/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.jira;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.Source.SourceEvent.Kind;
import org.buggywuggy.importer.jira.JiraApi.Attachment;
import org.buggywuggy.importer.jira.JiraApi.ChangeItem;
import org.buggywuggy.importer.jira.JiraApi.ChangelogPage;
import org.buggywuggy.importer.jira.JiraApi.Comment;
import org.buggywuggy.importer.jira.JiraApi.CommentPage;
import org.buggywuggy.importer.jira.JiraApi.Component;
import org.buggywuggy.importer.jira.JiraApi.Field;
import org.buggywuggy.importer.jira.JiraApi.Fields;
import org.buggywuggy.importer.jira.JiraApi.History;
import org.buggywuggy.importer.jira.JiraApi.IssueLink;
import org.buggywuggy.importer.jira.JiraApi.IssueWithChangelog;
import org.buggywuggy.importer.jira.JiraApi.Named;
import org.buggywuggy.importer.jira.JiraApi.Project;
import org.buggywuggy.importer.jira.JiraApi.RemoteLink;
import org.buggywuggy.importer.jira.JiraApi.SearchPage;
import org.buggywuggy.importer.jira.JiraApi.Status;
import org.buggywuggy.importer.jira.JiraApi.User;
import org.buggywuggy.importer.jira.JiraApi.Version;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * One Jira project as a source of issues. The issue type, priority and components become
 * labels the administrator maps ({@code type:Bug}, {@code priority/Major},
 * {@code component/Backend}); the first fix version becomes the version. Texts are converted
 * from wiki markup to Markdown. Comments visible only to a role or group, and internal
 * comments of Service Management, are left out. The history comes from the changelog;
 * sub-tasks and the issues of an epic are its sub-issues.
 */
final class JiraSource implements Source {

    /** Label for issues with a security level, so they can be found after the import. */
    static final String RESTRICTED = "restricted";
    static final String TYPE_PREFIX = "type:";
    static final String PRIORITY_PREFIX = "priority/";
    static final String COMPONENT_PREFIX = "component/";

    private static final String EPIC_LINK = "com.pyxis.greenhopper.jira:gh-epic-link";
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final List<String> FIELDS = List.of("summary", "description", "issuetype", "priority", "status",
            "resolution", "resolutiondate", "labels", "components", "fixVersions", "assignee", "reporter", "creator",
            "created", "updated", "duedate", "comment", "attachment", "issuelinks", "parent", "security");

    private final JiraConnector.Api api;
    private final Project project;
    private final Pattern attachments;
    private final Pattern keys;
    private final Map<String, SourceUser> users = new HashMap<>();
    private final Map<String, List<SourceRef>> children = new HashMap<>();
    /** The issue links of each issue (by id), from the list: SourceIssue has no place for them. */
    private final Map<String, List<IssueLink>> linksByIssue = new HashMap<>();
    private Map<String, Boolean> done;
    private String epicLinkField;

    JiraSource(JiraConnector.Api api, Project project) {
        this.api = api;
        this.project = project;
        String web = Pattern.quote(api.web.toString());
        this.attachments = Pattern.compile("(?:" + web + ")?/(?:secure/attachment|rest/api/(?:2|3|latest)/"
                + "attachment/content)/(\\d+)(?:/[^\\s)\"'<>\\]]*)?");
        this.keys = Pattern.compile("(?<![\\w/.\\-])(" + JiraConnector.PROJECT_KEY + ")-(\\d+)\\b");
    }

    static RepositoryInfo info(JiraConnector.Api api, Project p) {
        String name = p.name() == null || p.name().isBlank() ? p.key() : p.name();
        return new RepositoryInfo(p.key(), name, Boolean.TRUE.equals(p.isPrivate()), true, -1,
                api.web + "/browse/" + p.key());
    }

    @Override
    public String kind() {
        return JiraConnector.KIND;
    }

    @Override
    public String displayName() {
        return "Jira";
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
        return project.key();
    }

