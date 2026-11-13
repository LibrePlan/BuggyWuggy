/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.redmine;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.buggywuggy.importer.redmine.RedmineApi.Attachment;
import org.buggywuggy.importer.redmine.RedmineApi.Categories;
import org.buggywuggy.importer.redmine.RedmineApi.Detail;
import org.buggywuggy.importer.redmine.RedmineApi.Issue;
import org.buggywuggy.importer.redmine.RedmineApi.IssuePage;
import org.buggywuggy.importer.redmine.RedmineApi.IssueWrapper;
import org.buggywuggy.importer.redmine.RedmineApi.Journal;
import org.buggywuggy.importer.redmine.RedmineApi.Named;
import org.buggywuggy.importer.redmine.RedmineApi.Priorities;
import org.buggywuggy.importer.redmine.RedmineApi.Project;
import org.buggywuggy.importer.redmine.RedmineApi.ProjectWrapper;
import org.buggywuggy.importer.redmine.RedmineApi.Relation;
import org.buggywuggy.importer.redmine.RedmineApi.Status;
import org.buggywuggy.importer.redmine.RedmineApi.Statuses;
import org.buggywuggy.importer.redmine.RedmineApi.Trackers;
import org.buggywuggy.importer.redmine.RedmineApi.User;
import org.buggywuggy.importer.redmine.RedmineApi.UserWrapper;
import org.buggywuggy.importer.redmine.RedmineApi.Version;
import org.buggywuggy.importer.redmine.RedmineApi.VersionWrapper;
import org.buggywuggy.importer.redmine.RedmineApi.Versions;
import org.springframework.http.HttpStatus;

/**
 * One Redmine project (without its subprojects) as a source of issues. The tracker, priority
 * and category become labels the administrator maps ({@code type:Bug}, {@code priority/High},
 * {@code component/Backend}); the target version becomes the version. Private notes are left
 * out, private issues get the label {@code restricted}. The history comes from the journals;
 * subtasks are the sub-issues, and relations become dependencies (blocks, precedes) or links.
 */
final class RedmineSource implements Source {

    static final String RESTRICTED = "restricted";
    static final String TYPE_PREFIX = "type:";
    static final String PRIORITY_PREFIX = "priority/";
    static final String COMPONENT_PREFIX = "component/";

    private final RedmineConnector.Api api;
    private final Project project;
    private final Pattern attachments;
    private final Map<Long, SourceUser> users = new HashMap<>();
    /** Names of people as issues and journals show them, for ids in journals. */
    private final Map<Long, String> names = new HashMap<>();
    private final Map<Long, List<SourceRef>> children = new HashMap<>();
    /** The relations of each issue, from the list: SourceIssue has no place for them. */
    private final Map<Long, List<Relation>> relations = new HashMap<>();
    private final Set<Long> ids = new HashSet<>();
    /** The project of issues elsewhere that relations point to. */
    private final Map<Long, String> otherProjects = new HashMap<>();
    private final Map<Long, String> projectIdentifiers = new HashMap<>();
    private Map<Long, Status> statuses;
    private Map<Long, String> trackers;
    private Map<Long, String> priorities;
    private Map<Long, String> categories;
    private Map<Long, String> versions;
    private boolean textile;

    RedmineSource(RedmineConnector.Api api, Project project) {
        this.api = api;
        this.project = project;
        // Absolute, or relative with the path Redmine is under (/redmine/attachments/…), or without it.
        String under = api.web.getRawPath() == null || api.web.getRawPath().isEmpty() ? ""
                : "|" + Pattern.quote(api.web.getRawPath());
        this.attachments = Pattern.compile("(?:" + Pattern.quote(api.web.toString()) + under + ")?/attachments/"
                + "(?:download/|thumbnail/)?(\\d+)(?:/[^\\s)\"'<>\\]]*)?");
    }

    static RepositoryInfo info(RedmineConnector.Api api, Project p) {
        String name = p.name() == null || p.name().isBlank() ? p.identifier() : p.name();
        return new RepositoryInfo(p.identifier(), name, Boolean.FALSE.equals(p.isPublic()), true, -1,
                api.web + "/projects/" + p.identifier());
    }

