/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.buggywuggy.common.PlainText;
import org.buggywuggy.importer.ImportWriter;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.license.LicenseCode;
import org.buggywuggy.mail.inbound.InboundMailProperties.Accept;
import org.buggywuggy.mail.inbound.InboundMailProperties.Mailbox;
import org.buggywuggy.notification.IssueEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What becomes of one mail: an unassigned issue in the mailbox's project, spam, or nothing
 * (automatic mail, which could start a mail loop). Each mail is handled once: the Message-ID is
 * kept in {@code inbound_mail}.
 *
 * <p>Nothing of the mail is lost: the whole text goes into the description (as plain text, with
 * Markdown escaped), with who sent it and when, and its files become attachments. Mail of the
 * feedback form ({@code X-BuggyWuggy-Feedback}) gets the issue type of the kind of feedback and
 * the label {@code feedback}.
 */
@Service
public class InboundMailService {

    private static final Logger log = LoggerFactory.getLogger("org.buggywuggy.inbound-mail");

    /** Room for the footer within the 100,000 characters of a description. */
    private static final int DESCRIPTION_MAX = 95_000;
    private static final Pattern TEXT_CHALLENGE = Pattern.compile("(?mi)^Donor challenge:\\s*(\\S+)\\s*$");
    private static final Pattern TEXT_CODE = Pattern.compile("(?mi)^Donor code:\\s*(\\S+)\\s*$");
    private static final Set<String> BULK = Set.of("bulk", "junk", "list", "auto_reply");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    public enum Action {
        CREATED, COMMENTED, SPAM, IGNORED, ALREADY_HANDLED
    }

    /** The issue an answer belongs to, and the external comment it answers (if known). */
    private record Thread(long issueId, Long commentId) {
    }

    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");
    private static final Pattern SUBJECT_KEY = Pattern.compile("\\[([A-Z][A-Z0-9_]{1,9})-(\\d+)\\]");
    /** Room for a note within the 100,000 characters of a comment. */
    private static final int COMMENT_MAX = 95_000;

    public record Outcome(Action action, String issueKey, String reason) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ImportWriter writer;
    private final IssueEvents events;
    private final LicenseCode codes;
    private final Clock clock;