    @Override
    public String host() {
        return api.web.getHost();
    }

    @Override
    public String reference(String repository, long number) {
        return repository + "-" + number;
    }

    @Override
    public Pattern issueLinks() {
        return Pattern.compile(Pattern.quote(api.web.toString()) + "/(?:browse|projects/[A-Z][A-Z0-9_]*/issues)/("
                + JiraConnector.PROJECT_KEY + ")-(\\d+)/?");
    }

    @Override
    public Pattern issueKeys() {
        return keys;
    }

    @Override
    public String issueUrl(String repository, long number) {
        return api.web + "/browse/" + repository + "-" + number;
    }

    @Override
    public Pattern attachmentLinks() {
        return attachments;
    }

    @Override
    public void watch(BooleanSupplier cancelled, ReadOnlyHttp.Waits waits) {
        api.http.watch(cancelled, waits);
    }

    @Override
    public RepositoryInfo info() {
        return info(api, project);
    }

    @Override
    public List<SourceLabel> labels() {
        return optional(() -> api.list("/project/" + project.key() + "/components", Map.of(), Component.class))
                .stream().filter(c -> c.name() != null)
                .map(c -> new SourceLabel(COMPONENT_PREFIX + c.name(), "", c.description())).toList();
    }

    @Override
    public List<SourceMilestone> milestones() {
        return optional(() -> api.list("/project/" + project.key() + "/versions", Map.of(), Version.class)).stream()
                .filter(v -> v.name() != null)
                .map(v -> new SourceMilestone(v.name(), v.description(), date(v.releaseDate()),
                        Boolean.TRUE.equals(v.released())))
                .toList();
    }

