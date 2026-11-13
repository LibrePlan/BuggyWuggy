/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.trac;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.http.HttpStatus;

/**
 * One Trac environment as a source of tickets. Type, priority, component and severity become
 * labels the administrator maps ({@code type:defect}, {@code priority/major},
 * {@code component/core}, {@code severity/blocker}); keywords become labels; the milestone
 * becomes the version. Texts are converted from WikiFormatting to Markdown, and the history
 * comes from the ticket's change log. Fields of common plugins are used when present:
 * {@code blockedby} (MasterTickets) for dependencies, {@code parents} (Subtickets) for
 * sub-tickets and {@code sensitive} (SensitiveTickets) for the label {@code restricted}.
 */
final class TracSource implements Source {

    static final String RESTRICTED = "restricted";
    static final String TYPE_PREFIX = "type:";
    static final String PRIORITY_PREFIX = "priority/";
    static final String COMPONENT_PREFIX = "component/";
    static final String SEVERITY_PREFIX = "severity/";

    private static final Pattern NAME_AND_EMAIL = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<([^<>\\s]+@[^<>\\s]+)>\\s*$");
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    private final TracConnector.Api api;
    private final String name;
    private final Pattern attachments;
    private final Map<String, SourceUser> users = new HashMap<>();
    private final Map<Long, List<SourceRef>> children = new HashMap<>();
    private final Map<Long, List<Long>> blockedBy = new HashMap<>();

    TracSource(TracConnector.Api api) {
        this.api = api;
        this.name = TracConnector.name(api.web);
        String under = api.web.getRawPath() == null || api.web.getRawPath().isEmpty() ? ""
                : "|" + Pattern.quote(api.web.getRawPath());
        this.attachments = Pattern.compile("(?:" + Pattern.quote(api.web.toString()) + under
                + ")?/(?:raw-)?attachment/ticket/(\\d+/[^\\s)\"'<>\\]?#]+)");
    }

    static RepositoryInfo info(TracConnector.Api api) {
        String name = TracConnector.name(api.web);
        return new RepositoryInfo(name, name, false, true, -1, api.web.toString());
    }

    @Override
    public String kind() {
        return TracConnector.KIND;
    }

    @Override
    public String displayName() {
        return "Trac";
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
        return name;
    }

    @Override
    public String host() {
        return api.web.getHost();
    }

    /** Ticket numbers belong to the environment: /ticket/12 has no project. */
    @Override
    public Pattern issueLinks() {
        return Pattern.compile("()" + Pattern.quote(api.web.toString()) + "/ticket/(\\d+)/?");
    }

    @Override
    public String issueUrl(String repository, long number) {
        return api.web + "/ticket/" + number;
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
        return info(api);
    }

    @Override
    public List<SourceLabel> labels() {
        return optionalList("ticket.component.getAll").stream()
                .map(c -> new SourceLabel(COMPONENT_PREFIX + c, "", null)).toList();
    }

    @Override
    public List<SourceMilestone> milestones() {
        List<SourceMilestone> milestones = new ArrayList<>();
        for (Object milestoneName : optionalList("ticket.milestone.getAll")) {
            Map<?, ?> m = api.call("ticket.milestone.get", String.valueOf(milestoneName)) instanceof Map<?, ?> map
                    ? map : Map.of();
            Instant due = m.get("due") instanceof Instant i ? i : null;
            boolean completed = m.get("completed") instanceof Instant;
            milestones.add(new SourceMilestone(String.valueOf(milestoneName), text(m.get("description")),
                    due == null ? null : LocalDate.ofInstant(due, ZoneOffset.UTC), completed));
        }
        return milestones;
    }

    @Override
    public List<SourceIssue> issues() {
        children.clear();
        blockedBy.clear();
        List<SourceIssue> issues = new ArrayList<>();
        for (Object id : api.list("ticket.query", "max=0&order=id")) {
            if (!(id instanceof Integer number)) {
                continue;
            }
            List<Object> ticket = api.list("ticket.get", number);
            if (ticket.size() < 4 || !(ticket.get(3) instanceof Map<?, ?> attributes)) {
                continue;
            }
            List<SourceAttachment> files = attachments(number);
            issues.add(issue(number, ticket, attributes, files));
            for (String parent : numbers(text(attributes.get("parents")))) {
                children.computeIfAbsent(Long.parseLong(parent), p -> new ArrayList<>())
                        .add(new SourceRef(null, number));
            }
            List<Long> blockers = numbers(text(attributes.get("blockedby"))).stream().map(Long::parseLong).toList();
            if (!blockers.isEmpty()) {
                blockedBy.put((long) number, blockers);
            }
        }
        return issues;
    }

