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
        CREATED, SPAM, IGNORED, ALREADY_HANDLED
    }

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
        events.created(issue.id(), reporter, now);
        log.info("Inbound mail {}: {} created from mail of {}: {}", box.name(), key, mail.sender(), summary);
        return new Outcome(Action.CREATED, key, null);
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