    @Override
    public List<SourceIssue> issues() {
        List<String> fields = new ArrayList<>(FIELDS);
        String epicLink = epicLinkField();
        if (epicLink != null) {
            fields.add(epicLink);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("jql", "project = \"" + project.key() + "\" ORDER BY key ASC");
        query.put("fields", String.join(",", fields));
        query.put("maxResults", String.valueOf(JiraConnector.PAGE_SIZE));

        List<JsonNode> nodes = new ArrayList<>();
        if (api.cloud()) {
            // Cloud only pages with a token since /search was retired.
            String next = null;
            for (int page = 0; page < 100_000; page++) {
                Map<String, String> params = new LinkedHashMap<>(query);
                if (next != null) {
                    params.put("nextPageToken", next);
                }
                SearchPage result = api.get("/search/jql", params, SearchPage.class);
                nodes.addAll(result.issues() == null ? List.of() : result.issues());
                next = result.nextPageToken();
                if (Boolean.TRUE.equals(result.isLast()) || next == null || next.isBlank()) {
                    break;
                }
            }
        } else {
            int startAt = 0;
            for (int page = 0; page < 100_000; page++) {
                Map<String, String> params = new LinkedHashMap<>(query);
                params.put("startAt", String.valueOf(startAt));
                SearchPage result = api.get("/search", params, SearchPage.class);
                List<JsonNode> found = result.issues() == null ? List.of() : result.issues();
                nodes.addAll(found);
                startAt += found.size();
                if (found.isEmpty() || startAt >= result.total()) {
                    break;
                }
            }
        }

        children.clear();
        linksByIssue.clear();
        List<SourceIssue> issues = new ArrayList<>();
        for (JsonNode node : nodes) {
            String key = text(node, "key");
            JsonNode fieldsNode = node.get("fields");
            if (key == null || fieldsNode == null || number(key) < 0) {
                continue;
            }
            Fields f = ReadOnlyHttp.CAMEL_CASE_JSON.treeToValue(fieldsNode, Fields.class);
            String parent = f.parent() != null ? f.parent().key()
                    : epicLink == null ? null : text(fieldsNode, epicLink);
            if (parent != null && parent.startsWith(project.key() + "-") && number(parent) >= 0) {
                children.computeIfAbsent(parent, p -> new ArrayList<>()).add(new SourceRef(null, number(key)));
            }
            if (f.issuelinks() != null && !f.issuelinks().isEmpty()) {
                linksByIssue.put(text(node, "id"), f.issuelinks());
            }
            issues.add(issue(text(node, "id"), key, f));
        }
        return issues;
    }

    private SourceIssue issue(String id, String key, Fields f) {
        Set<String> labels = new LinkedHashSet<>(f.labels() == null ? List.of() : f.labels());
        if (f.issuetype() != null && f.issuetype().name() != null) {
            labels.add(TYPE_PREFIX + f.issuetype().name());
        }
        if (f.priority() != null && f.priority().name() != null) {
            labels.add(PRIORITY_PREFIX + f.priority().name());
        }
        if (f.components() != null) {
            f.components().stream().filter(c -> c.name() != null).forEach(c -> labels.add(COMPONENT_PREFIX + c.name()));
        }
        if (f.security() != null) {
            labels.add(RESTRICTED);
        }
        boolean closed = f.status() != null && isDone(f.status());
        List<SourceAttachment> files = f.attachment() == null ? List.of() : f.attachment().stream()
                .filter(a -> a.id() != null && a.content() != null)
                .map(a -> new SourceAttachment(a.id(), a.filename(), a.size(), a.content(), instant(a.created())))
                .toList();
        String milestone = f.fixVersions() == null || f.fixVersions().isEmpty() ? null
                : f.fixVersions().getFirst().name();
        SourceUser author = user(f.reporter() != null ? f.reporter() : f.creator());
        return new SourceIssue(id, number(key), api.web + "/browse/" + key, author, f.summary(),
                markdown(f.description(), files), new ArrayList<>(labels), milestone,
                f.assignee() == null ? List.of() : List.of(user(f.assignee())), closed,
                closed ? closeReason(f.resolution() == null ? null : f.resolution().name()) : null,
                instant(f.created()), instant(f.updated()), instant(f.resolutiondate()), date(f.duedate()),
                f.comment() == null ? -1 : f.comment().total(), files);
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        List<SourceEvent> events = new ArrayList<>();
        for (History h : histories(issue)) {
            events(h, events);
        }
        for (RemoteLink link : optional(() -> api.list("/issue/" + issue.externalId() + "/remotelink", Map.of(),
                RemoteLink.class))) {
            if (link.object() == null || link.object().url() == null || !link.object().url().matches("(?i)https?://.+")) {
                continue;
            }
            String url = link.object().url();
            boolean pullRequest = url.matches("(?i).*/(pull|pulls|merge_requests|pull-requests)/\\d+.*");
            events.add(new SourceEvent(pullRequest ? Kind.PULL_REQUEST : Kind.WEB_LINK, null, issue.createdAt(),
                    link.object().title(), url, null, null));
        }
        // Each link is listed by both issues; the outward side adds it.
        List<SourceLink> links = new ArrayList<>();
        for (IssueLink link : linksByIssue.getOrDefault(issue.externalId(), List.of())) {
            if (link.outwardIssue() != null && link.outwardIssue().key() != null && link.type() != null
                    && number(link.outwardIssue().key()) >= 0) {
                links.add(new SourceLink(link.type().name(), ref(link.outwardIssue().key())));
            }
        }
        List<SourceRef> subIssues = children.getOrDefault(reference(project.key(), issue.number()), List.of());
        return new SourceDetails(comments(issue), events, List.of(), subIssues, links);
    }

    @Override
    public List<SourceComment> comments(SourceIssue issue) {
        List<SourceComment> comments = new ArrayList<>();
        int startAt = 0;
        for (int page = 0; page < 10_000; page++) {
            CommentPage result = api.get("/issue/" + issue.externalId() + "/comment", Map.of("startAt",
                    String.valueOf(startAt), "maxResults", String.valueOf(JiraConnector.PAGE_SIZE), "orderBy",
                    "created"), CommentPage.class);
            List<Comment> found = result.comments() == null ? List.of() : result.comments();
            for (Comment c : found) {
                boolean restricted = c.visibility() != null && !c.visibility().isNull()
                        || Boolean.FALSE.equals(c.jsdPublic());
                if (!restricted && c.id() != null) {
                    comments.add(new SourceComment(c.id(), user(c.author()), markdown(c.body(), issue.attachments()),
                            instant(c.created()), instant(c.updated()), List.of()));
                }
            }
            startAt += found.size();
            if (found.isEmpty() || startAt >= result.total()) {
                break;
            }
        }
        return comments;
    }

    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        try {
            return api.http.download(URI.create(attachment.url()), maxBytes);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid address");
        }
    }