    public InboundMailService(NamedParameterJdbcTemplate jdbc, ImportWriter writer, IssueEvents events,
            LicenseCode codes, Clock clock) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.events = events;
        this.codes = codes;
        this.clock = clock;
    }

    @Transactional
    public Outcome handle(Mailbox box, IncomingMail mail) {
        Optional<String> earlier = jdbc.queryForList("""
                select action from inbound_mail where mailbox = :mailbox and message_id = :id""",
                Map.of("mailbox", box.name(), "id", mail.messageId()), String.class).stream().findFirst();
        if (earlier.isPresent()) {
            return new Outcome(Action.ALREADY_HANDLED, null, earlier.get());
        }
        Instant now = clock.instant();

        String ignored = ignoreReason(box, mail);
        if (ignored != null) {
            record(box, mail, now, Action.IGNORED, ignored, null);
            log.info("Inbound mail {}: ignored mail from {} ({}): {}", box.name(), mail.sender(), ignored,
                    mail.subject());
            return new Outcome(Action.IGNORED, null, ignored);
        }
        // An answer to an external comment (or to the mail the issue came from): a comment. It
        // needs no donor proof: it answers a mail of ours.
        Optional<Thread> thread = thread(mail);
        if (thread.isPresent()) {
            return addAnswer(box, mail, thread.get(), now);
        }
        if (box.accept() == Accept.DONOR_PROOF) {
            String refused = donorProofProblem(mail);
            if (refused != null) {
                record(box, mail, now, Action.SPAM, refused, null);
                log.warn("Inbound mail {}: spam from {} removed ({})", box.name(), mail.sender(), refused);
                return new Outcome(Action.SPAM, null, refused);
            }
        }

        long projectId = jdbc.queryForList("select id from project where key = upper(:key)",
                Map.of("key", box.project() == null ? "" : box.project().strip()), Long.class).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Project " + box.project() + " does not exist."));
        long typeId = issueType(box, mail);
        long sourceId = source(box, now);
        long reporter = writer.user(sourceId, new SourceUser(mail.sender().toLowerCase(Locale.ROOT), mail.sender(),
                mail.senderName(), mail.sender()), null, false, box.host(), now).id();

        String summary = mail.subject().replaceAll("\\s+", " ").strip();
        if (summary.isEmpty()) {
            summary = "(no subject)";
        }
        if (summary.length() > 255) {
            summary = summary.substring(0, 254) + "…";
        }
        String text = mail.text();
        boolean cut = text.length() > DESCRIPTION_MAX;
        String description = description(box, mail, cut ? text.substring(0, DESCRIPTION_MAX) : text, cut);

        ImportWriter.CreatedIssue issue = writer.createIssue(new ImportWriter.NewIssue(projectId, typeId,
                writer.statuses(projectId, typeId).open().id(),
                writer.defaultPriority().map(ImportWriter.Named::id).orElse(null), null, summary, description,
                reporter, null, reporter, null, now, now, null, sourceId, mail.sender(), null));
        String key = writer.projectKey(projectId) + "-" + issue.number();
        if (mail.header("X-BuggyWuggy-Feedback") != null) {
            writer.labels(issue.id(), List.of("feedback"));
        }
        if (cut) {
            writer.attachment(issue.id(), reporter, "message.txt", "text/plain", now,
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        }
        for (IncomingMail.File file : mail.files()) {
            writer.attachment(issue.id(), reporter, file.name(), file.contentType(), now,
                    new ByteArrayInputStream(file.content()));
        }
        record(box, mail, now, Action.CREATED, null, issue.id());
        jdbc.update("""
                insert into issue_mail_contact (issue_id, mailbox, address, name, subject, message_id)
                values (:issue, :mailbox, :address, :name, :subject, :id)""",
                new MapSqlParameterSource().addValue("issue", issue.id()).addValue("mailbox", box.name())
                        .addValue("address", mail.sender()).addValue("name", mail.senderName())
                        .addValue("subject", mail.subject()).addValue("id", mail.messageId()));
        events.created(issue.id(), reporter, now);
        log.info("Inbound mail {}: {} created from mail of {}: {}", box.name(), key, mail.sender(), summary);
        return new Outcome(Action.CREATED, key, null);
    }

    /**
     * The issue a mail answers: by In-Reply-To / References (an external comment, or the mail
     * the issue was made of), else by "[KEY-12]" in the subject. Only when the mail comes from
     * the person the issue's external comments go to: nobody else can add comments by mail.
     */
    private Optional<Thread> thread(IncomingMail mail) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (String header : List.of("In-Reply-To", "References")) {
            String value = mail.header(header);
            if (value != null) {
                Matcher m = MESSAGE_ID.matcher(value);
                while (m.find()) {
                    ids.add(m.group());
                }
            }
        }
        if (!ids.isEmpty()) {
            Optional<Thread> comment = jdbc.query("""
                    select issue_id, id from comment where external and mail_message_id in (:ids)
                    order by id desc limit 1""", Map.of("ids", ids),
                    (rs, n) -> new Thread(rs.getLong(1), rs.getLong(2))).stream().findFirst();
            if (comment.isPresent() && fromContact(comment.get().issueId(), mail)) {
                return comment;
            }
            Optional<Thread> original = jdbc.query("""
                    select issue_id from issue_mail_contact where message_id in (:ids) order by issue_id limit 1""",
                    Map.of("ids", ids), (rs, n) -> new Thread(rs.getLong(1), null)).stream().findFirst();
            if (original.isPresent() && fromContact(original.get().issueId(), mail)) {
                return original;
            }
        }
        Matcher key = SUBJECT_KEY.matcher(mail.subject());
        while (key.find()) {
            Optional<Long> issue = jdbc.queryForList("""
                    select i.id from issue i join project p on p.id = i.project_id
                    where p.key = :key and i.number = :number""",
                    Map.of("key", key.group(1), "number", Long.parseLong(key.group(2))), Long.class)
                    .stream().findFirst();
            if (issue.isPresent() && fromContact(issue.get(), mail)) {
                Long last = jdbc.queryForList("""
                        select id from comment where issue_id = :issue and external order by id desc limit 1""",
                        Map.of("issue", issue.get()), Long.class).stream().findFirst().orElse(null);
                return Optional.of(new Thread(issue.get(), last));
            }
        }
        return Optional.empty();
    }

    private boolean fromContact(long issueId, IncomingMail mail) {
        return jdbc.queryForList("select address from issue_mail_contact where issue_id = :issue",
                Map.of("issue", issueId), String.class).stream().anyMatch(a -> a.equalsIgnoreCase(mail.sender()));
    }

    /**
     * The answer as a comment by its sender, below the comment it answers: what was written,
     * and apart from it the text it quoted. A closed issue is reopened.
     */
    private Outcome addAnswer(Mailbox box, IncomingMail mail, Thread thread, Instant now) {
        long author = writer.user(source(box, now), new SourceUser(mail.sender().toLowerCase(Locale.ROOT),
                mail.sender(), mail.senderName(), mail.sender()), null, false, box.host(), now).id();
        QuotedText.Parts parts = QuotedText.split(mail.text());
        String written = parts.written();
        boolean cut = written.length() > COMMENT_MAX;
        StringBuilder body = new StringBuilder(PlainText.toMarkdown(cut ? written.substring(0, COMMENT_MAX) : written));
        if (cut) {
            body.append("\n\n*The text was too long: the whole text is in the attachment message.txt.*");
        }
        List<String> names = new ArrayList<>(mail.files().stream().map(IncomingMail.File::name).toList());
        if (cut) {
            names.add("message.txt");
        }
        if (!names.isEmpty()) {
            body.append("\n\n*Attached: ").append(PlainText.toMarkdown(String.join(", ", names))).append(".*");
        }
        if (!mail.skipped().isEmpty()) {
            body.append("\n\n*Files left out because they are larger than ").append(size(box.maxAttachmentSize()))
                    .append(": ").append(PlainText.toMarkdown(String.join(", ", mail.skipped()))).append(".*");
        }
        String quoted = parts.quoted() == null ? null : PlainText.toMarkdown(parts.quoted());
        if (quoted != null && quoted.length() > COMMENT_MAX) {
            quoted = quoted.substring(0, COMMENT_MAX) + "\n\n…";
        }
        String text = body.toString().isBlank() ? "—" : body.toString();
        long commentId = jdbc.queryForObject("""
                insert into comment (issue_id, author_id, body, created_at, updated_at, received, mail_message_id,
                                     reply_to_comment_id, quoted)
                values (:issue, :author, :body, :now, :now, true, :messageId, :parent, :quoted) returning id""",
                new MapSqlParameterSource().addValue("issue", thread.issueId()).addValue("author", author)
                        .addValue("body", text).addValue("now", Timestamp.from(now))
                        .addValue("messageId", mail.messageId()).addValue("parent", thread.commentId())
                        .addValue("quoted", quoted), Long.class);
        List<IncomingMail.File> files = new ArrayList<>(mail.files());
        if (cut) {
            files.add(new IncomingMail.File("message.txt", "text/plain", written.getBytes(StandardCharsets.UTF_8)));
        }
        for (IncomingMail.File file : files) {
            long attachment = writer.attachment(thread.issueId(), author, file.name(), file.contentType(), now,
                    new ByteArrayInputStream(file.content()));
            jdbc.update("update attachment set comment_id = :comment where id = :id",
                    Map.of("comment", commentId, "id", attachment));
        }
        boolean reopened = reopen(thread.issueId(), author, now);
        jdbc.update("update issue set updated_at = :now where id = :id",
                Map.of("now", Timestamp.from(now), "id", thread.issueId()));
        record(box, mail, now, Action.COMMENTED, reopened ? "reopened" : null, thread.issueId());
        jdbc.update("update inbound_mail set comment_id = :comment where mailbox = :mailbox and message_id = :id",
                Map.of("comment", commentId, "mailbox", box.name(), "id", mail.messageId()));
        events.commented(thread.issueId(), author, now, commentId);
        String key = jdbc.queryForObject("""
                select p.key || '-' || i.number from issue i join project p on p.id = i.project_id where i.id = :id""",
                Map.of("id", thread.issueId()), String.class);
        log.info("Inbound mail {}: answer of {} added to {}{}", box.name(), mail.sender(), key,
                reopened ? ", which was reopened" : "");
        return new Outcome(Action.COMMENTED, key, null);
    }

    /** A closed issue that gets an answer is open again: back to the workflow's reopened status. */
    private boolean reopen(long issueId, long author, Instant now) {
        Map<String, Object> issue = jdbc.queryForMap("""
                select i.project_id, i.issue_type_id, i.status_id, s.name as status, s.category,
                       i.resolution_id, r.name as resolution
                from issue i join status s on s.id = i.status_id left join resolution r on r.id = i.resolution_id
                where i.id = :id""", Map.of("id", issueId));
        if (!"DONE".equals(issue.get("category"))) {
            return false;
        }
        ImportWriter.Named to = writer.statuses(((Number) issue.get("project_id")).longValue(),
                ((Number) issue.get("issue_type_id")).longValue()).reopened();
        jdbc.update("""
                update issue set status_id = :status, resolution_id = null, resolved_at = null, version = version + 1
                where id = :id""", Map.of("status", to.id(), "id", issueId));
        org.buggywuggy.issue.IssueHistory.Changes changes = new org.buggywuggy.issue.IssueHistory.Changes();
        changes.record(org.buggywuggy.issue.IssueHistory.Fields.STATUS, issue.get("status_id"),
                (String) issue.get("status"), to.id(), to.name());
        if (issue.get("resolution_id") != null) {
            changes.record(org.buggywuggy.issue.IssueHistory.Fields.RESOLUTION, issue.get("resolution_id"),
                    (String) issue.get("resolution"), null, null);
        }
        writer.history(issueId, author, now, changes);
        return true;
    }

    /** Mail that must not become an issue, because answering it could start a mail loop. */
    private static String ignoreReason(Mailbox box, IncomingMail mail) {
        if (mail.sender() == null || !mail.sender().contains("@")) {
            return "no sender";
        }
        String auto = mail.header("Auto-Submitted");
        if (auto != null && !auto.strip().equalsIgnoreCase("no")) {
            return "automatic mail (Auto-Submitted: " + auto.strip() + ")";
        }
        String precedence = mail.header("Precedence");
        if (precedence != null && BULK.contains(precedence.strip().toLowerCase(Locale.ROOT))) {
            return "automatic mail (Precedence: " + precedence.strip() + ")";
        }
        if (mail.header("X-Autoreply") != null || mail.header("X-Autorespond") != null) {
            return "automatic reply";
        }
        String own = box.replyAddress();
        if (own != null && own.equalsIgnoreCase(mail.sender())) {
            return "sent from the mailbox itself";
        }
        return null;
    }

    /** Null when the mail has a valid donor proof: in its headers, or in its text (sent by hand). */
    private String donorProofProblem(IncomingMail mail) {
        String challenge = mail.header("X-BuggyWuggy-Donor-Challenge");
        String code = mail.header("X-BuggyWuggy-Donor-Code");
        if (challenge == null || code == null) {
            Matcher c = TEXT_CHALLENGE.matcher(mail.text());
            Matcher d = TEXT_CODE.matcher(mail.text());
            if (c.find() && d.find()) {
                challenge = c.group(1);
                code = d.group(1);
            }
        }
        if (challenge == null || code == null) {
            return "no donor proof";
        }
        Optional<LocalDate> until = codes.verify(challenge.strip(), code.strip());
        if (until.isEmpty()) {
            return "invalid donor proof";
        }
        if (until.get().isBefore(LocalDate.now(clock))) {
            return "donation expired on " + until.get();
        }
        return null;
    }

    /** The type of the feedback (bug, feature), else the mailbox's type, else Task, else the first type. */
    private long issueType(Mailbox box, IncomingMail mail) {
        String feedback = mail.header("X-BuggyWuggy-Feedback");
        List<String> names = new ArrayList<>();
        if (feedback != null) {
            switch (feedback.strip().toLowerCase(Locale.ROOT)) {
                case "bug" -> names.add("Bug");
                case "feature" -> names.add("Feature");
                default -> {
                    // a compliment: the mailbox's type
                }
            }
        }
        if (box.issueType() != null && !box.issueType().isBlank()) {
            names.add(box.issueType().strip());
        }
        names.add("Task");
        for (String name : names) {
            Optional<Long> id = jdbc.queryForList("""
                    select id from issue_type where lower(name) = lower(:name) and hierarchy_level = 0""",
                    Map.of("name", name), Long.class).stream().findFirst();
            if (id.isPresent()) {
                return id.get();
            }
        }
        return jdbc.queryForObject("select id from issue_type where hierarchy_level = 0 order by position, id limit 1",
                Map.of(), Long.class);
    }

    /** The origin of the issues of one mailbox ("Imported from E-mail"), and its people. */
    private long source(Mailbox box, Instant now) {
        String base = "imap://" + box.host();
        String path = box.username() + "/" + box.folder();
        Optional<Long> id = jdbc.queryForList("""
                select id from import_source where kind = 'MAIL' and lower(base_url) = lower(:base)
                and lower(project_path) = lower(:path)""", Map.of("base", base, "path", path), Long.class)
                .stream().findFirst();
        return id.orElseGet(() -> jdbc.queryForObject("""
                insert into import_source (kind, base_url, project_path, label, created_at)
                values ('MAIL', :base, :path, :label, :now) returning id""",
                new MapSqlParameterSource().addValue("base", base).addValue("path", path)
                        .addValue("label", "Mailbox " + box.name()).addValue("now", Timestamp.from(now)), Long.class));
    }

    private static String description(Mailbox box, IncomingMail mail, String text, boolean cut) {
        StringBuilder out = new StringBuilder();
        if (!text.isBlank()) {
            out.append(PlainText.toMarkdown(text)).append("\n\n");
        }
        if (cut) {
            out.append("*The text was too long: the whole text is in the attachment message.txt.*\n\n");
        }
        out.append("---\n");
        String from = mail.senderName() == null ? mail.sender() : mail.senderName() + " <" + mail.sender() + ">";
        out.append("*Received by e-mail from ").append(PlainText.toMarkdown(from));
        if (mail.sentAt() != null) {
            out.append(", sent ").append(WHEN.format(mail.sentAt()));
        }
        out.append(" (mailbox ").append(PlainText.toMarkdown(box.name())).append(").*");
        if (!mail.skipped().isEmpty()) {
            out.append("\n\n*Files left out because they are larger than ").append(size(box.maxAttachmentSize()))
                    .append(": ").append(PlainText.toMarkdown(String.join(", ", mail.skipped()))).append(".*");
        }
        return out.toString();
    }

    private static String size(org.springframework.util.unit.DataSize size) {
        long bytes = size.toBytes();
        return bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0 ? size.toMegabytes() + " MB"
                : bytes >= 1024 && bytes % 1024 == 0 ? size.toKilobytes() + " KB" : bytes + " bytes";
    }

    private void record(Mailbox box, IncomingMail mail, Instant now, Action action, String reason, Long issueId) {
        jdbc.update("""
                insert into inbound_mail (mailbox, message_id, sender, sender_name, subject, received_at, processed_at,
                                          action, reason, issue_id)
                values (:mailbox, :id, :sender, :name, :subject, :received, :now, :action, :reason, :issue)""",
                new MapSqlParameterSource().addValue("mailbox", box.name()).addValue("id", mail.messageId())
                        .addValue("sender", mail.sender()).addValue("name", mail.senderName())
                        .addValue("subject", mail.subject())
                        .addValue("received", mail.sentAt() == null ? null : Timestamp.from(mail.sentAt()))
                        .addValue("now", Timestamp.from(now)).addValue("action", action.name())
                        .addValue("reason", reason).addValue("issue", issueId));
    }
}