    @Override
    public String kind() {
        return RedmineConnector.KIND;
    }

    @Override
    public String displayName() {
        return "Redmine";
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
        return project.identifier();
    }

    @Override
    public String host() {
        return api.web.getHost();
    }

    /** Issue numbers are the same in all projects of a Redmine: /issues/12 has no project. */
    @Override
    public Pattern issueLinks() {
        return Pattern.compile("()" + Pattern.quote(api.web.toString()) + "/issues/(\\d+)/?");
    }

    @Override
    public String issueUrl(String repository, long number) {
        return api.web + "/issues/" + number;
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
        return categories().values().stream().map(c -> new SourceLabel(COMPONENT_PREFIX + c, "", null)).toList();
    }

    @Override
    public List<SourceMilestone> milestones() {
        // Includes versions shared by other projects, which issues of this one may use.
        return versionList().stream().filter(v -> v.name() != null)
                .map(v -> new SourceMilestone(v.name(), v.description(), date(v.dueDate()),
                        "closed".equals(v.status())))
                .toList();
    }

    @Override
    public List<SourceIssue> issues() {
        List<Issue> found = api.all("/issues.json", Map.of("project_id", project.identifier(),
                "subproject_id", "!*", "status_id", "*", "sort", "id", "include", "attachments,relations"),
                IssuePage.class, IssuePage::issues, IssuePage::totalCount);
        // Only this project: older versions ignore subproject_id.
        List<Issue> own = found.stream().filter(i -> i.id() != null
                && (i.project() == null || i.project().id() == null || i.project().id().equals(project.id())))
                .toList();
        textile = RedmineText.looksLikeTextile(own.stream().map(Issue::description).toList());
        ids.clear();
        children.clear();
        relations.clear();
        own.forEach(i -> ids.add(i.id()));
        List<SourceIssue> issues = new ArrayList<>();
        for (Issue i : own) {
            if (i.parent() != null && i.parent().id() != null && ids.contains(i.parent().id())) {
                children.computeIfAbsent(i.parent().id(), p -> new ArrayList<>()).add(new SourceRef(null, i.id()));
            }
            if (i.relations() != null && !i.relations().isEmpty()) {
                relations.put(i.id(), i.relations());
            }
            issues.add(issue(i));
        }
        return issues;
    }

    private SourceIssue issue(Issue i) {
        Set<String> labels = new LinkedHashSet<>();
        if (i.tracker() != null && i.tracker().name() != null) {
            labels.add(TYPE_PREFIX + i.tracker().name());
        }
        if (i.priority() != null && i.priority().name() != null) {
            labels.add(PRIORITY_PREFIX + i.priority().name());
        }
        if (i.category() != null && i.category().name() != null) {
            labels.add(COMPONENT_PREFIX + i.category().name());
        }
        if (Boolean.TRUE.equals(i.isPrivate())) {
            labels.add(RESTRICTED);
        }
        boolean closed = isClosed(i.status());
        List<SourceAttachment> files = i.attachments() == null ? List.of() : i.attachments().stream()
                .filter(a -> a.id() != null && a.filename() != null).map(this::attachment).toList();
        CloseReason reason = null;
        if (closed) {
            boolean duplicate = i.relations() != null && i.relations().stream()
                    .anyMatch(r -> "duplicates".equals(r.relationType()) && i.id().equals(r.issueId()));
            reason = duplicate ? CloseReason.DUPLICATE : Source.closeReason(statusName(i.status()));
        }
        return new SourceIssue(String.valueOf(i.id()), i.id(), api.web + "/issues/" + i.id(), user(i.author()),
                i.subject(), markdown(i.description(), files), new ArrayList<>(labels),
                i.fixedVersion() == null ? null : i.fixedVersion().name(),
                i.assignedTo() == null ? List.of() : List.of(user(i.assignedTo())), closed, reason,
                instant(i.createdOn()), instant(i.updatedOn()), closed ? instant(i.closedOn()) : null,
                date(i.dueDate()), -1, files);
    }