    @Override
    public SourceUser profile(SourceUser user) {
        return user;
    }

    // --- history -------------------------------------------------------------------------

    private List<History> histories(SourceIssue issue) {
        if (!api.cloud()) {
            // Server and Data Center give the whole changelog with the issue.
            IssueWithChangelog withChangelog = api.get("/issue/" + issue.externalId(), Map.of("expand", "changelog",
                    "fields", "summary"), IssueWithChangelog.class);
            return withChangelog.changelog() == null || withChangelog.changelog().histories() == null ? List.of()
                    : withChangelog.changelog().histories();
        }
        List<History> all = new ArrayList<>();
        int startAt = 0;
        for (int page = 0; page < 10_000; page++) {
            ChangelogPage result = api.get("/issue/" + issue.externalId() + "/changelog", Map.of("startAt",
                    String.valueOf(startAt), "maxResults", String.valueOf(JiraConnector.PAGE_SIZE)),
                    ChangelogPage.class);
            List<History> found = result.values() == null ? List.of() : result.values();
            all.addAll(found);
            startAt += found.size();
            if (found.isEmpty() || Boolean.TRUE.equals(result.isLast()) || startAt >= result.total()) {
                break;
            }
        }
        return all;
    }

    private void events(History h, List<SourceEvent> events) {
        SourceUser actor = user(h.author());
        Instant at = instant(h.created());
        if (at == null || h.items() == null) {
            return;
        }
        String resolution = h.items().stream().filter(i -> "resolution".equalsIgnoreCase(i.field()))
                .map(ChangeItem::toText).filter(r -> r != null && !r.isBlank()).findFirst().orElse(null);
        boolean statusChanged = h.items().stream().anyMatch(i -> "status".equalsIgnoreCase(i.field()));
        for (ChangeItem item : h.items()) {
            String field = item.field() == null ? "" : item.field().toLowerCase(Locale.ROOT);
            switch (field) {
                case "summary" -> events.add(new SourceEvent(Kind.RENAMED, actor, at, item.fromString(),
                        item.toText(), null, null));
                case "labels" -> {
                    Set<String> before = words(item.fromString());
                    Set<String> after = words(item.toText());
                    before.stream().filter(l -> !after.contains(l)).forEach(l -> events.add(label(false, l, actor, at)));
                    after.stream().filter(l -> !before.contains(l)).forEach(l -> events.add(label(true, l, actor, at)));
                }
                case "issuetype" -> renamedLabel(TYPE_PREFIX, item, actor, at, events);
                case "priority" -> renamedLabel(PRIORITY_PREFIX, item, actor, at, events);
                case "component" -> renamedLabel(COMPONENT_PREFIX, item, actor, at, events);
                case "assignee" -> {
                    if (item.from() != null && !item.from().isBlank()) {
                        events.add(new SourceEvent(Kind.UNASSIGNED, actor, at, null, null,
                                user(item.from(), item.fromString()), null));
                    }
                    if (item.to() != null && !item.to().isBlank()) {
                        events.add(new SourceEvent(Kind.ASSIGNED, actor, at, null, null,
                                user(item.to(), item.toText()), null));
                    }
                }
                case "fix version" -> events.add(new SourceEvent(Kind.MILESTONE, actor, at, item.fromString(),
                        item.toText(), null, null));
                case "duedate" -> events.add(new SourceEvent(Kind.DUE_DATE, actor, at, dateText(item.from()),
                        dateText(item.to()), null, null));
                case "status" -> {
                    Boolean from = item.from() == null ? null : statusDone(item.from());
                    Boolean to = item.to() == null ? null : statusDone(item.to());
                    if (Boolean.TRUE.equals(to) && !Boolean.TRUE.equals(from)) {
                        events.add(new SourceEvent(Kind.CLOSED, actor, at, null, null, null,
                                resolution == null ? null : closeReason(resolution)));
                    } else if (Boolean.TRUE.equals(from) && Boolean.FALSE.equals(to)) {
                        events.add(new SourceEvent(Kind.REOPENED, actor, at, null, null, null, null));
                    }
                }
                case "resolution" -> {
                    // Without status categories: a resolution that is set or cleared closes or reopens.
                    if (!statusChanged || done().isEmpty()) {
                        boolean set = item.toText() != null && !item.toText().isBlank();
                        events.add(set ? new SourceEvent(Kind.CLOSED, actor, at, null, null, null,
                                closeReason(item.toText())) : new SourceEvent(Kind.REOPENED, actor, at, null, null,
                                null, null));
                    }
                }
                default -> {
                    // descriptions, attachments, links, custom fields: not part of the imported history
                }
            }
        }
    }

