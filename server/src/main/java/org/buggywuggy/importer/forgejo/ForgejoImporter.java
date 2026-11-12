/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportJobs;
import org.buggywuggy.importer.ImportRunner.CancelledException;
import org.buggywuggy.importer.ImportRunner.Context;
import org.buggywuggy.importer.ImportWriter;
import org.buggywuggy.importer.ImportWriter.CreatedIssue;
import org.buggywuggy.importer.ImportWriter.Named;
import org.buggywuggy.importer.ImportWriter.NewIssue;
import org.buggywuggy.importer.ImportWriter.Ref;
import org.buggywuggy.importer.ImportWriter.ResolvedUser;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.ImportWriter.Statuses;
import org.buggywuggy.importer.forgejo.ForgejoApi.Asset;
import org.buggywuggy.importer.forgejo.ForgejoApi.Comment;
import org.buggywuggy.importer.forgejo.ForgejoApi.Issue;
import org.buggywuggy.importer.forgejo.ForgejoApi.Label;
import org.buggywuggy.importer.forgejo.ForgejoApi.Milestone;
import org.buggywuggy.importer.forgejo.ForgejoApi.Repository;
import org.buggywuggy.importer.forgejo.ForgejoApi.TimelineEvent;
import org.buggywuggy.importer.forgejo.ForgejoApi.User;
import org.buggywuggy.importer.forgejo.ForgejoDtos.Analysis;
import org.buggywuggy.importer.forgejo.ForgejoDtos.LabelInfo;
import org.buggywuggy.importer.forgejo.ForgejoDtos.LabelRule;
import org.buggywuggy.importer.forgejo.ForgejoDtos.LabelTarget;
import org.buggywuggy.importer.forgejo.ForgejoDtos.Options;
import org.buggywuggy.importer.forgejo.ForgejoDtos.RepositoryInfo;
import org.buggywuggy.importer.forgejo.ForgejoDtos.UserInfo;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * Imports the issues of one Forgejo repository into a project.
 *
 * <p>Round one creates each issue in its own transaction: fields, comments, attachments,
 * watchers, the original history and the pull requests that referenced it. Round two, once
 * all issues exist, rewrites references in the texts and adds the dependencies. Issues that
 * an earlier run imported only get their new comments.
 */
@Component
public class ForgejoImporter {

    public static final String KIND = "FORGEJO";

    private static final Pattern NO_REPLY = Pattern.compile("@noreply\\.", Pattern.CASE_INSENSITIVE);
    private static final int MAX_FAILURES_IN_A_ROW = 10;

    private final ImportWriter writer;
    private final ImportJobs jobs;
    private final TransactionTemplate tx;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final long maxFileSize;

    public ForgejoImporter(ImportWriter writer, ImportJobs jobs, TransactionTemplate tx, NamedParameterJdbcTemplate jdbc,
            Clock clock, @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize) {
        this.writer = writer;
        this.jobs = jobs;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxFileSize = maxFileSize.toBytes();
    }

