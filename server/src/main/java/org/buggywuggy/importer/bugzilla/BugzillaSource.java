/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.bugzilla;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.Source.SourceEvent.Kind;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Attachment;
import org.buggywuggy.importer.bugzilla.BugzillaApi.AttachmentData;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Bug;
import org.buggywuggy.importer.bugzilla.BugzillaApi.BugAttachments;
import org.buggywuggy.importer.bugzilla.BugzillaApi.BugHistory;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Bugs;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Change;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Comment;
import org.buggywuggy.importer.bugzilla.BugzillaApi.CommentList;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Comments;
import org.buggywuggy.importer.bugzilla.BugzillaApi.FieldValue;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Fields;
import org.buggywuggy.importer.bugzilla.BugzillaApi.History;
import org.buggywuggy.importer.bugzilla.BugzillaApi.HistoryEntry;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Milestone;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Product;
import org.buggywuggy.importer.bugzilla.BugzillaApi.UserDetail;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Users;
import org.springframework.http.HttpStatus;

/**
 * One Bugzilla product as a source of bugs. The bug type (or, before Bugzilla 5.2, the severity
 * "enhancement"), priority, severity and component become labels the administrator maps
 * ({@code type:defect}, {@code priority/P1}, {@code severity/critical}, {@code component/Backend});
 * keywords are labels; the target milestone becomes the version. Bugs restricted to groups get
 * the label {@code restricted}; private comments and attachments are left out.
 *
 * <p>Comment 0 is the description. The history comes from the bug activity; "depends on"
 * becomes a dependency, a duplicate a Duplicate link, and "see also" of bugs in the same
 * Bugzilla a Relates link. Bug numbers are the same in all products, so a bug is referred to as
 * "bug 12" (also in texts).
 */
final class BugzillaSource implements Source {

    static final String RESTRICTED = "restricted";
    static final String TYPE_PREFIX = "type:";
    static final String PRIORITY_PREFIX = "priority/";
    static final String SEVERITY_PREFIX = "severity/";
    static final String COMPONENT_PREFIX = "component/";
    /** Bugzilla's "no milestone" and "no priority". */
    private static final Set<String> NONE = Set.of("---", "--", "");
    /** The statuses of a default Bugzilla, when the field values cannot be read. */
    private static final Set<String> CLOSED = Set.of("RESOLVED", "VERIFIED", "CLOSED");
    private static final String FIELDS = "id,product,summary,status,resolution,is_open,severity,priority,component,"
            + "type,target_milestone,creator,creator_detail,assigned_to,assigned_to_detail,creation_time,"
            + "last_change_time,deadline,keywords,groups,depends_on,blocks,dupe_of,see_also";

    private final BugzillaConnector.Api api;
    private final Product product;
    private final Pattern attachments;
    private final Pattern bugLinks;
    private final Map<String, SourceUser> users = new HashMap<>();
    private final Map<Long, Bug> bugs = new HashMap<>();
    /** The product of bugs elsewhere that dependencies point to; "" when unknown. */
    private final Map<Long, String> otherProducts = new HashMap<>();
    /** The description, files and closing time each issue gets from {@link #details}. */
    private final Map<Long, SourceIssue> completed = new HashMap<>();
    private Set<String> openStatuses;

    BugzillaSource(BugzillaConnector.Api api, Product product) {
        this.api = api;
        this.product = product;
        String web = Pattern.quote(api.web.toString());
        this.attachments = Pattern.compile(web + "/attachment\\.cgi\\?id=(\\d+)(?:&[^\\s)\"'<>\\]]*)?");
        this.bugLinks = Pattern.compile("()" + web + "/show_bug\\.cgi\\?id=(\\d+)");
    }

    static RepositoryInfo info(BugzillaConnector.Api api, Product p) {
        return new RepositoryInfo(p.name(), p.description() == null || p.description().isBlank() ? null
                : p.description().strip(), false, !Boolean.FALSE.equals(p.isActive()), -1,
                api.webUri("describecomponents.cgi", Map.of("product", p.name())).toString());
    }

    @Override
    public String kind() {
        return BugzillaConnector.KIND;
    }

    @Override
    public String displayName() {
        return "Bugzilla";
    }

    @Override
    public String baseUrl() {
        return api.web.toString();
    }

