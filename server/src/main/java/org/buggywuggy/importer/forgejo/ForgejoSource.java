/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.Source.SourceEvent.Kind;
import org.buggywuggy.importer.forgejo.ForgejoApi.Asset;
import org.buggywuggy.importer.forgejo.ForgejoApi.Comment;
import org.buggywuggy.importer.forgejo.ForgejoApi.Issue;
import org.buggywuggy.importer.forgejo.ForgejoApi.Label;
import org.buggywuggy.importer.forgejo.ForgejoApi.Milestone;
import org.buggywuggy.importer.forgejo.ForgejoApi.Repository;
import org.buggywuggy.importer.forgejo.ForgejoApi.TimelineEvent;
import org.buggywuggy.importer.forgejo.ForgejoApi.User;

/** One Forgejo (or Gitea) repository as a source of issues. */
final class ForgejoSource implements Source {

    private static final Pattern NO_REPLY = Pattern.compile("@noreply\\.", Pattern.CASE_INSENSITIVE);

    private final ForgejoConnector.Api api;
    private final String path;
    private final Repository repository;
    private final Pattern attachments;

    ForgejoSource(ForgejoConnector.Api api, String path, Repository repository) {
        this.api = api;
        this.path = path;
        this.repository = repository;
        this.attachments = Pattern.compile("(?:" + Pattern.quote(api.base.toString())
                + ")?/attachments/([0-9a-fA-F-]{36})\\b");
    }

    static RepositoryInfo info(Repository r) {
        boolean external = r.externalTracker() != null && !r.externalTracker().isNull();
        return new RepositoryInfo(r.fullName(), r.description() == null || r.description().isBlank() ? null
                : r.description(), r.isPrivate(), r.hasIssues() && !external, r.openIssuesCount(), r.htmlUrl());
    }

    @Override
    public String kind() {
        return ForgejoConnector.KIND;
    }

    @Override
    public String displayName() {
        return "Forgejo";
    }

    @Override
    public String baseUrl() {
        return api.base.toString();
    }

