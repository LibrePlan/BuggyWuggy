/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.gitlab;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.Source.SourceEvent.Kind;
import org.buggywuggy.importer.gitlab.GitLabApi.Issue;
import org.buggywuggy.importer.gitlab.GitLabApi.Label;
import org.buggywuggy.importer.gitlab.GitLabApi.LabelEvent;
import org.buggywuggy.importer.gitlab.GitLabApi.LinkedIssue;
import org.buggywuggy.importer.gitlab.GitLabApi.MergeRequest;
import org.buggywuggy.importer.gitlab.GitLabApi.Milestone;
import org.buggywuggy.importer.gitlab.GitLabApi.MilestoneEvent;
import org.buggywuggy.importer.gitlab.GitLabApi.Note;
import org.buggywuggy.importer.gitlab.GitLabApi.Project;
import org.buggywuggy.importer.gitlab.GitLabApi.StateEvent;
import org.buggywuggy.importer.gitlab.GitLabApi.User;
import org.springframework.http.HttpStatus;

/**
 * One GitLab project as a source of issues. Comments are the notes that are not system notes;
 * internal notes are left out, as BuggyWuggy has no internal comments. The history comes from
 * the resource events (labels, milestone, state) and, for the title, assignees and due date,
 * from the system notes. Attachments are the uploads the texts link to.
 */
final class GitLabSource implements Source {

    /** Label for confidential issues, so they can be found after the import. */
    static final String CONFIDENTIAL = "confidential";
    /** GitLab's issue types (incident, test case, task) appear in the label mapping as type:Name. */
    static final String TYPE_PREFIX = "type:";

    private final GitLabConnector.Api api;
    private final String path;
    private final Project project;
    private final String projectWeb;
    private final Pattern attachments;
    private final Map<String, SourceUser> byUsername = new HashMap<>();
    private final Map<String, SourceUser> profiles = new HashMap<>();

    GitLabSource(GitLabConnector.Api api, String path, Project project) {
        this.api = api;
        this.path = path;
        this.project = project;
        this.projectWeb = api.web + "/" + path;
        // /uploads/<secret>/<file>, relative to the project, or absolute, or /-/project/<id>/uploads/…
        this.attachments = Pattern.compile("(?:" + Pattern.quote(projectWeb) + "|(?:" + Pattern.quote(
                api.web.toString()) + ")?/-/project/\\d+)?/uploads/([0-9a-fA-F]{32}/[^\\s)\"'<>\\]]+)",
                Pattern.CASE_INSENSITIVE);
    }

    static RepositoryInfo info(Project p) {
        boolean issues = !"disabled".equals(p.issuesAccessLevel()) && !Boolean.FALSE.equals(p.issuesEnabled());
        return new RepositoryInfo(p.pathWithNamespace(), p.description() == null || p.description().isBlank()
                ? null : p.description(), !"public".equals(p.visibility()), issues, p.openIssuesCount(),
                p.webUrl());
    }

    @Override
    public String kind() {
        return GitLabConnector.KIND;
    }

    @Override
    public String displayName() {
        return "GitLab";
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
        return path;
    }

    @Override
    public String host() {
        return api.web.getHost();
    }

    @Override
    public String issueUrl(String repository, long number) {
        return api.web + "/" + repository + "/-/issues/" + number;
    }

    @Override
    public Pattern attachmentLinks() {
        return attachments;
    }

    @Override
    public String attachmentUrl(String found) {
        if (found.startsWith("/uploads/")) {
            return projectWeb + found; // relative to the project
        }
        return found.startsWith("/") ? api.web + found : found;
    }

    @Override
    public void watch(BooleanSupplier cancelled, ReadOnlyHttp.Waits waits) {
        api.http.watch(cancelled, waits);
    }

    @Override
    public RepositoryInfo info() {
        return info(project);
    }

    private String base() {
        return "/projects/" + project.id();
    }

    @Override
    public List<SourceLabel> labels() {
        return api.all(base() + "/labels", Map.of("include_ancestor_groups", "true"), Label.class).stream()
                .map(l -> new SourceLabel(l.name(), l.color() == null ? "" : l.color().replace("#", ""),
                        l.description()))
                .toList();
    }

    @Override
    public List<SourceMilestone> milestones() {
        return api.all(base() + "/milestones", Map.of(), Milestone.class).stream()
                .map(m -> new SourceMilestone(m.title(), m.description(), m.dueDate(), "closed".equals(m.state())))
                .toList();
    }

