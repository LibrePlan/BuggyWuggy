/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import org.buggywuggy.attachment.AttachmentFiles;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.common.SqlLike;
import org.buggywuggy.project.ProjectDtos.CreateProjectRequest;
import org.buggywuggy.project.ProjectDtos.ProjectResponse;
import org.buggywuggy.project.ProjectDtos.UpdateProjectRequest;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.user.UserGroupRepository;
import org.buggywuggy.user.UserService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProjectService {

    private static final java.util.regex.Pattern NUMERIC = java.util.regex.Pattern.compile("\\d{1,18}");

    private final ProjectRepository projects;
    private final ProjectRoleRepository roles;
    private final ProjectRoleActorRepository actors;
    private final UserGroupRepository groups;
    private final UserService users;
    private final ProjectPermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final AttachmentFiles attachmentFiles;

    public ProjectService(ProjectRepository projects, ProjectRoleRepository roles, ProjectRoleActorRepository actors,
            UserGroupRepository groups, UserService users, ProjectPermissionService permissions,
            NamedParameterJdbcTemplate jdbc, Clock clock, AttachmentFiles attachmentFiles) {
        this.projects = projects;
        this.roles = roles;
        this.actors = actors;
        this.groups = groups;
        this.users = users;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.attachmentFiles = attachmentFiles;
    }

    /**
     * Resolves a project by id, key or former key, together with the user's permissions.
     * Projects the user may neither browse nor administer are reported as not found, so their
     * existence is not revealed.
     */
    @Transactional(readOnly = true)
    public ProjectAccess access(BuggyWuggyPrincipal user, String idOrKey) {
        Project project = find(idOrKey).orElseThrow(() -> notFound("project.not-found", idOrKey));
        return access(user, project, idOrKey);
    }

    /** As {@link #access(BuggyWuggyPrincipal, String)}, for an already loaded project. */
    @Transactional(readOnly = true)
    public ProjectAccess access(BuggyWuggyPrincipal user, Project project, String requestedAs) {
        Set<ProjectPermission> granted = permissions.permissionsOf(user, project.getId());
        if (!granted.contains(ProjectPermission.BROWSE_PROJECT)
                && !granted.contains(ProjectPermission.ADMINISTER_PROJECT)) {
            throw notFound("project.not-found", requestedAs);
        }
        return new ProjectAccess(project, user, granted);
    }

    /** Finds a project by numeric id, current key or former key, without any permission check. */
    @Transactional(readOnly = true)
    public Optional<Project> find(String idOrKey) {
        if (NUMERIC.matcher(idOrKey).matches()) {
            return projects.findById(Long.parseLong(idOrKey));
        }
        String key = idOrKey.toUpperCase(Locale.ROOT);
        return projects.findByKey(key).or(() -> jdbc.queryForList(
                        "select project_id from project_key_history where old_key = :key",
                        Map.of("key", key), Long.class)
                .stream().findFirst().flatMap(projects::findById));
    }

    /** Projects the user can browse (global administrators see all projects). */
    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> list(BuggyWuggyPrincipal user, String query, boolean includeArchived,
            int page, int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", user.id());
        StringBuilder where = new StringBuilder(" where true");
        if (!user.hasAuthority(Authorities.ADMINISTER)) {
            where.append(" and ").append(ProjectPermissionService.PROJECT_BROWSABLE);
        }
        if (!includeArchived) {
            where.append(" and not p.archived");
        }
        if (query != null && !query.isBlank()) {
            where.append(" and (lower(p.name) like :pattern escape '\\' or lower(p.key) like :pattern escape '\\')");
            params.put("pattern", SqlLike.containsPattern(query));
        }

        Long total = jdbc.queryForObject("select count(*) from project p" + where, params, Long.class);
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Long> ids = jdbc.queryForList(
                "select p.id from project p" + where + " order by lower(p.name), p.id limit :limit offset :offset",
                params, Long.class);

        Map<Long, Project> byId = projects.findAllWithLeadByIdIn(ids).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity()));
        List<ProjectResponse> items = ids.stream().map(byId::get).map(ProjectResponse::of).toList();
        return new PageResponse<>(items, page, size, total == null ? 0 : total);
    }

    /**
     * The keys of all projects the user may browse, archived ones included: the web interface
     * links issue keys in texts (BUG-12) only for these, not for look-alikes such as UTF-8.
     */
    @Transactional(readOnly = true)
    public List<String> keys(BuggyWuggyPrincipal user) {
        String where = user.hasAuthority(Authorities.ADMINISTER) ? ""
                : " where " + ProjectPermissionService.PROJECT_BROWSABLE;
        return jdbc.queryForList("select p.key from project p" + where + " order by p.key",
                Map.of("userId", user.id()), String.class);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(BuggyWuggyPrincipal user, String idOrKey) {
        return ProjectResponse.of(access(user, idOrKey).project());
    }

    @Transactional(readOnly = true)
    public Set<ProjectPermission> myPermissions(BuggyWuggyPrincipal user, String idOrKey) {
        return access(user, idOrKey).permissions();
    }

    /** Active users who may be assigned issues in the project, optionally filtered by name or e-mail. */
    @Transactional(readOnly = true)
    public List<UserRef> assignableUsers(BuggyWuggyPrincipal user, String idOrKey, String query, int limit) {
        ProjectAccess access = access(user, idOrKey).require(ProjectPermission.BROWSE_PROJECT);
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", access.projectId());
        params.put("limit", limit);
        String filter = "";
        if (query != null && !query.isBlank()) {
            filter = """
                     and (lower(u.username) like :pattern escape '\\'
                          or lower(u.display_name) like :pattern escape '\\'
                          or lower(u.email) like :pattern escape '\\')""";
            params.put("pattern", SqlLike.containsPattern(query));
        }
        return jdbc.query("""
                select u.id, u.username, u.display_name from user_account u
                where u.active and not u.placeholder
                  and exists (select 1 from project p
                              join permission_grant pg on pg.permission_scheme_id = p.permission_scheme_id
                              where p.id = :projectId and pg.permission = 'ASSIGNABLE_USER'
                                and """ + ProjectPermissionService.GRANT_APPLIES_TO_USER.replace(":userId", "u.id") + ")"
                + filter + " order by lower(u.display_name), u.id limit :limit",
                params, (rs, n) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public ProjectResponse create(BuggyWuggyPrincipal user, CreateProjectRequest request) {
        if (!user.hasAuthority(Authorities.CREATE_PROJECT)) {
            throw forbidden("project.create-permission-required");
        }
        String key = request.key().toUpperCase(Locale.ROOT);
        String name = request.name().trim();
        requireKeyAvailable(key, null);
        requireNameAvailable(name, -1);
        UserAccount lead = users.requireActiveUser(
                request.leadId() != null ? request.leadId() : user.id(), "project.lead-invalid");

        Project project = new Project(key, name, lead, defaultSchemeId("permission_scheme"),
                defaultSchemeId("workflow_scheme"), clock.instant());
        project.setDescription(blankToNull(request.description()));
        if (request.defaultAssignee() != null) {
            project.setDefaultAssignee(request.defaultAssignee());
        }
        projects.save(project);
        addDefaultRoleActors(project);
        return ProjectResponse.of(project);
    }

    public ProjectResponse update(BuggyWuggyPrincipal user, String idOrKey, UpdateProjectRequest request) {
        ProjectAccess access = access(user, idOrKey).require(ProjectPermission.ADMINISTER_PROJECT);
        Project project = access.project();
        boolean globalAdmin = user.hasAuthority(Authorities.ADMINISTER);

        boolean archiveRequested = request.archived() != null && request.archived() != project.isArchived();
        if (archiveRequested && !globalAdmin) {
            throw forbidden("project.global-admin-required");
        }
        boolean archivedAfterwards = archiveRequested ? request.archived() : project.isArchived();
        if (project.isArchived() && archivedAfterwards && changesConfiguration(request)) {
            throw conflict("project.archived", project.getProjectKey());
        }

        if (request.key() != null) {
            changeKey(project, request.key().toUpperCase(Locale.ROOT), globalAdmin);
        }
        if (request.name() != null) {
            String name = request.name().trim();
            requireNameAvailable(name, project.getId());
            project.setName(name);
        }
        if (request.description() != null) {
            project.setDescription(blankToNull(request.description()));
        }
        if (request.leadId() != null) {
            project.setLead(users.requireActiveUser(request.leadId(), "project.lead-invalid"));
        }
        if (request.defaultAssignee() != null) {
            project.setDefaultAssignee(request.defaultAssignee());
        }
        if (archiveRequested) {
            project.setArchived(request.archived());
        }
        return ProjectResponse.of(project);
    }

    /** Deletes a project including all its issues. Only for global administrators. */
    public void delete(BuggyWuggyPrincipal user, String idOrKey) {
        Project project = access(user, idOrKey).project();
        if (!user.hasAuthority(Authorities.ADMINISTER)) {
            throw forbidden("project.global-admin-required");
        }
        attachmentFiles.deleteAfterCommit(attachmentFiles.keysOfProject(project.getId()));
        projects.delete(project);
    }

    // --- helpers ---------------------------------------------------------------------

    private void changeKey(Project project, String newKey, boolean globalAdmin) {
        String oldKey = project.getProjectKey();
        if (newKey.equals(oldKey)) {
            return;
        }
        if (!globalAdmin) {
            throw forbidden("project.global-admin-required");
        }
        requireKeyAvailable(newKey, project.getId());
        Map<String, Object> params = Map.of("oldKey", oldKey, "newKey", newKey, "projectId", project.getId());
        // Switching back to a former key: it is current again, so remove it from the history.
        jdbc.update("delete from project_key_history where old_key = :newKey and project_id = :projectId", params);
        jdbc.update("insert into project_key_history (old_key, project_id) values (:oldKey, :projectId)", params);
        project.setProjectKey(newKey);
    }

    /** A key is taken if another project uses it now or used it before (old issue keys must keep working). */
    private void requireKeyAvailable(String key, Long projectId) {
        Long owner = jdbc.queryForList("""
                select id from project where key = :key
                union all
                select project_id from project_key_history where old_key = :key""",
                Map.of("key", key), Long.class).stream().findFirst().orElse(null);
        if (owner != null && !owner.equals(projectId)) {
            throw conflict("project.key-taken", key);
        }
    }

    private void requireNameAvailable(String name, long projectId) {
        if (projects.existsByNameExcluding(name, projectId)) {
            throw conflict("project.name-taken", name);
        }
    }

    private long defaultSchemeId(String table) {
        return jdbc.queryForObject("select id from " + table + " where is_default", Map.of(), Long.class);
    }

    private void addDefaultRoleActors(Project project) {
        jdbc.query("select project_role_id, group_id from project_role_default_actor", Map.of(), rs -> {
            actors.save(ProjectRoleActor.ofGroup(project.getId(), rs.getLong("project_role_id"),
                    groups.getReferenceById(rs.getLong("group_id"))));
        });
        roles.findByName(ProjectRole.ADMINISTRATORS).ifPresent(role ->
                actors.save(ProjectRoleActor.ofUser(project.getId(), role.getId(), project.getLead())));
    }

    private static boolean changesConfiguration(UpdateProjectRequest r) {
        return r.key() != null || r.name() != null || r.description() != null || r.leadId() != null
                || r.defaultAssignee() != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
