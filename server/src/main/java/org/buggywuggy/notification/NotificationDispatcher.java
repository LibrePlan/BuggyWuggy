/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.IssueHistory.Item;
import org.buggywuggy.language.LanguageService;
import org.buggywuggy.mail.IssueMail;
import org.buggywuggy.mail.IssueMail.Change;
import org.buggywuggy.mail.IssueMail.Model;
import org.buggywuggy.mail.IssueMail.Recipient;
import org.buggywuggy.mail.MailOutbox;
import org.buggywuggy.mail.MailProperties;
import org.buggywuggy.mail.MailTemplateKey;
import org.buggywuggy.mail.MailTemplateService;
import org.buggywuggy.mail.MailTemplateService.RenderedMail;
import org.buggywuggy.mail.MailTemplateService.Template;
import org.buggywuggy.mail.MailTransport;
import org.buggywuggy.notification.IssueEvents.Changed;
import org.buggywuggy.notification.IssueEvents.Commented;
import org.buggywuggy.notification.IssueEvents.Deleted;
import org.buggywuggy.notification.NotificationPreferences.Preferences;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns issue events into mails. Events of the same person on the same issue are bundled:
 * a bundle is sent once nothing was added to it for {@code quiet-period}, but never later
 * than {@code max-delay} after its first event. A bundle gets one mail per recipient, with
 * the template of its most important change and in the recipient's language.
 *
 * <p>Recipients are the reporter, the assignee (also the previous one, when it changed) and
 * the watchers; only active users with an e-mail address who may still see the issue, who
 * did not turn notifications off, and not the person who made the change unless they asked
 * for that.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int BATCH = 2000;
    /** Fields with one value: several changes in a bundle are shown as one, first → last. */
    private static final Set<String> SINGLE_VALUED = Set.of(Fields.SUMMARY, Fields.DESCRIPTION, Fields.ISSUE_TYPE,
            Fields.PRIORITY, Fields.ASSIGNEE, Fields.REPORTER, Fields.DUE_DATE, Fields.PARENT, Fields.STATUS,
            Fields.RESOLUTION);

    private record Event(long id, String type, long issueId, Long actorId, String payload, Instant at) {
    }

    private record Group(long issueId, Long actorId) {
    }

    private record IssueRow(long projectId, String key, String summary, String description, String type,
            String status, String priority, String assignee, String reporter, Long reporterId, Long assigneeId,
            String projectKey, String projectName) {
    }

    private record UserRow(long id, String username, String displayName, String email, String locale) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MailTransport transport;
    private final MailProperties properties;
    private final MailOutbox outbox;
    private final MailTemplateService templates;
    private final LanguageService languages;
    private final NotificationPreferences preferences;
    private final ProjectPermissionService permissions;
    private final MessageSource messages;
    private final JsonMapper json;
    private final Clock clock;
    private final String baseUrl;

    public NotificationDispatcher(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
            MailTransport transport, MailOutbox outbox, MailTemplateService templates, LanguageService languages,
            NotificationPreferences preferences, ProjectPermissionService permissions, MessageSource messages,
            JsonMapper json, Clock clock, @Value("${buggywuggy.base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.transport = transport;
        this.properties = transport.properties();
        this.outbox = outbox;
        this.templates = templates;
        this.languages = languages;
        this.preferences = preferences;
        this.permissions = permissions;
        this.messages = messages;
        this.json = json;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${buggywuggy.mail.poll-interval:10s}", initialDelayString = "20s")
    public void run() {
        try {
            transactions.executeWithoutResult(status -> dispatch());
        } catch (RuntimeException e) {
            log.error("Dispatching notifications failed", e);
        }
    }

    /** Handles the bundles that are ready; returns the number of queued mails. Call in a transaction. */
    public int dispatch() {
        List<Event> events = jdbc.query("""
                select id, event_type, issue_id, actor_id, payload::text, created_at from outbox_event
                where processed_at is null and event_type like 'issue.%'
                order by id limit :limit for update skip locked""", Map.of("limit", BATCH), (rs, n) -> {
            long actor = rs.getLong(4);
            return new Event(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.wasNull() ? null : actor,
                    rs.getString(5), rs.getTimestamp(6).toInstant());
        });
        if (events.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        if (!transport.configured()) {
            markProcessed(events.stream().map(Event::id).toList(), now);
            return 0;
        }
        Map<Group, List<Event>> groups = new LinkedHashMap<>();
        events.forEach(e -> groups.computeIfAbsent(new Group(e.issueId(), e.actorId()), g -> new ArrayList<>()).add(e));

        int mails = 0;
        List<Long> done = new ArrayList<>();
        for (List<Event> group : groups.values()) {
            Instant first = group.getFirst().at();
            Instant last = group.getLast().at();
            boolean deleted = group.stream().anyMatch(e -> e.type().equals(IssueEvents.DELETED));
            boolean quiet = Duration.between(last, now).compareTo(properties.quietPeriod()) >= 0;
            boolean overdue = Duration.between(first, now).compareTo(properties.maxDelay()) >= 0;
            if (!deleted && !quiet && !overdue) {
                continue;
            }
            mails += deleted ? sendDeleted(group) : sendBundle(group);
            group.forEach(e -> done.add(e.id()));
        }
        markProcessed(done, now);
        return mails;
    }

    // --- bundles -------------------------------------------------------------------------

    private int sendBundle(List<Event> group) {
        long issueId = group.getFirst().issueId();
        Long actorId = group.getFirst().actorId();
        IssueRow issue = loadIssue(issueId);
        if (issue == null) {
            return 0; // deleted meanwhile; the deletion has its own mail
        }
        boolean created = false;
        List<Item> items = new ArrayList<>();
        List<Long> commentIds = new ArrayList<>();
        for (Event e : group) {
            switch (e.type()) {
                case IssueEvents.CREATED -> created = true;
                case IssueEvents.CHANGED -> items.addAll(json.readValue(e.payload(), Changed.class).items());
                case IssueEvents.COMMENTED -> commentIds.add(json.readValue(e.payload(), Commented.class).commentId());
                default -> log.warn("Unknown issue event type {}", e.type());
            }
        }
        List<String> comments = commentIds.isEmpty() ? List.of() : jdbc.queryForList(
                "select body from comment where id in (:ids) order by created_at, id", Map.of("ids", commentIds),
                String.class);
        List<Change> changes = mergeChanges(items);
        List<String> attachments = items.stream()
                .filter(i -> i.field().equals(Fields.ATTACHMENT) && i.toText() != null).map(Item::toText).toList();
        if (!created && changes.isEmpty() && comments.isEmpty()) {
            return 0; // e.g. the comment was deleted again
        }
        MailTemplateKey key = templateFor(created, items, changes, comments);

        Set<Long> recipients = new LinkedHashSet<>();
        if (issue.reporterId() != null) {
            recipients.add(issue.reporterId());
        }
        if (issue.assigneeId() != null) {
            recipients.add(issue.assigneeId());
        }
        recipients.addAll(jdbc.queryForList("select user_id from watcher where issue_id = :id",
                Map.of("id", issueId), Long.class));
        items.stream().filter(i -> i.field().equals(Fields.ASSIGNEE) && i.from() != null)
                .forEach(i -> parseId(i.from(), recipients));
        recipients.removeIf(u -> !permissions.userHas(u, issue.projectId(), ProjectPermission.BROWSE_PROJECT));

        Model model = new Model(actorName(actorId), issue.key(), issue.summary(), issueUrl(issue.key()),
                issue.type(), issue.status(), issue.priority(), issue.assignee(), issue.reporter(),
                issue.description(), issue.projectKey(), issue.projectName(), changes, comments, attachments,
                projectKeys());
        return send(key, model, recipients, actorId);
    }

    private int sendDeleted(List<Event> group) {
        Event event = group.stream().filter(e -> e.type().equals(IssueEvents.DELETED)).reduce((a, b) -> b)
                .orElseThrow();
        Deleted d = json.readValue(event.payload(), Deleted.class);
        Model model = new Model(actorName(event.actorId()), d.key(), d.summary(), baseUrl, d.issueType(),
                d.status(), null, null, null, null, d.projectKey(), d.projectName(), List.of(), List.of(), List.of(),
                Set.of());
        return send(MailTemplateKey.ISSUE_DELETED, model, new LinkedHashSet<>(d.recipients()), event.actorId());
    }

    private int send(MailTemplateKey key, Model model, Set<Long> recipientIds, Long actorId) {
        if (recipientIds.isEmpty()) {
            return 0;
        }
        List<UserRow> users = jdbc.query("""
                select id, username, display_name, email, locale from user_account
                where id in (:ids) and active and not placeholder and email is not null""",
                Map.of("ids", recipientIds), (rs, n) -> new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
        Map<String, Template> byLanguage = new HashMap<>();
        int count = 0;
        for (UserRow user : users) {
            Preferences prefs = preferences.of(user.id());
            if (!prefs.notifications() || (Objects.equals(user.id(), actorId) && !prefs.ownChanges())) {
                continue;
            }
            String language = languages.resolve(user.locale());
            Template template = byLanguage.computeIfAbsent(language, l -> templates.effective(key, l));
            RenderedMail mail = IssueMail.render(key, template, model,
                    new Recipient(user.displayName(), user.username(), baseUrl, baseUrl + "/profile"),
                    Locale.forLanguageTag(language), messages);
            outbox.enqueue(user.id(), user.email(), mail.subject(), mail.html(), mail.text());
            count++;
        }
        return count;
    }

    // --- helpers ---------------------------------------------------------------------

    /** All project keys: issue keys in descriptions and comments link only for these. */
    private Set<String> projectKeys() {
        return Set.copyOf(jdbc.queryForList("select key from project", Map.of(), String.class));
    }

    /** The most important change decides the template. */
    static MailTemplateKey templateFor(boolean created, List<Item> items, List<Change> changes, List<String> comments) {
        if (created) {
            return MailTemplateKey.ISSUE_CREATED;
        }
        if (!comments.isEmpty()) {
            return MailTemplateKey.ISSUE_COMMENTED;
        }
        if (changes.stream().anyMatch(c -> c.field().equals(Fields.STATUS))) {
            return MailTemplateKey.ISSUE_TRANSITIONED;
        }
        if (changes.stream().anyMatch(c -> c.field().equals(Fields.ASSIGNEE))) {
            return MailTemplateKey.ISSUE_ASSIGNED;
        }
        if (!items.isEmpty() && items.stream().allMatch(i -> i.field().equals(Fields.ATTACHMENT))) {
            return MailTemplateKey.ISSUE_ATTACHMENT_ADDED;
        }
        return MailTemplateKey.ISSUE_UPDATED;
    }

    /** Several changes of a single-valued field become one (first → last); no-op changes disappear. */
    static List<Change> mergeChanges(List<Item> items) {
        Map<String, Change> merged = new LinkedHashMap<>();
        List<Change> result = new ArrayList<>();
        for (Item item : items) {
            if (SINGLE_VALUED.contains(item.field())) {
                Change previous = merged.get(item.field());
                merged.put(item.field(), new Change(item.field(),
                        previous != null ? previous.from() : item.fromText(), item.toText()));
            } else {
                result.add(new Change(item.field(), item.fromText(), item.toText()));
            }
        }
        List<Change> single = merged.values().stream()
                .filter(c -> !Objects.equals(blankToNull(c.from()), blankToNull(c.to()))).toList();
        List<Change> all = new ArrayList<>(single);
        all.addAll(result);
        return all;
    }

    private IssueRow loadIssue(long issueId) {
        return jdbc.query("""
                select p.id, p.key || '-' || i.number, i.summary, i.description, t.name, s.name, pr.name,
                       a.display_name, r.display_name, i.reporter_id, i.assignee_id, p.key, p.name
                from issue i
                join project p on p.id = i.project_id
                join issue_type t on t.id = i.issue_type_id
                join status s on s.id = i.status_id
                left join priority pr on pr.id = i.priority_id
                left join user_account a on a.id = i.assignee_id
                left join user_account r on r.id = i.reporter_id
                where i.id = :id""", Map.of("id", issueId), (rs, n) -> {
            long reporter = rs.getLong(10);
            Long reporterId = rs.wasNull() ? null : reporter;
            long assignee = rs.getLong(11);
            Long assigneeId = rs.wasNull() ? null : assignee;
            return new IssueRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), reporterId, assigneeId,
                    rs.getString(12), rs.getString(13));
        }).stream().findFirst().orElse(null);
    }

    private String actorName(Long actorId) {
        if (actorId == null) {
            return "BuggyWuggy";
        }
        return jdbc.queryForList("select display_name from user_account where id = :id", Map.of("id", actorId),
                String.class).stream().findFirst().orElse("BuggyWuggy");
    }

    private String issueUrl(String key) {
        return baseUrl + "/browse/" + key;
    }

    private void markProcessed(List<Long> ids, Instant now) {
        if (!ids.isEmpty()) {
            jdbc.update("update outbox_event set processed_at = :now where id in (:ids)",
                    Map.of("now", Timestamp.from(now), "ids", ids));
        }
    }

    private static void parseId(String value, Set<Long> into) {
        try {
            into.add(Long.parseLong(value));
        } catch (NumberFormatException e) {
            // not a user id
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