    /** From the address of Redmine itself: content_url may name another host (Setting.host_name). */
    private SourceAttachment attachment(Attachment a) {
        String url = api.web + "/attachments/download/" + a.id() + "/"
                + URLEncoder.encode(a.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return new SourceAttachment(String.valueOf(a.id()), a.filename(), a.filesize() == null ? -1 : a.filesize(),
                url, instant(a.createdOn()));
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        Issue full = api.get("/issues/" + issue.externalId() + ".json", Map.of("include", "journals"),
                IssueWrapper.class).issue();
        List<Journal> journals = full == null || full.journals() == null ? List.of() : full.journals();
        List<SourceComment> comments = new ArrayList<>();
        List<SourceEvent> events = new ArrayList<>();
        for (Journal j : journals) {
            if (j.id() == null) {
                continue;
            }
            if (j.notes() != null && !j.notes().isBlank() && !Boolean.TRUE.equals(j.privateNotes())) {
                comments.add(new SourceComment(String.valueOf(j.id()), user(j.user()),
                        markdown(j.notes(), issue.attachments()), instant(j.createdOn()),
                        j.updatedOn() == null ? instant(j.createdOn()) : instant(j.updatedOn()), List.of()));
            }
            events(j, events);
        }

        long me = issue.number();
        List<SourceDependency> blockedBy = new ArrayList<>();
        List<SourceLink> links = new ArrayList<>();
        // Each relation is listed with both issues; it is added from one side only.
        for (Relation r : relations.getOrDefault(me, List.of())) {
            if (r.issueId() == null || r.issueToId() == null || r.relationType() == null) {
                continue;
            }
            boolean from = r.issueId() == me;
            long other = from ? r.issueToId() : r.issueId();
            switch (r.relationType()) {
                case "blocks", "precedes" -> {
                    if (!from) {
                        blockedBy.add(new SourceDependency(ref(other), null, null));
                    }
                }
                case "blocked", "follows" -> {
                    if (from) {
                        blockedBy.add(new SourceDependency(ref(other), null, null));
                    }
                }
                case "duplicates" -> {
                    if (from) {
                        links.add(new SourceLink("Duplicate", ref(other)));
                    }
                }
                case "duplicated" -> {
                    if (!from) {
                        links.add(new SourceLink("Duplicate", ref(other)));
                    }
                }
                // copied_to: the issue it points to is the copy, which clones this one.
                case "copied_to" -> {
                    if (!from) {
                        links.add(new SourceLink("Cloners", ref(other)));
                    }
                }
                case "copied_from" -> {
                    if (from) {
                        links.add(new SourceLink("Cloners", ref(other)));
                    }
                }
                default -> {
                    if (from) {
                        links.add(new SourceLink("Relates", ref(other)));
                    }
                }
            }
        }
        return new SourceDetails(comments, events, blockedBy, children.getOrDefault(me, List.of()), links);
    }

    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        try {
            return api.http.download(URI.create(attachment.url()), maxBytes);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid address");
        }
    }

    // --- history -------------------------------------------------------------------------