    @Override
    public String webUrl() {
        return api.web.toString();
    }

    @Override
    public String path() {
        return product.name();
    }

    @Override
    public String host() {
        return api.web.getHost();
    }

    /** Bug numbers are unique in a Bugzilla, whatever the product. */
    @Override
    public String reference(String repository, long number) {
        return "bug " + number;
    }

    @Override
    public Pattern issueLinks() {
        return bugLinks;
    }

    /** "bug 12" and "Bug #12", as Bugzilla links them. */
    @Override
    public Pattern issueKeys() {
        return Pattern.compile("(?i)\\bbug\\s+#?()(\\d+)\\b");
    }

    @Override
    public String issueUrl(String repository, long number) {
        return api.web + "/show_bug.cgi?id=" + number;
    }

    @Override
    public Pattern attachmentLinks() {
        return attachments;
    }

    @Override
    public String attachmentUrl(String found) {
        return found;
    }

    @Override
    public void watch(BooleanSupplier cancelled, ReadOnlyHttp.Waits waits) {
        api.http.watch(cancelled, waits);
    }

    @Override
    public RepositoryInfo info() {
        return info(api, product);
    }

    @Override
    public List<SourceLabel> labels() {
        return product.components() == null ? List.of() : product.components().stream()
                .filter(c -> c.name() != null)
                .map(c -> new SourceLabel(COMPONENT_PREFIX + c.name(), "", c.description())).toList();
    }

    @Override
    public List<SourceMilestone> milestones() {
        return product.milestones() == null ? List.of() : product.milestones().stream()
                .filter(m -> m.name() != null && !NONE.contains(m.name().strip()))
                .map((Milestone m) -> new SourceMilestone(m.name(), null, null, Boolean.FALSE.equals(m.isActive())))
                .toList();
    }