    static String path(String repository) {
        String path = repository == null ? "" : repository.strip();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.matches("[\\w.\\-]+/[\\w.\\-]+")) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return path;
    }

    static RepositoryInfo info(Repository r) {
        return new RepositoryInfo(r.fullName(), r.description() == null || r.description().isBlank() ? null
                : r.description(), r.isPrivate(), r.hasIssues() && (r.externalTracker() == null || r.externalTracker().isNull()), r.openIssuesCount(),
                r.htmlUrl());
    }

    /** The repository, if its issues can be imported. */
    static Repository repository(ForgejoClient client, String path) {
        Repository repo = client.get("/repos/" + path, Repository.class);
        if (!info(repo).hasIssues()) {
            throw ApiException.badRequest("import.issues-disabled", path);
        }
        return repo;
    }

    static List<Issue> issues(ForgejoClient client, String path) {
        return client.all("/repos/" + path + "/issues", Map.of("state", "all", "type", "issues"), Issue.class)
                .stream().filter(i -> !i.isPullRequest())
                .sorted(Comparator.comparingLong(Issue::number)).toList();
    }

    // --- analysis ------------------------------------------------------------------------

    public Analysis analyze(ForgejoClient client, String repository) {
        String path = path(repository);
        Repository repo = repository(client, path);
        List<Issue> issues = issues(client, path);
        List<Label> labels = client.all("/repos/" + path + "/labels", Map.of(), Label.class);
        List<Milestone> milestones = client.all("/repos/" + path + "/milestones", Map.of("state", "all"),
                Milestone.class);

        Map<String, Integer> labelUse = new HashMap<>();
        Map<String, User> users = new LinkedHashMap<>();
        Map<String, Integer> userUse = new HashMap<>();
        int comments = 0;
        int open = 0;
        for (Issue issue : issues) {
            comments += issue.comments();
            if (!"closed".equals(issue.state())) {
                open++;
            }
            if (issue.labels() != null) {
                issue.labels().forEach(l -> labelUse.merge(l.name(), 1, Integer::sum));
            }
            List<User> involved = new ArrayList<>();
            if (issue.user() != null) {
                involved.add(issue.user());
            }
            if (issue.assignees() != null) {
                involved.addAll(issue.assignees());
            }
            for (User u : involved) {
                String id = String.valueOf(u.id());
                users.putIfAbsent(id, u);
                userUse.merge(id, 1, Integer::sum);
            }
        }
        Long sourceId = jdbc.queryForList("""
                select id from import_source where kind = :kind and lower(base_url) = lower(:url)
                  and lower(project_path) = lower(:path)""",
                Map.of("kind", KIND, "url", client.baseUrl(), "path", path), Long.class).stream().findFirst()
                .orElse(null);
        int already = sourceId == null ? 0 : jdbc.queryForObject("""
                select count(*) from external_ref r join issue i on i.id = r.local_id
                where r.import_source_id = :source and r.source_type = 'issue'""",
                Map.of("source", sourceId), Integer.class);

        Map<Long, String> types = writer.names("issue_type");
        Map<Long, String> priorities = writer.names("priority");
        List<LabelInfo> labelInfos = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Label l : labels) {
            seen.add(l.name());
            labelInfos.add(new LabelInfo(l.name(), l.color(), blankToNull(l.description()),
                    labelUse.getOrDefault(l.name(), 0), suggest(l.name(), types, priorities)));
        }
        // Labels of the organisation are not listed for the repository, but can be on its issues.
        labelUse.keySet().stream().filter(n -> !seen.contains(n)).sorted().forEach(n -> labelInfos.add(
                new LabelInfo(n, "", null, labelUse.get(n), suggest(n, types, priorities))));

        List<UserInfo> userInfos = users.entrySet().stream().map(e -> {
            User u = e.getValue();
            UserRef suggestion = writer.suggest(sourceUser(u), true).map(this::userRef).orElse(null);
            Optional<Long> earlier = sourceId == null ? Optional.empty()
                    : writer.localId(sourceId, Ref.USER, e.getKey());
            if (earlier.isPresent()) {
                suggestion = userRef(earlier.get());
            }
            return new UserInfo(e.getKey(), u.login(), blankToNull(u.fullName()), userUse.get(e.getKey()), suggestion);
        }).sorted(Comparator.comparing(UserInfo::issues).reversed().thenComparing(UserInfo::login)).toList();

        return new Analysis(info(repo), issues.size(), open, issues.size() - open, comments, milestones.size(), already,
                labelInfos, userInfos);
    }

    private UserRef userRef(long id) {
        return jdbc.queryForObject("select id, username, display_name from user_account where id = :id",
                Map.of("id", id), (rs, n) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    /** Suggests a mapping from common label names: kind/bug, bug, priority/high, … */
    static LabelRule suggest(String label, Map<Long, String> types, Map<Long, String> priorities) {
        String name = label.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        String last = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1).strip() : name.strip();
        boolean priorityScope = name.startsWith("priority") || name.startsWith("prio");
        if (priorityScope || name.endsWith(" priority")) {
            String level = priorityScope ? last : last.replace(" priority", "").strip();
            Map<String, String> aliases = Map.of("critical", "highest", "urgent", "highest", "blocker", "highest",
                    "major", "high", "normal", "medium", "minor", "low", "trivial", "lowest");
            String wanted = aliases.getOrDefault(level, level);
            for (Map.Entry<Long, String> p : priorities.entrySet()) {
                if (p.getValue().equalsIgnoreCase(wanted)) {
                    return new LabelRule(LabelTarget.PRIORITY, p.getKey());
                }
            }
        }
        Map<String, String> typeAliases = Map.of("bug", "bug", "defect", "bug", "kind/bug", "bug", "feature", "story",
                "enhancement", "story", "task", "task", "epic", "epic");
        String type = typeAliases.get(last);
        if (type != null && (!name.contains("/") || name.startsWith("kind") || name.startsWith("type"))) {
            for (Map.Entry<Long, String> t : types.entrySet()) {
                if (t.getValue().equalsIgnoreCase(type) && !t.getValue().equalsIgnoreCase("epic")) {
                    return new LabelRule(LabelTarget.ISSUE_TYPE, t.getKey());
                }
            }
        }
        return new LabelRule(LabelTarget.LABEL, null);
    }

    // --- import --------------------------------------------------------------------------

    /** What round two needs of an issue round one created or extended. */
    private record Done(long issueId, String key, Issue issue, boolean isNew, List<Long> commentIds,
            List<Dependency> dependencies) {
    }

    private record Dependency(String repository, long number, Long authorId, Instant at) {
    }

    /** State of one run. */
    private final class Run {
        final Context context;
        final ForgejoClient client;
        final long sourceId;
        final long projectId;
        final String projectKey;
        final String path;
        final String host;
        final Options options;
        final long startedBy;
        final Instant now;
        final Statuses statuses;
        final Named resolution;
        final Named defaultType;
        final Named defaultPriority;
        final Map<Long, String> typeNames;
        final Map<Long, String> priorityNames;
        final Map<String, Named> versions = new HashMap<>();
        final Map<Long, Named> users = new HashMap<>();
        int usersCreated;

        Run(Context context, ForgejoClient client, long sourceId, long projectId, Options options, long startedBy) {
            this.context = context;
            this.client = client;
            this.sourceId = sourceId;
            this.projectId = projectId;
            this.projectKey = writer.projectKey(projectId);
            this.path = path(options.repository());
            this.host = client.base().getHost();
            this.options = options;
            this.startedBy = startedBy;
            this.now = clock.instant();
            this.typeNames = writer.names("issue_type");
            this.priorityNames = writer.names("priority");
            this.defaultType = new Named(options.issueTypeId(), typeNames.get(options.issueTypeId()));
            this.statuses = writer.statuses(projectId, options.issueTypeId());
            this.resolution = writer.defaultResolution();
            this.defaultPriority = writer.defaultPriority().orElse(null);
        }

        LabelRule rule(String label) {
            LabelRule rule = options.labels() == null ? null : options.labels().get(label);
            return rule == null ? new LabelRule(LabelTarget.LABEL, null) : rule;
        }

        final ForgejoHistory.Mapping mapping = new ForgejoHistory.Mapping() {
            @Override
            public String label(String forgejoLabel) {
                return rule(forgejoLabel).target() == LabelTarget.LABEL ? ImportWriter.label(forgejoLabel) : null;
            }

            @Override
            public Named issueType(Set<String> labels) {
                return labels.stream().map(Run.this::rule)
                        .filter(r -> r.target() == LabelTarget.ISSUE_TYPE && typeNames.containsKey(r.id()))
                        .map(r -> new Named(r.id(), typeNames.get(r.id()))).findFirst().orElse(defaultType);
            }

            @Override
            public Named priority(Set<String> labels) {
                return labels.stream().map(Run.this::rule)
                        .filter(r -> r.target() == LabelTarget.PRIORITY && priorityNames.containsKey(r.id()))
                        .map(r -> new Named(r.id(), priorityNames.get(r.id()))).findFirst().orElse(defaultPriority);
            }

            @Override
            public Named user(User user) {
                return Run.this.user(user);
            }

            @Override
            public Named version(String milestone) {
                return versions.computeIfAbsent(milestone,
                        m -> new Named(writer.version(projectId, m, null, null, false), m));
            }

            @Override
            public Named status(boolean closed, boolean reopened) {
                return closed ? statuses.closed() : reopened ? statuses.reopened() : statuses.open();
            }

            @Override
            public Named resolution(boolean closed) {
                return closed ? resolution : null;
            }
        };

        Named user(User user) {
            if (user == null) {
                return null;
            }
            return users.computeIfAbsent(user.id(), id -> {
                Long chosen = options.users() == null ? null : options.users().get(String.valueOf(id));
                ResolvedUser resolved = writer.user(sourceId, sourceUser(user), chosen, options.matchUsernames(), host,
                        now);
                if (resolved.created()) {
                    usersCreated++;
                }
                return new Named(resolved.id(), writer.userName(resolved.id()));
            });
        }

        /** The author of something that may have been migrated into Forgejo from elsewhere. */
        Named author(User user, String originalAuthor) {
            if (originalAuthor != null && !originalAuthor.isBlank()) {
                String login = originalAuthor.strip();
                return user(new User(-Math.abs((long) login.hashCode()) - 1_000_000L, login, login, null, false));
            }
            return user(user);
        }

        Long id(Named named) {
            return named == null ? null : named.id();
        }
    }

    private static SourceUser sourceUser(User u) {
        String email = u.email() == null || u.email().isBlank() || NO_REPLY.matcher(u.email()).find() ? null
                : u.email();
        return new SourceUser(String.valueOf(u.id()), u.login(), u.fullName(), email);
    }

    public void run(Context context, ForgejoClient client, long sourceId, long projectId, Options options,
            long startedBy) {
        client.watch(context::cancelled, context::info);
        Run run = new Run(context, client, sourceId, projectId, options, startedBy);
        long jobId = context.jobId();
        context.info("Reading the issues of " + run.path + " on " + client.baseUrl() + ".");
        Repository repo = repository(client, run.path);
        List<Milestone> milestones = client.all("/repos/" + run.path + "/milestones", Map.of("state", "all"),
                Milestone.class);
        List<Issue> issues = issues(client, run.path);
        jobs.total(jobId, issues.size());
        context.info(issues.size() + " issues found in " + repo.fullName() + ".");

        tx.executeWithoutResult(status -> {
            for (Milestone m : milestones) {
                if (m.title() == null || m.title().isBlank()) {
                    continue;
                }
                long id = writer.version(projectId, m.title().strip(), m.description(),
                        m.dueOn() == null || m.dueOn().getYear() < 1900 ? null : m.dueOn().toLocalDate(),
                        "closed".equals(m.state()));
                run.versions.put(m.title().strip(), new Named(id, m.title().strip()));
            }
        });

        List<Done> done = new ArrayList<>();
        int failuresInARow = 0;
        int failed = 0;
        for (Issue issue : issues) {
            if (context.cancelled()) {
                throw new CancelledException();
            }
            int usersBefore = run.usersCreated;
            Map<Long, Named> knownUsers = new HashMap<>(run.users);
            Map<String, Named> knownVersions = new HashMap<>(run.versions);
            int[] counts = new int[3];
            try {
                Done result = tx.execute(status -> importIssue(run, issue, counts));
                if (result != null) {
                    done.add(result);
                }
                jobs.count(jobId, 1, result != null && result.isNew() ? 1 : 0, counts[0], counts[1],
                        run.usersCreated - usersBefore);
                failuresInARow = 0;
            } catch (CancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                // The transaction was rolled back: forget users and versions it created.
                run.users.clear();
                run.users.putAll(knownUsers);
                run.versions.clear();
                run.versions.putAll(knownVersions);
                run.usersCreated = usersBefore;
                jobs.count(jobId, 1, 0, 0, 0, 0);
                failed++;
                context.warn("Issue #" + issue.number() + " was not imported: " + context.describe(e));
                if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                    throw new IllegalStateException(MAX_FAILURES_IN_A_ROW + " issues in a row failed; stopped.", e);
                }
            }
        }

        context.info("Linking issues and updating references.");
        ForgejoText text = new ForgejoText(client.baseUrl(), run.path, resolver(run));
        for (Done d : done) {
            if (context.cancelled()) {
                throw new CancelledException();
            }
            try {
                tx.executeWithoutResult(status -> finish(run, text, d));
            } catch (RuntimeException e) {
                context.warn(d.key() + ": references and links could not be updated: " + context.describe(e));
            }
        }
        long imported = done.stream().filter(Done::isNew).count();
        context.info("Done: " + imported + " issues imported, " + (issues.size() - imported - failed)
                + " were imported earlier, " + failed + " failed.");
        if (failed > 0) {
            throw new IllegalStateException(failed + " issues could not be imported; see the warnings. Run the "
                    + "import again to retry them.");
        }
    }

    private ForgejoText.Resolver resolver(Run run) {
        return new ForgejoText.Resolver() {
            @Override
            public Optional<String> issueKey(String repository, long number) {
                return writer.issueBySource(KIND, run.client.baseUrl(), repository == null ? run.path : repository,
                        number).map(Named::name);
            }

            @Override
            public Optional<Long> attachmentId(String uuid) {
                return writer.localId(run.sourceId, Ref.ATTACHMENT, uuid);
            }
        };
    }

    /** Round one for one issue. {@code counts}: comments, attachments. */
    private Done importIssue(Run run, Issue issue, int[] counts) {
        String externalId = String.valueOf(issue.id());
        Optional<Long> existing = writer.localId(run.sourceId, Ref.ISSUE, externalId);
        if (existing.isPresent()) {
            return addNewComments(run, issue, existing.get(), counts);
        }
        String base = "/repos/" + run.path + "/issues/" + issue.number();
        List<TimelineEvent> timeline = run.client.all(base + "/timeline", Map.of(), TimelineEvent.class);
        ForgejoHistory.Result history = ForgejoHistory.of(issue, timeline, run.mapping);

        Set<String> labels = issue.labels() == null ? Set.of()
                : new LinkedHashSet<>(issue.labels().stream().map(Label::name).toList());
        boolean closed = "closed".equals(issue.state());
        Named reporter = run.author(issue.user(), issue.originalAuthor());
        Named assignee = issue.assignees() == null || issue.assignees().isEmpty() ? null
                : run.user(issue.assignees().getFirst());
        Named type = run.mapping.issueType(labels);
        Named priority = run.mapping.priority(labels);
        Named status = run.mapping.status(closed, history.reopened());
        Instant created = issue.createdAt().toInstant();
        Instant updated = issue.updatedAt() == null ? created : issue.updatedAt().toInstant();
        Instant resolved = closed ? (issue.closedAt() == null ? updated : issue.closedAt().toInstant()) : null;
        String summary = issue.title() == null || issue.title().isBlank() ? "(no title)" : issue.title().strip();

        CreatedIssue created1 = writer.createIssue(new NewIssue(run.projectId, type.id(), status.id(), run.id(priority),
                closed ? run.resolution.id() : null, summary, blankToNull(issue.body()), run.id(reporter),
                run.id(assignee), run.id(reporter),
                issue.dueDate() == null ? null : issue.dueDate().toLocalDate(), created, updated, resolved,
                run.sourceId, run.path + "#" + issue.number(), issue.htmlUrl()));
        long id = created1.id();
        String key = run.projectKey + "-" + created1.number();
        writer.remember(run.sourceId, Ref.ISSUE, externalId, id, run.now);

        writer.labels(id, labels.stream().map(run.mapping::label).filter(Objects::nonNull).toList());
        if (issue.milestone() != null && issue.milestone().title() != null && !issue.milestone().title().isBlank()) {
            writer.fixVersions(id, List.of(run.mapping.version(issue.milestone().title().strip()).id()));
        }
        if (reporter != null) {
            writer.watch(id, reporter.id(), created);
        }
        if (issue.assignees() != null) {
            for (User a : issue.assignees()) {
                writer.watch(id, run.user(a).id(), created);
            }
        }

        counts[1] += attachments(run, id, run.id(reporter), issue.assets(), created);
        List<Long> commentIds = new ArrayList<>();
        if (issue.comments() > 0) {
            for (Comment c : run.client.list(base + "/comments", Comment.class)) {
                commentIds.add(comment(run, id, c, counts));
            }
        }

        for (ForgejoHistory.Entry entry : history.entries()) {
            writer.history(id, run.id(run.user(entry.author())), entry.at(), entry.changes());
        }
        for (ForgejoHistory.PullRequest pr : history.pullRequests()) {
            writer.remoteLink(id, pr.url(), pr.title(), "PULL_REQUEST", pr.at());
        }
        Changes origin = new Changes();
        origin.record(Fields.IMPORT, null, null, issue.htmlUrl(), "Forgejo " + run.path + "#" + issue.number());
        writer.history(id, run.startedBy, run.now, origin);

        List<Dependency> dependencies = new ArrayList<>();
        for (Issue blocker : run.client.list(base + "/dependencies", Issue.class)) {
            String repo = blocker.repository() == null ? run.path : blocker.repository().fullName();
            TimelineEvent event = timeline.stream().filter(e -> "add_dependency".equals(e.type())
                    && e.dependentIssue() != null && e.dependentIssue().id() == blocker.id()).findFirst().orElse(null);
            dependencies.add(new Dependency(repo, blocker.number(),
                    event == null ? run.startedBy : run.id(run.user(event.user())),
                    event == null ? run.now : event.createdAt().toInstant()));
        }
        return new Done(id, key, issue, true, commentIds, dependencies);
    }

    /** For an issue an earlier run imported: only comments that are new since then. */
    private Done addNewComments(Run run, Issue issue, long issueId, int[] counts) {
        if (issue.comments() == 0) {
            return null;
        }
        List<Long> commentIds = new ArrayList<>();
        String base = "/repos/" + run.path + "/issues/" + issue.number();
        for (Comment c : run.client.list(base + "/comments", Comment.class)) {
            if (writer.localId(run.sourceId, Ref.COMMENT, String.valueOf(c.id())).isEmpty()) {
                commentIds.add(comment(run, issueId, c, counts));
            }
        }
        if (commentIds.isEmpty()) {
            return null;
        }
        String key = jdbc.queryForObject("""
                select p.key || '-' || i.number from issue i join project p on p.id = i.project_id where i.id = :id""",
                Map.of("id", issueId), String.class);
        return new Done(issueId, key, issue, false, commentIds, List.of());
    }

    private long comment(Run run, long issueId, Comment c, int[] counts) {
        Named author = run.author(c.user(), c.originalAuthor());
        Instant created = c.createdAt().toInstant();
        Instant updated = c.updatedAt() == null || c.updatedAt().toInstant().isBefore(created) ? created
                : c.updatedAt().toInstant();
        long commentId = writer.comment(issueId, run.id(author), c.body(), created, updated);
        writer.remember(run.sourceId, Ref.COMMENT, String.valueOf(c.id()), commentId, run.now);
        if (author != null) {
            writer.watch(issueId, author.id(), created);
        }
        counts[0]++;
        counts[1] += attachments(run, issueId, run.id(author), c.assets(), created);
        return commentId;
    }

    private int attachments(Run run, long issueId, Long authorId, List<Asset> assets, Instant fallback) {
        if (assets == null) {
            return 0;
        }
        int stored = 0;
        for (Asset asset : assets) {
            String uuid = asset.uuid() == null ? null : asset.uuid().toLowerCase(Locale.ROOT);
            if (uuid == null || writer.localId(run.sourceId, Ref.ATTACHMENT, uuid).isPresent()) {
                continue;
            }
            if (asset.size() > maxFileSize) {
                run.context.warn("Attachment " + asset.name() + " (" + asset.size() + " bytes) is larger than "
                        + "allowed and was not copied; the text links to the original.");
                continue;
            }
            Instant at = asset.createdAt() == null ? fallback : asset.createdAt().toInstant();
            try (InputStream in = run.client.download(asset.browserDownloadUrl(), maxFileSize)) {
                long attachmentId = writer.attachment(issueId, authorId, asset.name(), null, at, in);
                writer.remember(run.sourceId, Ref.ATTACHMENT, uuid, attachmentId, run.now);
                stored++;
            } catch (IOException | java.io.UncheckedIOException e) {
                run.context.warn("Attachment " + asset.name() + " could not be copied (" + e.getMessage()
                        + "); the text links to the original.");
            }
        }
        return stored;
    }

    /** Round two for one issue: references in texts, and dependencies. */
    private void finish(Run run, ForgejoText text, Done d) {
        if (d.isNew() && d.issue().body() != null && !d.issue().body().isBlank()) {
            String rewritten = text.rewrite(d.issue().body());
            if (!rewritten.equals(d.issue().body())) {
                writer.description(d.issueId(), rewritten);
            }
        }
        for (long commentId : d.commentIds()) {
            String body = jdbc.queryForObject("select body from comment where id = :id", Map.of("id", commentId),
                    String.class);
            String rewritten = text.rewrite(body);
            if (!rewritten.equals(body)) {
                writer.commentBody(commentId, rewritten);
            }
        }
        for (Dependency dep : d.dependencies()) {
            Optional<Named> blocker = writer.issueBySource(KIND, run.client.baseUrl(), dep.repository(), dep.number());
            if (blocker.isEmpty()) {
                run.context.warn(d.key() + " depends on " + dep.repository() + "#" + dep.number()
                        + ", which was not imported; the dependency was skipped.");
                continue;
            }
            writer.blocks(blocker.get().id(), blocker.get().name(), d.issueId(), d.key(), dep.authorId(), dep.at());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