    private static SourceEvent label(boolean added, String name, SourceUser actor, Instant at) {
        return new SourceEvent(added ? Kind.LABEL_ADDED : Kind.LABEL_REMOVED, actor, at, null, name, null, null);
    }

    /** A type, priority or component that changed, as the label it is in the mapping. */
    private static void renamedLabel(String prefix, ChangeItem item, SourceUser actor, Instant at,
            List<SourceEvent> events) {
        if (item.fromString() != null && !item.fromString().isBlank()) {
            events.add(label(false, prefix + item.fromString(), actor, at));
        }
        if (item.toText() != null && !item.toText().isBlank()) {
            events.add(label(true, prefix + item.toText(), actor, at));
        }
    }

    private static Set<String> words(String value) {
        return value == null || value.isBlank() ? new LinkedHashSet<>()
                : new LinkedHashSet<>(Arrays.asList(value.strip().split("\\s+")));
    }

    /** Whether a status is in the category "done"; null when unknown. */
    private Boolean statusDone(String statusId) {
        return done().get(statusId);
    }

    private boolean isDone(Status status) {
        if (status.statusCategory() != null && status.statusCategory().key() != null) {
            return "done".equalsIgnoreCase(status.statusCategory().key());
        }
        return Boolean.TRUE.equals(done().get(status.id()));
    }

    private Map<String, Boolean> done() {
        if (done == null) {
            done = new HashMap<>();
            for (Status s : optional(() -> api.list("/status", Map.of(), Status.class))) {
                if (s.id() != null && s.statusCategory() != null) {
                    done.put(s.id(), "done".equalsIgnoreCase(s.statusCategory().key()));
                }
            }
        }
        return done;
    }

    /** Server: the custom field "Epic Link" that holds the key of an issue's epic; null if none. */
    private String epicLinkField() {
        if (epicLinkField == null) {
            epicLinkField = optional(() -> api.list("/field", Map.of(), Field.class)).stream()
                    .filter(f -> f.schema() != null && EPIC_LINK.equals(f.schema().custom())).map(Field::id)
                    .findFirst().orElse("");
        }
        return epicLinkField.isEmpty() ? null : epicLinkField;
    }

    static CloseReason closeReason(String resolution) {
        return Source.closeReason(resolution);
    }

    // --- texts and users -----------------------------------------------------------------