    @Override
    public List<SourceIssue> issues() {
        bugs.clear();
        completed.clear();
        List<SourceIssue> issues = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int offset = 0; offset < 10_000_000; ) {
            Bugs page = api.get("/bug", Map.of("product", product.name(), "include_fields", FIELDS,
                    "order", "bug_id", "limit", String.valueOf(BugzillaConnector.PAGE_SIZE),
                    "offset", String.valueOf(offset)), Bugs.class);
            List<Bug> found = page == null || page.bugs() == null ? List.of() : page.bugs();
            boolean news = false;
            for (Bug b : found) {
                if (b.id() == null || !seen.add(b.id())) {
                    continue;
                }
                news = true;
                // Only this product: a Bugzilla that ignores the parameter would return all.
                if (b.product() == null || b.product().equalsIgnoreCase(product.name())) {
                    bugs.put(b.id(), b);
                    issues.add(issue(b));
                }
            }
            offset += found.size();
            // Until an empty page: max_search_results may give fewer than asked for. A page
            // without new bugs means the offset is ignored.
            if (found.isEmpty() || !news) {
                break;
            }
        }
        return issues;
    }

    private SourceIssue issue(Bug b) {
        Set<String> labels = new LinkedHashSet<>();
        if (b.type() != null && !b.type().isBlank()) {
            labels.add(TYPE_PREFIX + b.type());
        } else if ("enhancement".equalsIgnoreCase(b.severity())) {
            labels.add(TYPE_PREFIX + "enhancement");
        }
        if (b.priority() != null && !NONE.contains(b.priority().strip())) {
            labels.add(PRIORITY_PREFIX + b.priority());
        }
        if (b.severity() != null && !b.severity().isBlank() && !"enhancement".equalsIgnoreCase(b.severity())) {
            labels.add(SEVERITY_PREFIX + b.severity());
        }
        if (b.component() != null && !b.component().isBlank()) {
            labels.add(COMPONENT_PREFIX + b.component());
        }
        if (b.keywords() != null) {
            b.keywords().stream().filter(k -> k != null && !k.isBlank()).map(String::strip).forEach(labels::add);
        }
        if (b.groups() != null && !b.groups().isEmpty()) {
            labels.add(RESTRICTED);
        }
        boolean closed = !isOpen(b);
        CloseReason reason = !closed ? null : b.dupeOf() != null ? CloseReason.DUPLICATE
                : Source.closeReason(b.resolution());
        String milestone = b.targetMilestone() == null || NONE.contains(b.targetMilestone().strip()) ? null
                : b.targetMilestone();
        SourceUser assignee = user(b.assignedTo(), b.assignedToDetail());
        return new SourceIssue(String.valueOf(b.id()), b.id(), issueUrl(null, b.id()),
                user(b.creator(), b.creatorDetail()), b.summary(), null, new ArrayList<>(labels), milestone,
                assignee == null ? List.of() : List.of(assignee), closed, reason, instant(b.creationTime()),
                instant(b.lastChangeTime()), null, date(b.deadline()), -1, List.of());
    }

    private boolean isOpen(Bug b) {
        if (b.isOpen() != null) {
            return b.isOpen();
        }
        return isOpen(b.status());
    }

    private boolean isOpen(String status) {
        if (status == null) {
            return true;
        }
        if (openStatuses == null) {
            openStatuses = new HashSet<>();
            Fields fields = optional(() -> api.get("/field/bug/bug_status", Map.of(), Fields.class), null);
            if (fields != null && fields.fields() != null) {
                fields.fields().stream().filter(f -> f.values() != null).flatMap(f -> f.values().stream())
                        .filter((FieldValue v) -> v.name() != null && Boolean.TRUE.equals(v.isOpen()))
                        .forEach(v -> openStatuses.add(v.name().toUpperCase(Locale.ROOT)));
            }
        }
        String value = status.toUpperCase(Locale.ROOT);
        return openStatuses.isEmpty() ? !CLOSED.contains(value) : openStatuses.contains(value);
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        long id = issue.number();
        Bug bug = bugs.get(id);
        List<Comment> all = comments(id);

        String description = null;
        List<SourceComment> comments = new ArrayList<>();
        for (Comment c : all) {
            if (Boolean.TRUE.equals(c.isPrivate())) {
                continue;
            }
            String text = BugzillaText.toMarkdown(c.text(), Boolean.TRUE.equals(c.isMarkdown()), api.web.toString());
            if (c.count() != null && c.count() == 0) {
                description = text;
            } else if (c.id() != null && text != null && !text.isBlank()) {
                Instant at = instant(c.creationTime());
                comments.add(new SourceComment(String.valueOf(c.id()),
                        user(c.creator() != null ? c.creator() : c.author(), null), text, at, at, List.of()));
            }
        }

        List<SourceEvent> events = events(id);
        Instant closedAt = null;
        if (issue.closed()) {
            for (SourceEvent e : events) {
                if (e.kind() == Kind.CLOSED) {
                    closedAt = e.at();
                }
            }
        }
        completed.put(id, new SourceIssue(issue.externalId(), issue.number(), issue.url(), issue.author(),
                issue.title(), description, issue.labels(), issue.milestone(), issue.assignees(), issue.closed(),
                issue.closeReason(), issue.createdAt(), issue.updatedAt(), closedAt, issue.due(), comments.size(),
                attachments(id)));

        List<SourceDependency> blockedBy = new ArrayList<>();
        List<SourceLink> links = new ArrayList<>();
        if (bug != null) {
            // "Depends on" is the other side of "blocks": added from this side only.
            if (bug.dependsOn() != null) {
                bug.dependsOn().stream().filter(o -> o != null).forEach(o -> blockedBy.add(
                        new SourceDependency(ref(o), null, null)));
            }
            if (bug.dupeOf() != null) {
                links.add(new SourceLink("Duplicate", ref(bug.dupeOf())));
            }
            if (bug.seeAlso() != null) {
                for (String url : bug.seeAlso()) {
                    Matcher m = url == null ? null : bugLinks.matcher(url.strip());
                    // Each side lists the other: added from the lower number only.
                    if (m != null && m.matches() && Long.parseLong(m.group(2)) > id) {
                        links.add(new SourceLink("Relates", ref(Long.parseLong(m.group(2)))));
                    }
                }
            }
        }
        return new SourceDetails(comments, events, blockedBy, List.of(), links);
    }

    /** With the description (comment 0), the attachments and the closing time that {@link #details} read. */
    @Override
    public SourceIssue complete(SourceIssue issue, SourceDetails details) {
        SourceIssue found = completed.remove(issue.number());
        return found == null ? issue : found;
    }

    private List<Comment> comments(long id) {
        Comments found = api.get("/bug/" + id + "/comment", Map.of(), Comments.class);
        CommentList list = found == null || found.bugs() == null ? null : found.bugs().get(String.valueOf(id));
        return list == null || list.comments() == null ? List.of() : list.comments();
    }

    /** The files of a bug, without obsolete and private ones and without their content. */
    private List<SourceAttachment> attachments(long id) {
        BugAttachments found = optional(() -> api.get("/bug/" + id + "/attachment",
                Map.of("exclude_fields", "data"), BugAttachments.class), null);
        List<Attachment> list = found == null || found.bugs() == null ? null : found.bugs().get(String.valueOf(id));
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .filter(a -> a.id() != null && a.fileName() != null && !Boolean.TRUE.equals(a.isPrivate())
                        && !Boolean.TRUE.equals(a.isObsolete()))
                .map(a -> new SourceAttachment(String.valueOf(a.id()), a.fileName(), a.size() == null ? -1 : a.size(),
                        api.web + "/attachment.cgi?id=" + a.id(), instant(a.creationTime())))
                .toList();
    }

    /** Through the API, which returns the content in base64: attachment.cgi does not take API keys. */
    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        if (attachment.size() > maxBytes) {
            throw new ReadOnlyHttp.TooLargeException(attachment.size());
        }
        AttachmentData found;
        try {
            found = api.get("/bug/attachment/" + attachment.key(), Map.of("include_fields", "data,content_type"),
                    AttachmentData.class);
        } catch (ApiException e) {
            throw new IOException(e.getMessage());
        }
        Attachment a = found == null || found.attachments() == null ? null
                : found.attachments().get(attachment.key());
        if (a == null || a.data() == null) {
            throw new IOException("no content");
        }
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(a.data());
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid content");
        }
        if (content.length > maxBytes) {
            throw new ReadOnlyHttp.TooLargeException(content.length);
        }
        return new ReadOnlyHttp.Download(new ByteArrayInputStream(content),
                java.net.URI.create(attachment.url()), a.contentType(), content.length);
    }

    // --- history -------------------------------------------------------------------------

    private List<SourceEvent> events(long id) {
        History found = optional(() -> api.get("/bug/" + id + "/history", Map.of(), History.class), null);
        List<SourceEvent> events = new ArrayList<>();
        if (found == null || found.bugs() == null) {
            return events;
        }
        for (BugHistory bug : found.bugs()) {
            if (bug.history() == null) {
                continue;
            }
            for (HistoryEntry entry : bug.history()) {
                Instant at = instant(entry.when());
                if (at == null || entry.changes() == null) {
                    continue;
                }
                SourceUser actor = user(entry.who(), null);
                // The resolution of a change set says why a bug was closed in it.
                String resolution = null;
                for (Change c : entry.changes()) {
                    if ("resolution".equals(c.fieldName()) && c.added() != null && !c.added().isBlank()) {
                        resolution = c.added();
                    }
                }
                for (Change c : entry.changes()) {
                    change(c, actor, at, resolution, events);
                }
            }
        }
        return events;
    }

    private void change(Change c, SourceUser actor, Instant at, String resolution, List<SourceEvent> events) {
        if (c.fieldName() == null) {
            return;
        }
        String removed = blankToNull(c.removed());
        String added = blankToNull(c.added());
        switch (c.fieldName()) {
            case "summary", "short_desc" -> events.add(new SourceEvent(Kind.RENAMED, actor, at, removed, added,
                    null, null));
            case "priority" -> label(PRIORITY_PREFIX, removed, added, actor, at, events);
            case "bug_severity", "severity" -> label(SEVERITY_PREFIX, removed, added, actor, at, events);
            case "component" -> label(COMPONENT_PREFIX, removed, added, actor, at, events);
            case "bug_type", "type" -> label(TYPE_PREFIX, removed, added, actor, at, events);
            case "keywords" -> {
                for (String k : split(removed)) {
                    events.add(new SourceEvent(Kind.LABEL_REMOVED, actor, at, null, k, null, null));
                }
                for (String k : split(added)) {
                    events.add(new SourceEvent(Kind.LABEL_ADDED, actor, at, null, k, null, null));
                }
            }
            case "assigned_to" -> {
                if (removed != null) {
                    events.add(new SourceEvent(Kind.UNASSIGNED, actor, at, null, null, user(removed, null), null));
                }
                if (added != null) {
                    events.add(new SourceEvent(Kind.ASSIGNED, actor, at, null, null, user(added, null), null));
                }
            }
            case "target_milestone" -> events.add(new SourceEvent(Kind.MILESTONE, actor, at, milestone(removed),
                    milestone(added), null, null));
            case "deadline" -> events.add(new SourceEvent(Kind.DUE_DATE, actor, at, dateText(removed),
                    dateText(added), null, null));
            case "status", "bug_status" -> {
                boolean wasOpen = removed == null || isOpen(removed);
                boolean nowOpen = added == null || isOpen(added);
                if (wasOpen && !nowOpen) {
                    events.add(new SourceEvent(Kind.CLOSED, actor, at, null, null, null,
                            Source.closeReason(resolution)));
                } else if (!wasOpen && nowOpen) {
                    events.add(new SourceEvent(Kind.REOPENED, actor, at, null, null, null, null));
                }
            }
            default -> {
                // CC, URL, whiteboard, version, platform, OS, flags, attachments: not part of the history
            }
        }
    }

    private static void label(String prefix, String removed, String added, SourceUser actor, Instant at,
            List<SourceEvent> events) {
        if (removed != null && !NONE.contains(removed)) {
            events.add(new SourceEvent(Kind.LABEL_REMOVED, actor, at, null, prefix + removed, null, null));
        }
        if (added != null && !NONE.contains(added)) {
            events.add(new SourceEvent(Kind.LABEL_ADDED, actor, at, null, prefix + added, null, null));
        }
    }

    private static String milestone(String value) {
        return value == null || NONE.contains(value.strip()) ? null : value;
    }

    private static List<String> split(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    /** A bug a dependency points to: in this product, or in the product it is in. */
    private SourceRef ref(long id) {
        if (bugs.containsKey(id)) {
            return new SourceRef(null, id);
        }
        String other = otherProducts.computeIfAbsent(id, i -> {
            Bugs found = optional(() -> api.get("/bug/" + i, Map.of("include_fields", "id,product"), Bugs.class),
                    null);
            return found == null || found.bugs() == null || found.bugs().isEmpty()
                    || found.bugs().getFirst().product() == null ? "" : found.bugs().getFirst().product();
        });
        return new SourceRef(other.isEmpty() || other.equalsIgnoreCase(product.name()) ? null : other, id);
    }

    // --- users ---------------------------------------------------------------------------

    /**
     * A person by login, which is usually an e-mail address: the part before the @ becomes the
     * login of a placeholder, the whole address its e-mail address. Without the details in the
     * bug, the real name is looked up once.
     */
    private SourceUser user(String login, UserDetail detail) {
        if (login == null || login.isBlank()) {
            return null;
        }
        String key = login.strip();
        SourceUser known = users.get(key.toLowerCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        UserDetail found = detail;
        if (found == null || found.realName() == null) {
            Users list = optional(() -> api.get("/user", Map.of("names", key, "include_fields",
                    "id,name,real_name,email"), Users.class), null);
            if (list != null && list.users() != null && !list.users().isEmpty()) {
                found = list.users().getFirst();
            }
        }
        String email = found != null && found.email() != null && found.email().contains("@") ? found.email()
                : key.contains("@") ? key : null;
        String name = found == null ? null : blankToNull(found.realName());
        int at = key.indexOf('@');
        String placeholder = at > 0 ? key.substring(0, at) : key;
        SourceUser user = new SourceUser(key, placeholder, name == null ? placeholder : name, email);
        users.put(key.toLowerCase(Locale.ROOT), user);
        return user;
    }

    /** What a bug or user may not show (401, 403, 404): the fallback instead of failing. */
    private static <T> T optional(Supplier<T> get, T fallback) {
        try {
            return get.get();
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND || e.status() == HttpStatus.BAD_REQUEST) {
                return fallback;
            }
            throw e;
        }
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate date(String value) {
        String text = dateText(value);
        return text == null ? null : LocalDate.parse(text);
    }

    private static String dateText(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10)).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
