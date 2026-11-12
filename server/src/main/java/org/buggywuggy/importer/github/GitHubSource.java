/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.github;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.buggywuggy.importer.github.GitHubApi.Issue;
import org.buggywuggy.importer.github.GitHubApi.Label;
import org.buggywuggy.importer.github.GitHubApi.Milestone;
import org.buggywuggy.importer.github.GitHubApi.Repository;
import org.buggywuggy.importer.github.GitHubApi.TimelineEvent;
import org.buggywuggy.importer.github.GitHubApi.User;
import org.springframework.http.HttpStatus;

/**
 * One GitHub repository as a source of issues. Comments and events come from the issue's
 * timeline; attachments are links in the texts (GitHub has no attachment list), which are
 * downloaded without the token, from wherever GitHub redirects them to.
 */
final class GitHubSource implements Source {

    /** GitHub's prefix for issue types in the label mapping. */
    static final String TYPE_PREFIX = "type:";

    private static final Pattern REPOSITORY_URL = Pattern.compile("/repos/([\\w.\\-]+/[\\w.\\-]+)$");
    private static final String FILE_CHARS = "[\\w.\\-/%~+]+";

    private final GitHubConnector.Api api;
    private final String path;
    private final Repository repository;
    private final Pattern attachments;
    private final Map<String, SourceUser> profiles = new HashMap<>();
    /** Per issue number: sub-issues and blocking issues, by the summaries in the list; absent when unknown. */
    private final Map<Long, int[]> related = new HashMap<>();

    GitHubSource(GitHubConnector.Api api, String path, Repository repository) {
        this.api = api;
        this.path = path;
        this.repository = repository;
        String web = Pattern.quote(api.web.toString());
        String links = web + "/user-attachments/(?:assets|files)/" + FILE_CHARS
                + "|" + web + "/[\\w.\\-]+/[\\w.\\-]+/(?:assets|files)/" + FILE_CHARS;
        if (api.isGitHubCom()) {
            links += "|https://user-images\\.githubusercontent\\.com/" + FILE_CHARS;
        }
        this.attachments = Pattern.compile("(" + links + ")");
    }

    static RepositoryInfo info(Repository r) {
        return new RepositoryInfo(r.fullName(), r.description() == null || r.description().isBlank() ? null
                : r.description(), r.isPrivate(), r.hasIssues(), r.openIssuesCount(), r.htmlUrl());
    }

    @Override
    public String kind() {
        return GitHubConnector.KIND;
    }

    @Override
    public String displayName() {
        return "GitHub";
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
    public Pattern attachmentLinks() {
        return attachments;
    }

    @Override
    public void watch(BooleanSupplier cancelled, ReadOnlyHttp.Waits waits) {
        api.http.watch(cancelled, waits);
    }

    @Override
    public RepositoryInfo info() {
        return info(repository);
    }

    @Override
    public List<SourceLabel> labels() {
        return api.all("/repos/" + path + "/labels", Map.of(), Label.class).stream()
                .map(l -> new SourceLabel(l.name(), l.color(), l.description())).toList();
    }

    @Override
    public List<SourceMilestone> milestones() {
        return api.all("/repos/" + path + "/milestones", Map.of("state", "all"), Milestone.class).stream()
                .map(m -> new SourceMilestone(m.title(), m.description(),
                        m.dueOn() == null ? null : m.dueOn().toLocalDate(), "closed".equals(m.state())))
                .toList();
    }

    @Override
    public List<SourceIssue> issues() {
        return api.all("/repos/" + path + "/issues", Map.of("state", "all", "direction", "asc"), Issue.class)
                .stream().filter(i -> !i.isPullRequest()).map(this::issue).toList();
    }

    private SourceIssue issue(Issue i) {
        List<String> labels = new ArrayList<>();
        if (i.labels() != null) {
            i.labels().stream().map(Label::name).forEach(labels::add);
        }
        if (i.type() != null && i.type().name() != null) {
            labels.add(TYPE_PREFIX + i.type().name());
        }
        if (i.subIssuesSummary() != null && i.issueDependenciesSummary() != null) {
            related.put(i.number(), new int[] {i.subIssuesSummary().total(),
                    Math.max(i.issueDependenciesSummary().blockedBy(), i.issueDependenciesSummary().totalBlockedBy())});
        }
        boolean closed = "closed".equals(i.state());
        return new SourceIssue(String.valueOf(i.id()), i.number(), i.htmlUrl(), user(i.user()), i.title(), i.body(),
                labels, i.milestone() == null ? null : i.milestone().title(),
                i.assignees() == null ? List.of() : i.assignees().stream().map(GitHubSource::user).toList(),
                closed, closed ? reason(i.stateReason()) : null, instant(i.createdAt()), instant(i.updatedAt()),
                instant(i.closedAt()), null, i.comments(), links(i.body(), instant(i.createdAt())));
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        List<TimelineEvent> timeline = timeline(issue);
        List<SourceEvent> events = new ArrayList<>();
        for (TimelineEvent e : timeline) {
            SourceEvent event = event(e);
            if (event != null) {
                events.add(event);
            }
        }
        int[] counts = related.get(issue.number());
        List<SourceDependency> blockedBy = counts != null && counts[1] == 0 ? List.of()
                : related(issue, "/dependencies/blocked_by").stream()
                        .map(ref -> new SourceDependency(ref, null, null)).toList();
        List<SourceRef> children = counts != null && counts[0] == 0 ? List.of() : related(issue, "/sub_issues");
        return new SourceDetails(comments(timeline), events, blockedBy, children);
    }

    @Override
    public List<SourceComment> comments(SourceIssue issue) {
        return comments(timeline(issue));
    }

    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        return api.http.download(URI.create(attachment.url()), maxBytes);
    }

