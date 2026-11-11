/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.notFound;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.issue.CollaborationDtos.CreateLinkRequest;
import org.buggywuggy.issue.CollaborationDtos.IssueLinkResponse;
import org.buggywuggy.issue.CollaborationDtos.LinkDirection;
import org.buggywuggy.issue.CollaborationDtos.LinkTypeResponse;
import org.buggywuggy.issue.CollaborationDtos.LinkedIssueRef;
import org.buggywuggy.issue.IssueDtos.IssueTypeRef;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links between issues, possibly across projects. A link is stored once (source → target) and
 * shown from both ends. Adding or removing a link is recorded in the history of both issues,
 * like Jira does, so imported histories look the same.
 */
@Service
@Transactional
public class IssueLinkService {

    private final IssueService issues;
    private final IssueFieldStore fields;
    private final IssueHistory history;
    private final ProjectPermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public IssueLinkService(IssueService issues, IssueFieldStore fields, IssueHistory history,
            ProjectPermissionService permissions, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.issues = issues;
        this.fields = fields;
        this.history = history;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LinkTypeResponse> linkTypes() {
        return jdbc.query("select id, name, outward_description, inward_description from issue_link_type order by lower(name)",
                (rs, n) -> new LinkTypeResponse(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    /** Links of an issue. Links to issues the user cannot browse are left out. */
    @Transactional(readOnly = true)
    public List<IssueLinkResponse> list(BuggyWuggyPrincipal user, String issueIdOrKey) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        Map<Long, Boolean> browsable = new HashMap<>();
        browsable.put(access.project().projectId(), true);
        return query(access.issue().getId(), null).stream()
                .filter(l -> browsable.computeIfAbsent(l.projectId(), projectId ->
                        permissions.permissionsOf(user, projectId).contains(ProjectPermission.BROWSE_PROJECT)))
                .map(Row::response)
                .toList();
    }

    public IssueLinkResponse create(BuggyWuggyPrincipal user, String issueIdOrKey, CreateLinkRequest request) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        access.project().require(ProjectPermission.LINK_ISSUE);
        access.requireWritable();
        IssueAccess other;
        try {
            other = issues.access(user, request.issue());
        } catch (ApiException e) {
            if (e.status() == HttpStatus.NOT_FOUND) {
                throw badRequest("link.issue-not-found", request.issue());
            }
            throw e;
        }
        long thisId = access.issue().getId();
        long otherId = other.issue().getId();
        if (thisId == otherId) {
            throw badRequest("link.self");
        }
        LinkTypeResponse type = linkTypes().stream().filter(t -> t.id() == request.linkTypeId()).findFirst()
                .orElseThrow(() -> badRequest("link.type-invalid", request.linkTypeId()));
        boolean outward = request.direction() != LinkDirection.INWARD;
        long sourceId = outward ? thisId : otherId;
        long targetId = outward ? otherId : thisId;

        Instant now = clock.instant();
        // "on conflict do nothing" returns no row when the link exists already.
        Long id = jdbc.queryForList("""
                insert into issue_link (link_type_id, source_issue_id, target_issue_id, created_at)
                values (:type, :source, :target, :now)
                on conflict on constraint issue_link_uk do nothing returning id""",
                new MapSqlParameterSource().addValue("type", type.id()).addValue("source", sourceId)
                        .addValue("target", targetId).addValue("now", Timestamp.from(now)),
                Long.class).stream().findFirst().orElseThrow(() -> conflict("link.exists"));
        recordHistory(user, type, thisId, access.key(), otherId, other.key(), outward, now, true);
        return query(thisId, id).getFirst().response();
    }

    public void delete(BuggyWuggyPrincipal user, String issueIdOrKey, long linkId) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        access.project().require(ProjectPermission.LINK_ISSUE);
        access.requireWritable();
        Row link = query(access.issue().getId(), linkId).stream().findFirst()
                .orElseThrow(() -> notFound("link.not-found", linkId));
        jdbc.update("delete from issue_link where id = :id", Map.of("id", linkId));
        recordHistory(user, link.response().type(), access.issue().getId(), access.key(),
                link.response().issue().id(), link.response().issue().key(),
                link.response().direction() == LinkDirection.OUTWARD, clock.instant(), false);
    }

    /**
     * Records the link in the history of both issues, with the phrase as seen from each:
     * "blocks BUG-2" on BUG-1 and "is blocked by BUG-1" on BUG-2.
     */
    private void recordHistory(BuggyWuggyPrincipal user, LinkTypeResponse type, long thisId, String thisKey,
            long otherId, String otherKey, boolean thisIsSource, Instant now, boolean added) {
        record(user, thisId, otherKey, thisIsSource ? type.outward() : type.inward(), now, added);
        record(user, otherId, thisKey, thisIsSource ? type.inward() : type.outward(), now, added);
    }

    private void record(BuggyWuggyPrincipal user, long issueId, String linkedKey, String phrase, Instant now,
            boolean added) {
        Changes changes = new Changes();
        String text = phrase + " " + linkedKey;
        if (added) {
            changes.record(Fields.LINK, null, null, linkedKey, text);
        } else {
            changes.record(Fields.LINK, linkedKey, text, null, null);
        }
        history.save(issueId, user.id(), now, changes);
        fields.touch(issueId, now);
    }

    private record Row(long projectId, IssueLinkResponse response) {
    }

    private List<Row> query(long issueId, Long linkId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("issue", issueId).addValue("link", linkId);
        return jdbc.query("""
                select l.id, t.id as type_id, t.name as type_name, t.outward_description, t.inward_description,
                       l.source_issue_id = :issue as outward,
                       o.id as other_id, o.number, o.summary, p.id as project_id, p.key as project_key,
                       it.name as type_name2, it.icon, it.hierarchy_level, it.id as issue_type_id,
                       s.id as status_id, s.name as status_name, s.category
                from issue_link l
                join issue_link_type t on t.id = l.link_type_id
                join issue o on o.id = case when l.source_issue_id = :issue then l.target_issue_id
                                            else l.source_issue_id end
                join project p on p.id = o.project_id
                join issue_type it on it.id = o.issue_type_id
                join status s on s.id = o.status_id
                where (l.source_issue_id = :issue or l.target_issue_id = :issue)
                  and (cast(:link as bigint) is null or l.id = :link)
                order by lower(t.name), outward desc, p.key, o.number""", params, (rs, n) -> {
            LinkTypeResponse type = new LinkTypeResponse(rs.getLong("type_id"), rs.getString("type_name"),
                    rs.getString("outward_description"), rs.getString("inward_description"));
            boolean outward = rs.getBoolean("outward");
            LinkedIssueRef other = new LinkedIssueRef(rs.getLong("other_id"),
                    rs.getString("project_key") + "-" + rs.getLong("number"), rs.getString("summary"),
                    new IssueTypeRef(rs.getLong("issue_type_id"), rs.getString("type_name2"), rs.getString("icon"),
                            rs.getInt("hierarchy_level")),
                    new StatusRef(rs.getLong("status_id"), rs.getString("status_name"),
                            Status.Category.valueOf(rs.getString("category"))));
            return new Row(rs.getLong("project_id"), new IssueLinkResponse(rs.getLong("id"), type,
                    outward ? LinkDirection.OUTWARD : LinkDirection.INWARD,
                    outward ? type.outward() : type.inward(), other));
        });
    }
}