    private String markdown(String wiki, List<SourceAttachment> files) {
        return JiraMarkup.toMarkdown(wiki, new JiraMarkup.Context() {
            @Override
            public String attachment(String fileName) {
                // The newest file of that name, as Jira shows it.
                SourceAttachment found = null;
                for (SourceAttachment a : files) {
                    if (fileName.equals(a.name()) && (found == null || a.createdAt() != null
                            && found.createdAt() != null && a.createdAt().isAfter(found.createdAt()))) {
                        found = a;
                    }
                }
                return found == null ? null : "/secure/attachment/" + found.key() + "/"
                        + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            }

            @Override
            public String mention(String user) {
                SourceUser known = user(user, null);
                return known == null || known.name() == null ? user : known.name();
            }
        });
    }

    private SourceRef ref(String key) {
        int dash = key.lastIndexOf('-');
        String projectKey = key.substring(0, dash);
        return new SourceRef(projectKey.equals(project.key()) ? null : projectKey, number(key));
    }

    /** ABC-12 → 12; -1 when it is no key. */
    static long number(String key) {
        int dash = key == null ? -1 : key.lastIndexOf('-');
        if (dash <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(key.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private SourceUser user(User u) {
        if (u == null) {
            return null;
        }
        String id = u.accountId() != null ? u.accountId() : u.key() != null ? u.key() : u.name();
        if (id == null) {
            return null;
        }
        SourceUser known = users.get(id);
        if (known != null && (known.email() != null || u.emailAddress() == null)) {
            return known;
        }
        String email = blankToNull(u.emailAddress());
        String login;
        if (u.name() != null && !u.name().isBlank()) {
            login = u.name(); // Server: the username
        } else if (email != null) {
            login = email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@'));
        } else {
            // Cloud without a visible e-mail address: john.doe for John Doe.
            String name = u.displayName() == null ? id : u.displayName();
            login = name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", ".");
        }
        SourceUser user = new SourceUser(id, login, blankToNull(u.displayName()), email);
        users.put(id, user);
        if (u.name() != null) {
            users.putIfAbsent(u.name(), user);
        }
        if (u.key() != null) {
            users.putIfAbsent(u.key(), user);
        }
        return user;
    }

    /**
     * A person by the id in a changelog or mention: the account id (Cloud), or the key or
     * username (Server); looked up once when not seen before.
     */
    private SourceUser user(String idOrName, String shown) {
        SourceUser known = users.get(idOrName);
        if (known != null) {
            return known;
        }
        List<Map<String, String>> lookups = api.cloud() ? List.of(Map.of("accountId", idOrName))
                : List.of(Map.of("key", idOrName), Map.of("username", idOrName));
        for (Map<String, String> lookup : lookups) {
            try {
                SourceUser found = user(api.get("/user", lookup, User.class));
                if (found != null) {
                    users.put(idOrName, found);
                    return found;
                }
            } catch (ApiException e) {
                // try the next way, or fall back below
            }
        }
        if (shown == null) {
            return null;
        }
        SourceUser unknown = new SourceUser(idOrName, api.cloud() ? shown.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", ".") : idOrName, shown, null);
        users.put(idOrName, unknown);
        return unknown;
    }

    /** Lists a project may not show (e.g. remote links turned off): empty instead of failing. */
    private static <T> List<T> optional(Supplier<List<T>> list) {
        try {
            return list.get();
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND || e.status() == HttpStatus.BAD_REQUEST) {
                return List.of();
            }
            throw e;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
    }

    /** 2026-01-05T09:00:00.000+0000 (Jira) or with a colon in the offset. */
    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, JIRA_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }

    private static LocalDate date(String value) {
        String text = dateText(value);
        return text == null ? null : LocalDate.parse(text);
    }

    /** The date part of a due date in a changelog or field ("2026-01-05" or "2026-01-05 00:00:00.0"). */
    private static String dateText(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        String date = value.substring(0, 10);
        try {
            return LocalDate.parse(date).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