    @Override
    public SourceUser profile(SourceUser user) {
        if (user == null || user.login() == null) {
            return user;
        }
        return profiles.computeIfAbsent(user.externalId(), id -> {
            try {
                User full = api.get("/users/" + URLEncoder.encode(user.login(), StandardCharsets.UTF_8), User.class);
                return new SourceUser(id, user.login(), blankToNull(full.name()), blankToNull(full.email()));
            } catch (ApiException e) {
                return user; // e.g. a deleted account or a bot
            }
        });
    }

    // --- details ---------------------------------------------------------------------------

    private List<TimelineEvent> timeline(SourceIssue issue) {
        return api.all("/repos/" + path + "/issues/" + issue.number() + "/timeline", Map.of(), TimelineEvent.class)
                .stream().filter(e -> e.event() != null && e.createdAt() != null)
                .sorted(Comparator.comparing(TimelineEvent::createdAt)).toList();
    }

    private List<SourceComment> comments(List<TimelineEvent> timeline) {
        return timeline.stream().filter(e -> "commented".equals(e.event()))
                .map(e -> new SourceComment(String.valueOf(e.id()), user(e.user() != null ? e.user() : e.actor()),
                        e.body(), instant(e.createdAt()), instant(e.updatedAt()),
                        links(e.body(), instant(e.createdAt()))))
                .toList();
    }

    /** Sub-issues or blocking issues; empty when the server does not know them (older Enterprise). */
    private List<SourceRef> related(SourceIssue issue, String endpoint) {
        try {
            return api.all("/repos/" + path + "/issues/" + issue.number() + endpoint, Map.of(), Issue.class).stream()
                    .map(i -> new SourceRef(repositoryOf(i), i.number())).toList();
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) {
                return List.of();
            }
            throw e;
        }
    }

    /** {@code owner/name} of another repository, or null for this one. */
    private String repositoryOf(Issue i) {
        if (i.repositoryUrl() == null) {
            return null;
        }
        Matcher m = REPOSITORY_URL.matcher(i.repositoryUrl());
        return m.find() && !m.group(1).equalsIgnoreCase(path) ? m.group(1) : null;
    }

    private SourceEvent event(TimelineEvent e) {
        SourceUser actor = user(e.actor());
        Instant at = instant(e.createdAt());
        return switch (e.event()) {
            case "labeled", "unlabeled" -> e.label() == null ? null : new SourceEvent(
                    e.event().equals("labeled") ? Kind.LABEL_ADDED : Kind.LABEL_REMOVED, actor, at, null,
                    e.label().name(), null, null);
            case "assigned", "unassigned" -> e.assignee() == null ? null : new SourceEvent(
                    e.event().equals("assigned") ? Kind.ASSIGNED : Kind.UNASSIGNED, actor, at, null, null,
                    user(e.assignee()), null);
            // An issue has one milestone: changing it is "demilestoned" plus "milestoned".
            case "milestoned" -> e.milestone() == null ? null
                    : new SourceEvent(Kind.MILESTONE, actor, at, null, e.milestone().title(), null, null);
            case "demilestoned" -> e.milestone() == null ? null
                    : new SourceEvent(Kind.MILESTONE, actor, at, e.milestone().title(), null, null, null);
            case "renamed" -> e.rename() == null ? null
                    : new SourceEvent(Kind.RENAMED, actor, at, e.rename().from(), e.rename().to(), null, null);
            case "closed" -> new SourceEvent(Kind.CLOSED, actor, at, null, null, null, reason(e.stateReason()));
            case "reopened" -> new SourceEvent(Kind.REOPENED, actor, at, null, null, null, null);
            case "cross-referenced" -> {
                Issue ref = e.source() == null ? null : e.source().issue();
                if (ref == null || !ref.isPullRequest() || ref.htmlUrl() == null) {
                    yield null;
                }
                String repo = repositoryOf(ref);
                yield new SourceEvent(Kind.PULL_REQUEST, actor, at,
                        (repo == null ? path : repo) + "#" + ref.number() + " " + ref.title(), ref.htmlUrl(), null,
                        null);
            }
            default -> null;
        };
    }

    /** The attachments a text links to. */
    private List<SourceAttachment> links(String text, Instant at) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher m = attachments.matcher(text);
        while (m.find()) {
            urls.add(m.group(1));
        }
        return urls.stream().map(url -> new SourceAttachment(url, fileName(url), -1, url, at)).toList();
    }

    /** {@code …/files/<id>/<name>} carries the name; {@code …/assets/<uuid>} gets it when downloaded. */
    private static String fileName(String url) {
        int files = url.indexOf("/files/");
        if (files < 0) {
            return null;
        }
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.isBlank() ? null : java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
    }

    private static CloseReason reason(String stateReason) {
        if (stateReason == null) {
            return null;
        }
        return switch (stateReason.toLowerCase(Locale.ROOT)) {
            case "not_planned" -> CloseReason.NOT_PLANNED;
            case "duplicate" -> CloseReason.DUPLICATE;
            case "completed" -> CloseReason.COMPLETED;
            default -> null;
        };
    }

    static SourceUser user(User u) {
        return u == null || u.login() == null ? null
                : new SourceUser(String.valueOf(u.id()), u.login(), blankToNull(u.name()), blankToNull(u.email()));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