    @Override
    public List<SourceIssue> issues() {
        return api.all(base() + "/issues", Map.of("scope", "all", "state", "all", "order_by", "created_at",
                "sort", "asc"), Issue.class).stream().map(this::issue).toList();
    }

    private SourceIssue issue(Issue i) {
        List<String> labels = new ArrayList<>(i.labels() == null ? List.of() : i.labels());
        if (i.issueType() != null && !i.issueType().isBlank() && !i.issueType().equalsIgnoreCase("issue")) {
            labels.add(TYPE_PREFIX + typeName(i.issueType()));
        }
        if (i.confidential()) {
            labels.add(CONFIDENTIAL);
        }
        boolean closed = "closed".equals(i.state());
        return new SourceIssue(String.valueOf(i.id()), i.iid(), i.webUrl(), user(i.author()), i.title(),
                i.description(), labels, i.milestone() == null ? null : i.milestone().title(),
                i.assignees() == null ? List.of() : i.assignees().stream().map(this::user).toList(), closed,
                closed ? CloseReason.COMPLETED : null, instant(i.createdAt()), instant(i.updatedAt()),
                instant(i.closedAt()), i.dueDate(), i.userNotesCount(), links(i.description(),
                        instant(i.createdAt())));
    }

    /** test_case → Test case */
    private static String typeName(String issueType) {
        String name = issueType.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        String at = base() + "/issues/" + issue.number();
        List<Note> notes = notes(issue);
        List<SourceEvent> events = new ArrayList<>();
        systemNotes(notes, events);
        for (LabelEvent e : api.all(at + "/resource_label_events", Map.of(), LabelEvent.class)) {
            if (e.label() != null && e.label().name() != null) {
                events.add(new SourceEvent("remove".equals(e.action()) ? Kind.LABEL_REMOVED : Kind.LABEL_ADDED,
                        user(e.user()), instant(e.createdAt()), null, e.label().name(), null, null));
            }
        }
        for (MilestoneEvent e : api.all(at + "/resource_milestone_events", Map.of(), MilestoneEvent.class)) {
            if (e.milestone() != null && e.milestone().title() != null) {
                boolean removed = "remove".equals(e.action());
                events.add(new SourceEvent(Kind.MILESTONE, user(e.user()), instant(e.createdAt()),
                        removed ? e.milestone().title() : null, removed ? null : e.milestone().title(), null, null));
            }
        }
        for (StateEvent e : api.all(at + "/resource_state_events", Map.of(), StateEvent.class)) {
            Kind kind = "closed".equals(e.state()) ? Kind.CLOSED : "reopened".equals(e.state()) ? Kind.REOPENED : null;
            if (kind != null) {
                events.add(new SourceEvent(kind, user(e.user()), instant(e.createdAt()), null, null, null, null));
            }
        }
        for (MergeRequest mr : optional(() -> api.all(at + "/related_merge_requests", Map.of(),
                MergeRequest.class))) {
            if (mr.webUrl() != null) {
                String ref = mr.references() != null && mr.references().full() != null ? mr.references().full()
                        : "!" + mr.iid();
                events.add(new SourceEvent(Kind.PULL_REQUEST, user(mr.author()), instant(mr.createdAt()),
                        ref + " " + mr.title(), mr.webUrl(), null, null));
            }
        }
        List<SourceDependency> blockedBy = new ArrayList<>();
        List<SourceLink> related = new ArrayList<>();
        for (LinkedIssue link : optional(() -> api.all(at + "/links", Map.of(), LinkedIssue.class))) {
            SourceRef ref = ref(link);
            if (ref == null) {
                continue;
            }
            if ("is_blocked_by".equals(link.linkType())) {
                blockedBy.add(new SourceDependency(ref, null, instant(link.linkCreatedAt())));
            } else if ("relates_to".equals(link.linkType())) {
                related.add(new SourceLink("Relates", ref));
            }
            // "blocks": recorded as "is_blocked_by" on the other issue.
        }
        return new SourceDetails(comments(notes), events, blockedBy, List.of(), related);
    }