    private SourceIssue issue(int id, List<Object> ticket, Map<?, ?> a, List<SourceAttachment> files) {
        Set<String> labels = new LinkedHashSet<>(words(text(a.get("keywords"))));
        addLabel(labels, TYPE_PREFIX, a.get("type"));
        addLabel(labels, PRIORITY_PREFIX, a.get("priority"));
        addLabel(labels, COMPONENT_PREFIX, a.get("component"));
        addLabel(labels, SEVERITY_PREFIX, a.get("severity"));
        if ("1".equals(text(a.get("sensitive")))) {
            labels.add(RESTRICTED);
        }
        boolean closed = "closed".equals(text(a.get("status")));
        String owner = text(a.get("owner"));
        Instant created = ticket.get(1) instanceof Instant i ? i : null;
        Instant changed = ticket.get(2) instanceof Instant i ? i : created;
        return new SourceIssue(String.valueOf(id), id, api.web + "/ticket/" + id, user(text(a.get("reporter"))),
                text(a.get("summary")), markdown(text(a.get("description")), files), new ArrayList<>(labels),
                blankToNull(text(a.get("milestone"))),
                owner == null || owner.isBlank() ? List.of() : List.of(user(owner)), closed,
                closed ? Source.closeReason(text(a.get("resolution"))) : null, created, changed, null, null, -1,
                files);
    }

    private static void addLabel(Set<String> labels, String prefix, Object value) {
        String text = text(value);
        if (text != null && !text.isBlank()) {
            labels.add(prefix + text.strip());
        }
    }

    /** [filename, description, size, time, author] for each file. */
    private List<SourceAttachment> attachments(int id) {
        List<SourceAttachment> files = new ArrayList<>();
        for (Object item : optionalList("ticket.listAttachments", id)) {
            if (item instanceof List<?> f && f.size() >= 4 && f.get(0) != null) {
                String file = String.valueOf(f.get(0));
                String encoded = URLEncoder.encode(file, StandardCharsets.UTF_8).replace("+", "%20");
                long size = f.get(2) instanceof Integer s ? s : -1;
                files.add(new SourceAttachment(id + "/" + encoded, file, size,
                        api.web + "/raw-attachment/ticket/" + id + "/" + encoded,
                        f.get(3) instanceof Instant t ? t : null));
            }
        }
        return files;
    }

    @Override
    public SourceDetails details(SourceIssue issue) {
        List<SourceComment> comments = new ArrayList<>();
        List<SourceEvent> events = new ArrayList<>();
        // [time, author, field, old value, new value, permanent]; one change has one time.
        List<List<?>> changes = new ArrayList<>();
        for (Object item : api.list("ticket.changeLog", (int) issue.number())) {
            if (item instanceof List<?> c && c.size() >= 5 && c.get(0) instanceof Instant) {
                changes.add(c);
            }
        }
        for (List<?> c : changes) {
            Instant at = (Instant) c.get(0);
            String author = text(c.get(1));
            String field = text(c.get(2));
            String before = blankToNull(text(c.get(3)));
            String after = blankToNull(text(c.get(4)));
            SourceUser actor = user(author);
            if (field == null) {
                continue;
            }
            switch (field) {
                case "comment" -> {
                    if (after != null) {
                        String number = before == null ? at.toString()
                                : before.substring(before.lastIndexOf('.') + 1);
                        comments.add(new SourceComment(issue.number() + ":" + number, actor,
                                markdown(after, issue.attachments()), at, at, List.of()));
                    }
                }
                case "summary" -> events.add(new SourceEvent(Kind.RENAMED, actor, at, before, after, null, null));
                case "owner" -> {
                    if (before != null) {
                        events.add(new SourceEvent(Kind.UNASSIGNED, actor, at, null, null, user(before), null));
                    }
                    if (after != null) {
                        events.add(new SourceEvent(Kind.ASSIGNED, actor, at, null, null, user(after), null));
                    }
                }
                case "milestone" -> events.add(new SourceEvent(Kind.MILESTONE, actor, at, before, after, null,
                        null));
                case "type" -> renamedLabel(TYPE_PREFIX, before, after, actor, at, events);
                case "priority" -> renamedLabel(PRIORITY_PREFIX, before, after, actor, at, events);
                case "component" -> renamedLabel(COMPONENT_PREFIX, before, after, actor, at, events);
                case "severity" -> renamedLabel(SEVERITY_PREFIX, before, after, actor, at, events);
                case "keywords" -> {
                    Set<String> old = words(before);
                    Set<String> now = words(after);
                    old.stream().filter(k -> !now.contains(k)).forEach(k -> events.add(new SourceEvent(
                            Kind.LABEL_REMOVED, actor, at, null, k, null, null)));
                    now.stream().filter(k -> !old.contains(k)).forEach(k -> events.add(new SourceEvent(
                            Kind.LABEL_ADDED, actor, at, null, k, null, null)));
                }
                case "status" -> {
                    if ("closed".equals(after) && !"closed".equals(before)) {
                        String resolution = changes.stream().filter(o -> at.equals(o.get(0))
                                && "resolution".equals(text(o.get(2)))).map(o -> blankToNull(text(o.get(4))))
                                .filter(r -> r != null).findFirst().orElse(null);
                        events.add(new SourceEvent(Kind.CLOSED, actor, at, null, null, null,
                                Source.closeReason(resolution)));
                    } else if ("closed".equals(before) && after != null && !"closed".equals(after)) {
                        events.add(new SourceEvent(Kind.REOPENED, actor, at, null, null, null, null));
                    }
                }
                default -> {
                    // description, cc, version, edits of comments, custom fields
                }
            }
        }
        List<SourceDependency> dependencies = blockedBy.getOrDefault(issue.number(), List.of()).stream()
                .map(b -> new SourceDependency(new SourceRef(null, b), null, null)).toList();
        return new SourceDetails(comments, events, dependencies, children.getOrDefault(issue.number(), List.of()),
                List.of());
    }

