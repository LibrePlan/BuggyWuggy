/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportDtos.Analysis;
import org.buggywuggy.importer.ImportDtos.LabelInfo;
import org.buggywuggy.importer.ImportDtos.LabelRule;
import org.buggywuggy.importer.ImportDtos.LabelTarget;
import org.buggywuggy.importer.ImportDtos.Options;
import org.buggywuggy.importer.ImportDtos.UserInfo;
import org.buggywuggy.importer.ImportRunner.CancelledException;
import org.buggywuggy.importer.ImportRunner.Context;
import org.buggywuggy.importer.ImportWriter.CreatedIssue;
import org.buggywuggy.importer.ImportWriter.Hierarchy;
import org.buggywuggy.importer.ImportWriter.Named;
import org.buggywuggy.importer.ImportWriter.NewIssue;
import org.buggywuggy.importer.ImportWriter.Ref;
import org.buggywuggy.importer.ImportWriter.ResolvedUser;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.ImportWriter.Statuses;
import org.buggywuggy.importer.Source.CloseReason;
import org.buggywuggy.importer.Source.SourceAttachment;
import org.buggywuggy.importer.Source.SourceComment;
import org.buggywuggy.importer.Source.SourceDependency;
import org.buggywuggy.importer.Source.SourceDetails;
import org.buggywuggy.importer.Source.SourceIssue;
import org.buggywuggy.importer.Source.SourceLabel;
import org.buggywuggy.importer.Source.SourceMilestone;
import org.buggywuggy.importer.Source.SourceRef;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * Imports the issues of one {@link Source} into a project, for every kind of tracker.
 *
 * <p>Round one creates each issue in its own transaction: fields, comments, attachments,
 * watchers, the original history and the pull requests that referenced it. Round two, once
 * all issues exist, rewrites references in the texts and adds the dependencies; round three
 * turns sub-issues into sub-tasks (one level deep) or links. Issues that an earlier run
 * imported only get their new comments.
 */
@Component
public class IssueImporter {

    private static final int MAX_FAILURES_IN_A_ROW = 10;
    /** Looking up display names costs a request per person; enough for the wizard. */
    private static final int MAX_PROFILES_IN_ANALYSIS = 300;

    private final ImportWriter writer;
    private final ImportJobs jobs;
    private final TransactionTemplate tx;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final long maxFileSize;

    public IssueImporter(ImportWriter writer, ImportJobs jobs, TransactionTemplate tx, NamedParameterJdbcTemplate jdbc,
            Clock clock, @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize) {
        this.writer = writer;
        this.jobs = jobs;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxFileSize = maxFileSize.toBytes();
    }

    /** owner/name, or group/subgroup/…/name (GitLab). */
    public static final String PATH = "[\\w.\\-]+(?:/[\\w.\\-]+)+";