    private void events(Journal j, List<SourceEvent> events) {
        SourceUser actor = user(j.user());
        Instant at = instant(j.createdOn());
        if (at == null || j.details() == null) {
            return;
        }
        for (Detail d : j.details()) {
            if (!"attr".equals(d.property()) || d.name() == null) {
                continue; // custom fields, attachments, relations: not part of the imported history
            }
            switch (d.name()) {
                case "subject" -> events.add(new SourceEvent(Kind.RENAMED, actor, at, d.oldValue(), d.newValue(),
                        null, null));
                case "tracker_id" -> renamedLabel(TYPE_PREFIX, trackers(), d, actor, at, events);
                case "priority_id" -> renamedLabel(PRIORITY_PREFIX, priorities(), d, actor, at, events);
                case "category_id" -> renamedLabel(COMPONENT_PREFIX, categories(), d, actor, at, events);
                case "assigned_to_id" -> {
                    SourceUser before = userById(id(d.oldValue()));
                    SourceUser after = userById(id(d.newValue()));
                    if (before != null) {
                        events.add(new SourceEvent(Kind.UNASSIGNED, actor, at, null, null, before, null));
                    }
                    if (after != null) {
                        events.add(new SourceEvent(Kind.ASSIGNED, actor, at, null, null, after, null));
                    }
                }
                case "fixed_version_id" -> events.add(new SourceEvent(Kind.MILESTONE, actor, at,
                        version(id(d.oldValue())), version(id(d.newValue())), null, null));
                case "due_date" -> events.add(new SourceEvent(Kind.DUE_DATE, actor, at, dateText(d.oldValue()),
                        dateText(d.newValue()), null, null));
                case "status_id" -> {
                    Status before = statuses().get(id(d.oldValue()));
                    Status after = statuses().get(id(d.newValue()));
                    boolean wasClosed = before != null && Boolean.TRUE.equals(before.isClosed());
                    boolean isClosed = after != null && Boolean.TRUE.equals(after.isClosed());
                    if (isClosed && !wasClosed) {
                        events.add(new SourceEvent(Kind.CLOSED, actor, at, null, null, null,
                                Source.closeReason(after.name())));
                    } else if (wasClosed && after != null && !isClosed) {
                        events.add(new SourceEvent(Kind.REOPENED, actor, at, null, null, null, null));
                    }
                }
                default -> {
                    // description, start date, done ratio, estimated time, privacy, parent
                }
            }
        }
    }

    private static void renamedLabel(String prefix, Map<Long, String> names, Detail d, SourceUser actor,
            Instant at, List<SourceEvent> events) {
        String before = names.get(id(d.oldValue()));
        String after = names.get(id(d.newValue()));
        if (before != null) {
            events.add(new SourceEvent(Kind.LABEL_REMOVED, actor, at, null, prefix + before, null, null));
        }
        if (after != null) {
            events.add(new SourceEvent(Kind.LABEL_ADDED, actor, at, null, prefix + after, null, null));
        }
    }

    private boolean isClosed(Status status) {
        if (status == null || status.id() == null) {
            return false;
        }
        if (status.isClosed() != null) {
            return status.isClosed();
        }
        Status known = statuses().get(status.id());
        return known != null && Boolean.TRUE.equals(known.isClosed());
    }

    private String statusName(Status status) {
        if (status.name() != null) {
            return status.name();
        }
        Status known = statuses().get(status.id());
        return known == null ? null : known.name();
    }

    private Map<Long, Status> statuses() {
        if (statuses == null) {
            statuses = new HashMap<>();
            Statuses list = optional(() -> api.get("/issue_statuses.json", Map.of(), Statuses.class), null);
            if (list != null && list.issueStatuses() != null) {
                list.issueStatuses().stream().filter(s -> s.id() != null).forEach(s -> statuses.put(s.id(), s));
            }
        }
        return statuses;
    }

    private Map<Long, String> trackers() {
        if (trackers == null) {
            Trackers list = optional(() -> api.get("/trackers.json", Map.of(), Trackers.class), null);
            trackers = byId(list == null ? null : list.trackers());
        }
        return trackers;
    }

    private Map<Long, String> priorities() {
        if (priorities == null) {
            Priorities list = optional(() -> api.get("/enumerations/issue_priorities.json", Map.of(),
                    Priorities.class), null);
            priorities = byId(list == null ? null : list.issuePriorities());
        }
        return priorities;
    }

    private Map<Long, String> categories() {
        if (categories == null) {
            Categories list = optional(() -> api.get("/projects/" + project.identifier() + "/issue_categories.json",
                    Map.of(), Categories.class), null);
            categories = byId(list == null ? null : list.issueCategories());
        }
        return categories;
    }

    private List<Version> versionList() {
        Versions list = optional(() -> api.get("/projects/" + project.identifier() + "/versions.json", Map.of(),
                Versions.class), null);
        List<Version> found = list == null || list.versions() == null ? List.of() : list.versions();
        if (versions == null) {
            versions = new HashMap<>();
        }
        found.stream().filter(v -> v.id() != null && v.name() != null).forEach(v -> versions.put(v.id(), v.name()));
        return found;
    }