    private static void renamedLabel(String prefix, String before, String after, SourceUser actor, Instant at,
            List<SourceEvent> events) {
        if (before != null) {
            events.add(new SourceEvent(Kind.LABEL_REMOVED, actor, at, null, prefix + before, null, null));
        }
        if (after != null) {
            events.add(new SourceEvent(Kind.LABEL_ADDED, actor, at, null, prefix + after, null, null));
        }
    }

    @Override
    public ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException {
        try {
            return api.http.download(URI.create(attachment.url()), maxBytes);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid address");
        }
    }

    // --- texts and users -----------------------------------------------------------------

    private String markdown(String text, List<SourceAttachment> files) {
        return TracWiki.toMarkdown(text, new TracWiki.Context() {
            @Override
            public String attachment(String ticket, String fileName) {
                String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
                if (ticket != null) {
                    return api.web + "/raw-attachment/ticket/" + ticket + "/" + encoded;
                }
                // The key is <ticket>/<file name, encoded>.
                return files.stream().filter(f -> fileName.equals(f.name())).findFirst()
                        .map(f -> "/raw-attachment/ticket/" + f.key()).orElse(null);
            }

            @Override
            public String web() {
                return api.web.toString();
            }
        });
    }

    /**
     * A person as Trac writes them: a username, an e-mail address, or {@code Name <e-mail>}
     * (tickets of people who were not logged in). Trac's API tells nothing more about users.
     */
    private SourceUser user(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String id = value.strip();
        return users.computeIfAbsent(id, v -> {
            String displayName = null;
            String email = null;
            Matcher m = NAME_AND_EMAIL.matcher(v);
            if (m.matches()) {
                displayName = blankToNull(m.group(1).strip());
                email = m.group(2);
            } else if (v.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                email = v;
            }
            String login = email != null ? email.substring(0, email.indexOf('@')) : v;
            return new SourceUser(v, login.toLowerCase(Locale.ROOT).replaceAll("\\s+", "."), displayName, email);
        });
    }

    /** Lists the user may not read (e.g. without MILESTONE_VIEW): empty instead of failing. */
    private List<Object> optionalList(String method, Object... params) {
        try {
            return api.list(method, params);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.BAD_GATEWAY && "import.rpc-fault".equals(e.messageKey())) {
                return List.of();
            }
            throw e;
        }
    }

    private static Set<String> words(String value) {
        return value == null || value.isBlank() ? new LinkedHashSet<>()
                : new LinkedHashSet<>(Arrays.stream(value.strip().split("[\\s,]+")).filter(w -> !w.isBlank())
                        .toList());
    }

    private static List<String> numbers(String value) {
        List<String> found = new ArrayList<>();
        if (value != null) {
            Matcher m = NUMBERS.matcher(value);
            while (m.find()) {
                found.add(m.group());
            }
        }
        return found;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