    @Override
    public String webUrl() {
        return api.base.toString();
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String host() {
        return api.base.getHost();
    }

    @Override
    public Pattern attachmentLinks() {
        return attachments;
    }

    @Override
    public String attachmentKey(String found) {
        return found.toLowerCase(Locale.ROOT);
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
                        m.dueOn() == null || m.dueOn().getYear() < 1900 ? null : m.dueOn().toLocalDate(),
                        "closed".equals(m.state())))
                .toList();
    }

    @Override
    public List<SourceIssue> issues() {
        return api.all("/repos/" + path + "/issues", Map.of("state", "all", "type", "issues"), Issue.class).stream()
                .filter(i -> !i.isPullRequest()).map(this::issue).toList();
    }

    private SourceIssue issue(Issue i) {
        boolean closed = "closed".equals(i.state());
        return new SourceIssue(String.valueOf(i.id()), i.number(), i.htmlUrl(), author(i.user(), i.originalAuthor()),
                i.title(), i.body(), i.labels() == null ? List.of() : i.labels().stream().map(Label::name).toList(),
                i.milestone() == null || i.milestone().title() == null ? null : i.milestone().title(),
                i.assignees() == null ? List.of() : i.assignees().stream().map(ForgejoSource::user).toList(),
                closed, closed ? CloseReason.COMPLETED : null, instant(i.createdAt()), instant(i.updatedAt()),
                instant(i.closedAt()), i.dueDate() == null ? null : i.dueDate().toLocalDate(), i.comments(),
                attachments(i.assets()));
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        String base = "/repos/" + path + "/issues/" + issue.number();
        List<TimelineEvent> timeline = api.all(base + "/timeline", Map.of(), TimelineEvent.class).stream()
                .filter(e -> e.createdAt() != null && e.type() != null)
                .sorted(Comparator.comparing(TimelineEvent::createdAt).thenComparingLong(TimelineEvent::id))
                .toList();
        List<SourceEvent> events = new ArrayList<>();
        for (TimelineEvent e : timeline) {
            SourceEvent event = event(e);
            if (event != null) {
                events.add(event);
            }
        }
        List<SourceDependency> blockedBy = new ArrayList<>();
        for (Issue blocker : api.list(base + "/dependencies", Issue.class)) {
            String repo = blocker.repository() == null ? null : blocker.repository().fullName();
            TimelineEvent added = timeline.stream().filter(e -> "add_dependency".equals(e.type())
                    && e.dependentIssue() != null && e.dependentIssue().id() == blocker.id()).findFirst().orElse(null);
            blockedBy.add(new SourceDependency(new SourceRef(repo == null || repo.equalsIgnoreCase(path) ? null : repo,
                    blocker.number()), added == null ? null : user(added.user()),
                    added == null ? null : instant(added.createdAt())));
        }
        return new SourceDetails(comments(issue), events, blockedBy, List.of(), List.of());
    }

    @Override
    public List<SourceComment> comments(SourceIssue issue) {
        if (issue.commentCount() == 0) {
            return List.of();
        }
        return api.list("/repos/" + path + "/issues/" + issue.number() + "/comments", Comment.class).stream()
                .map(c -> new SourceComment(String.valueOf(c.id()), author(c.user(), c.originalAuthor()), c.body(),
                        instant(c.createdAt()), instant(c.updatedAt()), attachments(c.assets())))
                .toList();
    }

    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        return api.http.download(api.base.resolve(attachment.url()), maxBytes);
    }

    // --- conversion ------------------------------------------------------------------------

    private SourceEvent event(TimelineEvent e) {
        SourceUser actor = user(e.user());
        Instant at = instant(e.createdAt());
        return switch (e.type()) {
            case "label" -> e.label() == null ? null : new SourceEvent("1".equals(e.body()) ? Kind.LABEL_ADDED
                    : Kind.LABEL_REMOVED, actor, at, null, e.label().name(), null, null);
            case "assignees" -> e.assignee() == null ? null : new SourceEvent(e.removedAssignee() ? Kind.UNASSIGNED
                    : Kind.ASSIGNED, actor, at, null, null, user(e.assignee()), null);
            case "milestone" -> new SourceEvent(Kind.MILESTONE, actor, at, title(e.oldMilestone()),
                    title(e.milestone()), null, null);
            case "change_title" -> new SourceEvent(Kind.RENAMED, actor, at, e.oldTitle(), e.newTitle(), null, null);
            case "close" -> new SourceEvent(Kind.CLOSED, actor, at, null, null, null, null);
            case "reopen" -> new SourceEvent(Kind.REOPENED, actor, at, null, null, null, null);
            case "added_deadline" -> new SourceEvent(Kind.DUE_DATE, actor, at, null, e.body(), null, null);
            case "removed_deadline" -> new SourceEvent(Kind.DUE_DATE, actor, at, e.body(), null, null, null);
            case "modified_deadline" -> {
                // body is "new|old"
                String[] parts = e.body() == null ? new String[0] : e.body().split("\\|");
                yield new SourceEvent(Kind.DUE_DATE, actor, at, parts.length > 1 ? parts[1] : null,
                        parts.length > 0 ? parts[0] : null, null, null);
            }
            case "pull_ref", "comment_ref" -> {
                Issue ref = e.refIssue();
                if (ref == null || !ref.isPullRequest() || ref.htmlUrl() == null) {
                    yield null;
                }
                String repo = ref.repository() == null ? "" : ref.repository().fullName();
                yield new SourceEvent(Kind.PULL_REQUEST, actor, at, repo + "#" + ref.number() + " " + ref.title(),
                        ref.htmlUrl(), null, null);
            }
            default -> null;
        };
    }

    private static String title(Milestone m) {
        return m == null || m.title() == null || m.title().isEmpty() ? null : m.title();
    }

    private static List<SourceAttachment> attachments(List<Asset> assets) {
        if (assets == null) {
            return List.of();
        }
        return assets.stream().filter(a -> a.uuid() != null)
                .map(a -> new SourceAttachment(a.uuid(), a.name(), a.size(), a.browserDownloadUrl(),
                        instant(a.createdAt())))
                .toList();
    }

    static SourceUser user(User u) {
        if (u == null) {
            return null;
        }
        String email = u.email() == null || u.email().isBlank() || NO_REPLY.matcher(u.email()).find() ? null
                : u.email();
        return new SourceUser(String.valueOf(u.id()), u.login(), u.fullName(), email);
    }

    /** The author of something that may have been migrated into Forgejo from elsewhere. */
    private static SourceUser author(User user, String originalAuthor) {
        if (originalAuthor != null && !originalAuthor.isBlank()) {
            String login = originalAuthor.strip();
            return new SourceUser(String.valueOf(-Math.abs((long) login.hashCode()) - 1_000_000L), login, login, null);
        }
        return user(user);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