    @Override
    public List<SourceComment> comments(SourceIssue issue) {
        return comments(notes(issue));
    }

    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        // The API serves uploads with the token (GitLab 17.4 and later); older servers only on the web.
        String[] parts = attachment.key().split("/", 2);
        try {
            return api.http.download(URI.create(api.api + base() + "/uploads/" + parts[0] + "/" + parts[1]),
                    maxBytes);
        } catch (ReadOnlyHttp.TooLargeException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            try {
                return api.http.download(URI.create(projectWeb + "/uploads/" + attachment.key()), maxBytes);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("invalid file name");
            }
        }
    }

    @Override
    public SourceUser profile(SourceUser user) {
        if (user == null || !api.http.hasToken()) {
            return user;
        }
        return profiles.computeIfAbsent(user.externalId(), id -> {
            try {
                User full = api.get("/users/" + id, User.class);
                return new SourceUser(id, user.login(), blankToNull(full.name()) == null ? user.name() : full.name(),
                        blankToNull(full.publicEmail()));
            } catch (ApiException e) {
                return user;
            }
        });
    }

    // --- details ---------------------------------------------------------------------------

    private List<Note> notes(SourceIssue issue) {
        return api.all(base() + "/issues/" + issue.number() + "/notes", Map.of("sort", "asc",
                "order_by", "created_at"), Note.class).stream().filter(n -> n.createdAt() != null).toList();
    }

    private List<SourceComment> comments(List<Note> notes) {
        return notes.stream().filter(n -> !n.system() && !n.internal() && !n.confidential())
                .map(n -> new SourceComment(String.valueOf(n.id()), user(n.author()), n.body(),
                        instant(n.createdAt()), instant(n.updatedAt()), links(n.body(), instant(n.createdAt()))))
                .toList();
    }

    /** Title, assignees and due date, from the system notes (in time order). */
    private void systemNotes(List<Note> notes, List<SourceEvent> events) {
        String due = null;
        for (Note n : notes) {
            if (!n.system() || n.body() == null) {
                continue;
            }
            SourceUser actor = user(n.author());
            Instant at = instant(n.createdAt());
            GitLabNotes.title(n.body()).ifPresent(t -> events.add(new SourceEvent(Kind.RENAMED, actor, at, t.from(),
                    t.to(), null, null)));
            GitLabNotes.assignment(n.body()).ifPresent(a -> {
                for (String username : a.unassigned()) {
                    events.add(new SourceEvent(Kind.UNASSIGNED, actor, at, null, null, byUsername(username), null));
                }
                for (String username : a.assigned()) {
                    events.add(new SourceEvent(Kind.ASSIGNED, actor, at, null, null, byUsername(username), null));
                }
            });
            Optional<Optional<LocalDate>> date = GitLabNotes.dueDate(n.body());
            if (date.isPresent()) {
                String value = date.get().map(LocalDate::toString).orElse(null);
                events.add(new SourceEvent(Kind.DUE_DATE, actor, at, due, value, null, null));
                due = value;
            }
        }
    }

    /** A person a system note names; looked up once when not seen before. */
    private SourceUser byUsername(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        SourceUser known = byUsername.get(key);
        if (known != null) {
            return known;
        }
        try {
            List<User> found = ReadOnlyHttp.list(api.http.get(api.uri("/users", Map.of("username", username))),
                    User.class);
            if (!found.isEmpty()) {
                return user(found.getFirst());
            }
        } catch (ApiException e) {
            // unknown: handled below
        }
        return null;
    }

    private SourceRef ref(LinkedIssue link) {
        if (link.projectId() == project.id()) {
            return new SourceRef(null, link.iid());
        }
        if (link.references() == null || link.references().full() == null) {
            return null;
        }
        String full = link.references().full();
        int hash = full.lastIndexOf('#');
        return hash <= 0 ? null : new SourceRef(full.substring(0, hash), link.iid());
    }

    /** Lists a project may not show (e.g. merge requests turned off): empty instead of failing. */
    private static <T> List<T> optional(java.util.function.Supplier<List<T>> list) {
        try {
            return list.get();
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND || e.status() == HttpStatus.BAD_REQUEST) {
                return List.of();
            }
            throw e;
        }
    }

    /** The uploads a text links to; the key is {@code <secret>/<file name>}. */
    private List<SourceAttachment> links(String text, Instant at) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = attachments.matcher(text);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys.stream().map(key -> new SourceAttachment(key, java.net.URLDecoder.decode(
                key.substring(key.indexOf('/') + 1), StandardCharsets.UTF_8), -1, attachmentUrl("/uploads/" + key),
                at)).toList();
    }

    private SourceUser user(User u) {
        if (u == null || u.username() == null) {
            return null;
        }
        SourceUser user = new SourceUser(String.valueOf(u.id()), u.username(), blankToNull(u.name()),
                blankToNull(u.publicEmail()));
        byUsername.putIfAbsent(u.username().toLowerCase(Locale.ROOT), user);
        return user;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