    /** The name of a version by id; a version of another project is looked up once. */
    private String version(Long id) {
        if (id == null) {
            return null;
        }
        if (versions == null) {
            versionList();
        }
        if (!versions.containsKey(id)) {
            VersionWrapper found = optional(() -> api.get("/versions/" + id + ".json", Map.of(),
                    VersionWrapper.class), null);
            versions.put(id, found == null || found.version() == null ? null : found.version().name());
        }
        return versions.get(id);
    }

    private static Map<Long, String> byId(List<Named> list) {
        Map<Long, String> map = new HashMap<>();
        if (list != null) {
            list.stream().filter(n -> n.id() != null && n.name() != null).forEach(n -> map.put(n.id(), n.name()));
        }
        return map;
    }

    /** An issue a relation points to: in this project, or in the project it is in. */
    private SourceRef ref(long id) {
        if (ids.contains(id)) {
            return new SourceRef(null, id);
        }
        String other = otherProjects.computeIfAbsent(id, i -> {
            IssueWrapper issue = optional(() -> api.get("/issues/" + i + ".json", Map.of(), IssueWrapper.class),
                    null);
            Long projectId = issue == null || issue.issue() == null || issue.issue().project() == null ? null
                    : issue.issue().project().id();
            if (projectId == null) {
                return "";
            }
            return projectIdentifiers.computeIfAbsent(projectId, p -> {
                ProjectWrapper found = optional(() -> api.get("/projects/" + p + ".json", Map.of(),
                        ProjectWrapper.class), null);
                return found == null || found.project() == null || found.project().identifier() == null ? ""
                        : found.project().identifier();
            });
        });
        return new SourceRef(other.isEmpty() || other.equals(project.identifier()) ? null : other, id);
    }

    // --- texts and users -----------------------------------------------------------------

    private String markdown(String text, List<SourceAttachment> files) {
        return RedmineText.toMarkdown(text, textile, new RedmineText.Context() {
            @Override
            public String attachment(String fileName) {
                // The newest file of that name, as Redmine shows it.
                SourceAttachment found = null;
                for (SourceAttachment a : files) {
                    if (fileName.equalsIgnoreCase(a.name()) && (found == null || a.createdAt() != null
                            && found.createdAt() != null && a.createdAt().isAfter(found.createdAt()))) {
                        found = a;
                    }
                }
                return found == null ? null : "/attachments/download/" + found.key() + "/"
                        + URLEncoder.encode(found.name(), StandardCharsets.UTF_8).replace("+", "%20");
            }

            @Override
            public String wiki(String otherProject, String page) {
                String title = page.strip().replace(' ', '_');
                int anchor = title.indexOf('#');
                String fragment = anchor < 0 ? "" : title.substring(anchor);
                String name = anchor < 0 ? title : title.substring(0, anchor);
                return api.web + "/projects/" + (otherProject == null ? project.identifier() : otherProject)
                        + "/wiki/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20") + fragment;
            }
        });
    }

    private SourceUser user(Named n) {
        if (n == null || n.id() == null) {
            return null;
        }
        if (n.name() != null) {
            names.putIfAbsent(n.id(), n.name());
        }
        return userById(n.id());
    }

    /**
     * A person by id. Only administrators see logins and e-mail addresses; for others the
     * login is made from the name (Ann Example → ann.example). Groups (as assignee) have no
     * account page and get one the same way.
     */
    private SourceUser userById(Long id) {
        if (id == null) {
            return null;
        }
        SourceUser known = users.get(id);
        if (known != null) {
            return known;
        }
        UserWrapper found = optional(() -> api.get("/users/" + id + ".json", Map.of(), UserWrapper.class), null);
        User u = found == null ? null : found.user();
        String name = u == null ? names.get(id)
                : blankToNull((nullToEmpty(u.firstname()) + " " + nullToEmpty(u.lastname())).strip());
        if (name == null) {
            name = names.get(id);
        }
        String login = u != null && u.login() != null && !u.login().isBlank() ? u.login()
                : name == null ? "user" + id : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", ".");
        SourceUser user = new SourceUser(String.valueOf(id), login, name, u == null ? null : blankToNull(u.mail()));
        users.put(id, user);
        return user;
    }

    /** What a project or user may not show (403, 404): the fallback instead of failing. */
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

    private static Long id(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