    /** {@code owner/name}, or a bad request. */
    public static String path(String repository) {
        String path = repository == null ? "" : repository.strip();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.matches(PATH)) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return path;
    }

    // --- analysis ------------------------------------------------------------------------

    public Analysis analyze(Source source) {
        List<SourceIssue> issues = source.issues();
        List<SourceLabel> labels = source.labels();
        List<SourceMilestone> milestones = source.milestones();

        Map<String, Integer> labelUse = new HashMap<>();
        Map<String, SourceUser> users = new LinkedHashMap<>();
        Map<String, Integer> userUse = new HashMap<>();
        int comments = 0;
        int open = 0;
        for (SourceIssue issue : issues) {
            comments += Math.max(0, issue.commentCount());
            if (!issue.closed()) {
                open++;
            }
            issue.labels().forEach(l -> labelUse.merge(l, 1, Integer::sum));
            List<SourceUser> involved = new ArrayList<>();
            if (issue.author() != null) {
                involved.add(issue.author());
            }
            involved.addAll(issue.assignees());
            for (SourceUser u : involved) {
                users.putIfAbsent(u.externalId(), u);
                userUse.merge(u.externalId(), 1, Integer::sum);
            }
        }
        Long sourceId = jdbc.queryForList("""
                select id from import_source where kind = :kind and lower(base_url) = lower(:url)
                  and lower(project_path) = lower(:path)""",
                Map.of("kind", source.kind(), "url", source.baseUrl(), "path", source.path()), Long.class)
                .stream().findFirst().orElse(null);
        int already = sourceId == null ? 0 : jdbc.queryForObject("""
                select count(*) from external_ref r join issue i on i.id = r.local_id
                where r.import_source_id = :source and r.source_type = 'issue'""",
                Map.of("source", sourceId), Integer.class);

        Map<Long, String> types = writer.names("issue_type");
        Map<Long, String> priorities = writer.names("priority");
        List<LabelInfo> labelInfos = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SourceLabel l : labels) {
            seen.add(l.name());
            labelInfos.add(new LabelInfo(l.name(), l.color() == null ? "" : l.color(), blankToNull(l.description()),
                    labelUse.getOrDefault(l.name(), 0), suggest(l.name(), types, priorities)));
        }
        // Labels that are not listed for the repository (of the organisation, issue types).
        labelUse.keySet().stream().filter(n -> !seen.contains(n)).sorted().forEach(n -> labelInfos.add(
                new LabelInfo(n, "", null, labelUse.get(n), suggest(n, types, priorities))));

        int[] profiles = {0};
        List<UserInfo> userInfos = users.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, SourceUser> e) -> userUse.get(e.getKey())).reversed())
                .map(e -> {
                    SourceUser u = profiles[0]++ < MAX_PROFILES_IN_ANALYSIS ? source.profile(e.getValue())
                            : e.getValue();
                    UserRef suggestion = writer.suggest(u, true).map(this::userRef).orElse(null);
                    Optional<Long> earlier = sourceId == null ? Optional.empty()
                            : writer.localId(sourceId, Ref.USER, e.getKey());
                    if (earlier.isPresent()) {
                        suggestion = userRef(earlier.get());
                    }
                    return new UserInfo(e.getKey(), u.login(), blankToNull(u.name()), userUse.get(e.getKey()),
                            suggestion);
                })
                .sorted(Comparator.comparing(UserInfo::issues).reversed().thenComparing(UserInfo::login)).toList();

        return new Analysis(source.info(), issues.size(), open, issues.size() - open, comments, milestones.size(),
                already, labelInfos, userInfos);
    }

    private UserRef userRef(long id) {
        return jdbc.queryForObject("select id, username, display_name from user_account where id = :id",
                Map.of("id", id), (rs, n) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    /**
     * Suggests a mapping from common label names: kind/bug, bug, priority/high, … and issue
     * types of the source ({@code type:Bug}).
     */
    public static LabelRule suggest(String label, Map<Long, String> types, Map<Long, String> priorities) {
        String name = label.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        boolean sourceType = name.startsWith("type:");
        if (sourceType) {
            name = "type/" + name.substring("type:".length()).strip();
        }
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
        Map<String, String> typeAliases = Map.of("bug", "bug", "defect", "bug", "feature", "story",
                "enhancement", "story", "task", "task", "story", "story");
        String type = typeAliases.get(last);
        if (type != null && (!name.contains("/") || name.startsWith("kind") || name.startsWith("type"))) {
            for (Map.Entry<Long, String> t : types.entrySet()) {
                if (t.getValue().equalsIgnoreCase(type)) {
                    return new LabelRule(LabelTarget.ISSUE_TYPE, t.getKey());
                }
            }
        }
        // An issue type of the source that is no type here would only be noise as a label.
        return new LabelRule(sourceType ? LabelTarget.IGNORE : LabelTarget.LABEL, null);
    }

    // --- import --------------------------------------------------------------------------

    /** What rounds two and three need of an issue round one created or extended. */
    private record Done(long issueId, String key, SourceIssue issue, boolean isNew, List<Long> commentIds,
            List<SourceDependency> dependencies, List<SourceRef> children, List<SourceRef> related) {
    }

    /** State of one run. */
    private final class Run {
        final Context context;
        final Source source;
        final long sourceId;
        final long projectId;
        final String projectKey;
        final Options options;
        final long startedBy;
        final Instant now;
        final Statuses statuses;
        final Named completed;
        final Named notPlanned;
        final Named duplicate;
        final Named defaultType;
        final Named defaultPriority;
        final Map<Long, String> typeNames;
        final Map<Long, String> priorityNames;
        final Map<String, Named> versions = new HashMap<>();
        final Map<String, Named> users = new HashMap<>();
        int usersCreated;

        Run(Context context, Source source, long sourceId, long projectId, Options options, long startedBy) {
            this.context = context;
            this.source = source;
            this.sourceId = sourceId;
            this.projectId = projectId;
            this.projectKey = writer.projectKey(projectId);
            this.options = options;
            this.startedBy = startedBy;
            this.now = clock.instant();
            this.typeNames = writer.names("issue_type");
            this.priorityNames = writer.names("priority");
            this.defaultType = new Named(options.issueTypeId(), typeNames.get(options.issueTypeId()));
            this.statuses = writer.statuses(projectId, options.issueTypeId());
            this.completed = writer.defaultResolution();
            this.notPlanned = writer.resolution("Won't Fix");
            this.duplicate = writer.resolution("Duplicate");
            this.defaultPriority = writer.defaultPriority().orElse(null);
        }

        LabelRule rule(String label) {
            LabelRule rule = options.labels() == null ? null : options.labels().get(label);
            return rule == null ? new LabelRule(LabelTarget.LABEL, null) : rule;
        }

        final ImportHistory.Mapping mapping = new ImportHistory.Mapping() {
            @Override
            public String label(String sourceLabel) {
                return rule(sourceLabel).target() == LabelTarget.LABEL ? ImportWriter.label(sourceLabel) : null;
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
            public Named user(SourceUser user) {
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
            public Named resolution(boolean closed, CloseReason reason) {
                if (!closed) {
                    return null;
                }
                return reason == CloseReason.NOT_PLANNED ? notPlanned
                        : reason == CloseReason.DUPLICATE ? duplicate : completed;
            }
        };

        Named user(SourceUser user) {
            if (user == null) {
                return null;
            }
            return users.computeIfAbsent(user.externalId(), id -> {
                Long chosen = options.users() == null ? null : options.users().get(id);
                SourceUser full = chosen == null && writer.localId(sourceId, Ref.USER, id).isEmpty()
                        ? source.profile(user) : user;
                ResolvedUser resolved = writer.user(sourceId, full, chosen, options.matchUsernames(), source.host(),
                        now);
                if (resolved.created()) {
                    usersCreated++;
                }
                return new Named(resolved.id(), writer.userName(resolved.id()));
            });
        }

        Long id(Named named) {
            return named == null ? null : named.id();
        }

        String origin(long number) {
            return source.path() + "#" + number;
        }
    }

    public void run(Context context, Source source, long sourceId, long projectId, Options options,
            long startedBy) {
        source.watch(context::cancelled, context);
        Run run = new Run(context, source, sourceId, projectId, options, startedBy);
        long jobId = context.jobId();
        context.status("Reading the list of issues");
        context.info("Reading the issues of " + source.path() + " on " + source.baseUrl() + ".");
        List<SourceMilestone> milestones = source.milestones();
        List<SourceIssue> issues = source.issues().stream()
                .sorted(Comparator.comparingLong(SourceIssue::number)).toList();
        jobs.total(jobId, issues.size());
        context.info(issues.size() + " issues found in " + source.path() + ".");

        tx.executeWithoutResult(status -> {
            for (SourceMilestone m : milestones) {
                if (m.title() == null || m.title().isBlank()) {
                    continue;
                }
                long id = writer.version(projectId, m.title().strip(), m.description(), m.due(), m.closed());
                run.versions.put(m.title().strip(), new Named(id, m.title().strip()));
            }
        });

        List<Done> done = new ArrayList<>();
        int failuresInARow = 0;
        int failed = 0;
        int position = 0;
        for (SourceIssue issue : issues) {
            if (context.cancelled()) {
                throw new CancelledException();
            }
            context.status("Issue " + ++position + " of " + issues.size() + ": #" + issue.number());
            int usersBefore = run.usersCreated;
            Map<String, Named> knownUsers = new HashMap<>(run.users);
            Map<String, Named> knownVersions = new HashMap<>(run.versions);
            int[] counts = new int[2];
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

        context.status("Linking issues and updating references");
        context.info("Linking issues and updating references.");
        ImportText text = new ImportText(source.webUrl(), source.path(), source.attachmentLinks(), resolver(run));
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
        hierarchy(run, done);

        long imported = done.stream().filter(Done::isNew).count();
        context.info("Done: " + imported + " issues imported, " + (issues.size() - imported - failed)
                + " were imported earlier, " + failed + " failed.");
        if (failed > 0) {
            throw new IllegalStateException(failed + " issues could not be imported; see the warnings. Run the "
                    + "import again to retry them.");
        }
    }

    private ImportText.Resolver resolver(Run run) {
        return new ImportText.Resolver() {
            @Override
            public Optional<String> issueKey(String repository, long number) {
                return writer.issueBySource(run.source.kind(), run.source.baseUrl(),
                        repository == null ? run.source.path() : repository, number).map(Named::name);
            }

            @Override
            public Optional<Long> attachmentId(String key) {
                return writer.localId(run.sourceId, Ref.ATTACHMENT, run.source.attachmentKey(key));
            }

            @Override
            public String issueUrl(String repository, long number) {
                return run.source.issueUrl(repository == null ? run.source.path() : repository, number);
            }

            @Override
            public String attachmentUrl(String found) {
                return run.source.attachmentUrl(found);
            }
        };
    }

    /** Round one for one issue. {@code counts}: comments, attachments. */
    private Done importIssue(Run run, SourceIssue issue, int[] counts) {
        Optional<Long> existing = writer.localId(run.sourceId, Ref.ISSUE, issue.externalId());
        if (existing.isPresent()) {
            return addNewComments(run, issue, existing.get(), counts);
        }
        SourceDetails details = run.source.details(issue);
        ImportHistory.Result history = ImportHistory.of(issue, details.events(), run.mapping);

        Set<String> labels = new LinkedHashSet<>(issue.labels());
        boolean closed = issue.closed();
        Named reporter = run.user(issue.author());
        Named assignee = issue.assignees().isEmpty() ? null : run.user(issue.assignees().getFirst());
        Named type = run.mapping.issueType(labels);
        Named priority = run.mapping.priority(labels);
        Named status = run.mapping.status(closed, history.reopened());
        Named resolution = run.mapping.resolution(closed, issue.closeReason());
        Instant created = issue.createdAt();
        Instant updated = issue.updatedAt() == null || issue.updatedAt().isBefore(created) ? created
                : issue.updatedAt();
        Instant resolved = closed ? (issue.closedAt() == null ? updated : issue.closedAt()) : null;
        String summary = issue.title() == null || issue.title().isBlank() ? "(no title)" : issue.title().strip();

        CreatedIssue createdIssue = writer.createIssue(new NewIssue(run.projectId, type.id(), status.id(),
                run.id(priority), run.id(resolution), summary, blankToNull(issue.body()), run.id(reporter),
                run.id(assignee), run.id(reporter), issue.due(), created, updated, resolved, run.sourceId,
                run.origin(issue.number()), issue.url()));
        long id = createdIssue.id();
        String key = run.projectKey + "-" + createdIssue.number();
        writer.remember(run.sourceId, Ref.ISSUE, issue.externalId(), id, run.now);

        writer.labels(id, labels.stream().map(run.mapping::label).filter(Objects::nonNull).toList());
        if (issue.milestone() != null && !issue.milestone().isBlank()) {
            writer.fixVersions(id, List.of(run.mapping.version(issue.milestone().strip()).id()));
        }
        if (reporter != null) {
            writer.watch(id, reporter.id(), created);
        }
        for (SourceUser a : issue.assignees()) {
            writer.watch(id, run.user(a).id(), created);
        }

        counts[1] += attachments(run, id, run.id(reporter), issue.attachments(), created);
        List<Long> commentIds = new ArrayList<>();
        for (SourceComment c : details.comments()) {
            commentIds.add(comment(run, id, c, counts));
        }

        for (ImportHistory.Entry entry : history.entries()) {
            writer.history(id, run.id(run.user(entry.author())), entry.at(), entry.changes());
        }
        for (ImportHistory.PullRequest pr : history.pullRequests()) {
            writer.remoteLink(id, pr.url(), pr.title(), "PULL_REQUEST", pr.at());
        }
        Changes origin = new Changes();
        origin.record(Fields.IMPORT, null, null, issue.url(),
                run.source.displayName() + " " + run.origin(issue.number()));
        writer.history(id, run.startedBy, run.now, origin);
        return new Done(id, key, issue, true, commentIds, details.blockedBy(), details.children(), details.related());
    }

    /** For an issue an earlier run imported: only comments that are new since then. */
    private Done addNewComments(Run run, SourceIssue issue, long issueId, int[] counts) {
        if (issue.commentCount() == 0) {
            return null;
        }
        List<Long> commentIds = new ArrayList<>();
        for (SourceComment c : run.source.comments(issue)) {
            if (writer.localId(run.sourceId, Ref.COMMENT, c.externalId()).isEmpty()) {
                commentIds.add(comment(run, issueId, c, counts));
            }
        }
        if (commentIds.isEmpty()) {
            return null;
        }
        String key = jdbc.queryForObject("""
                select p.key || '-' || i.number from issue i join project p on p.id = i.project_id where i.id = :id""",
                Map.of("id", issueId), String.class);
        return new Done(issueId, key, issue, false, commentIds, List.of(), List.of(), List.of());
    }

    private long comment(Run run, long issueId, SourceComment c, int[] counts) {
        Named author = run.user(c.author());
        Instant created = c.createdAt();
        Instant updated = c.updatedAt() == null || c.updatedAt().isBefore(created) ? created : c.updatedAt();
        long commentId = writer.comment(issueId, run.id(author), c.body() == null ? "" : c.body(), created, updated);
        writer.remember(run.sourceId, Ref.COMMENT, c.externalId(), commentId, run.now);
        if (author != null) {
            writer.watch(issueId, author.id(), created);
        }
        counts[0]++;
        counts[1] += attachments(run, issueId, run.id(author), c.attachments(), created);
        return commentId;
    }

    private int attachments(Run run, long issueId, Long authorId, List<SourceAttachment> attachments,
            Instant fallback) {
        int stored = 0;
        for (SourceAttachment attachment : attachments) {
            String key = attachment.key() == null ? null : run.source.attachmentKey(attachment.key());
            if (key == null || writer.localId(run.sourceId, Ref.ATTACHMENT, key).isPresent()) {
                continue;
            }
            String label = attachment.name() != null ? attachment.name() : attachment.url();
            if (attachment.size() > maxFileSize) {
                run.context.warn("Attachment " + label + " (" + attachment.size() + " bytes) is larger than "
                        + "allowed and was not copied; the text links to the original.");
                continue;
            }
            Instant at = attachment.createdAt() == null ? fallback : attachment.createdAt();
            try {
                ReadOnlyHttp.Download download = run.source.download(attachment, maxFileSize);
                try (InputStream in = download.content()) {
                    String name = attachment.name() != null ? attachment.name() : fileName(download.uri());
                    long attachmentId = writer.attachment(issueId, authorId, name, download.contentType(), at, in);
                    writer.remember(run.sourceId, Ref.ATTACHMENT, key, attachmentId, run.now);
                    stored++;
                }
            } catch (IOException | UncheckedIOException e) {
                run.context.warn("Attachment " + label + " could not be copied (" + e.getMessage()
                        + "); the text links to the original.");
            }
        }
        return stored;
    }

    /** The last part of the path of where a file was downloaded from. */
    static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? "attachment" : last;
    }

    /** Round two for one issue: references in texts, and dependencies. */
    private void finish(Run run, ImportText text, Done d) {
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
        for (SourceDependency dep : d.dependencies()) {
            String repo = dep.blocker().repository() == null ? run.source.path() : dep.blocker().repository();
            Optional<Named> blocker = writer.issueBySource(run.source.kind(), run.source.baseUrl(), repo,
                    dep.blocker().number());
            if (blocker.isEmpty()) {
                run.context.warn(d.key() + " depends on " + repo + "#" + dep.blocker().number()
                        + ", which was not imported; the dependency was skipped.");
                continue;
            }
            writer.blocks(blocker.get().id(), blocker.get().name(), d.issueId(), d.key(),
                    dep.author() == null ? run.startedBy : run.id(run.user(dep.author())),
                    dep.at() == null ? run.now : dep.at());
        }
        for (SourceRef ref : d.related()) {
            String repo = ref.repository() == null ? run.source.path() : ref.repository();
            Optional<Named> other = writer.issueBySource(run.source.kind(), run.source.baseUrl(), repo, ref.number());
            // Both issues list the link; the second one finds it already there.
            if (other.isPresent() && other.get().id() != d.issueId() && !writer.linked(d.issueId(), other.get().id())) {
                writer.link("Relates", d.issueId(), d.key(), other.get().id(), other.get().name(), run.startedBy,
                        run.now);
            }
        }
    }

    /**
     * Round three: sub-issues. BuggyWuggy has one level of sub-tasks under a standard issue,
     * and standard issues under an epic. So a sub-issue of a standard issue becomes a
     * sub-task, a sub-issue of an epic gets it as parent, and anything deeper or otherwise
     * not allowed becomes a "relates to" link. Parents are handled from the top down.
     */
    private void hierarchy(Run run, List<Done> done) {
        Map<String, Done> byOrigin = new HashMap<>();
        Set<String> children = new HashSet<>();
        for (Done d : done) {
            byOrigin.put(run.origin(d.issue().number()).toLowerCase(Locale.ROOT), d);
            for (SourceRef c : d.children()) {
                children.add(originOf(run, c));
            }
        }
        Deque<Done> queue = new ArrayDeque<>();
        done.stream().filter(d -> !d.children().isEmpty()
                && !children.contains(run.origin(d.issue().number()).toLowerCase(Locale.ROOT))).forEach(queue::add);
        done.stream().filter(d -> !d.children().isEmpty() && !queue.contains(d)).forEach(queue::add);
        Set<Long> handled = new HashSet<>();
        Optional<Named> subtask = writer.subtaskType();
        while (!queue.isEmpty()) {
            Done parent = queue.poll();
            if (!handled.add(parent.issueId())) {
                continue;
            }
            for (SourceRef ref : parent.children()) {
                String repo = ref.repository() == null ? run.source.path() : ref.repository();
                try {
                    tx.executeWithoutResult(status -> child(run, parent, repo, ref.number(), subtask));
                } catch (RuntimeException e) {
                    run.context.warn(parent.key() + ": sub-issue " + repo + "#" + ref.number()
                            + " could not be linked: " + run.context.describe(e));
                }
                Done next = byOrigin.get((repo + "#" + ref.number()).toLowerCase(Locale.ROOT));
                if (next != null && !next.children().isEmpty() && !handled.contains(next.issueId())) {
                    queue.addFirst(next);
                }
            }
        }
    }

    private static String originOf(Run run, SourceRef ref) {
        return ((ref.repository() == null ? run.source.path() : ref.repository()) + "#" + ref.number())
                .toLowerCase(Locale.ROOT);
    }

    private void child(Run run, Done parentDone, String repo, long number, Optional<Named> subtask) {
        Optional<Named> found = writer.issueBySource(run.source.kind(), run.source.baseUrl(), repo, number);
        if (found.isEmpty()) {
            run.context.warn(parentDone.key() + " has sub-issue " + repo + "#" + number
                    + ", which was not imported; it was skipped.");
            return;
        }
        Hierarchy parent = writer.hierarchy(parentDone.issueId()).orElseThrow();
        Hierarchy child = writer.hierarchy(found.get().id()).orElseThrow();
        if (child.parentId() != null && child.parentId() == parent.id()) {
            return;
        }
        boolean sameProject = parent.projectId() == child.projectId();
        boolean free = sameProject && child.parentId() == null && child.level() == 0;
        if (free && parent.level() == 1) {
            writer.parent(child, parent.id(), parentDone.key(), null, run.startedBy, run.now);
        } else if (free && parent.level() == 0 && !child.hasChildren() && subtask.isPresent()
                && writer.workflowHas(child.projectId(), subtask.get().id(), child.statusId())) {
            writer.parent(child, parent.id(), parentDone.key(), subtask.get(), run.startedBy, run.now);
        } else {
            writer.link("Relates", parent.id(), parentDone.key(), child.id(), found.get().name(), run.startedBy,
                    run.now);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
