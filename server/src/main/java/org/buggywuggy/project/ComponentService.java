/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.notFound;

import java.util.List;
import org.buggywuggy.project.ProjectDtos.ComponentResponse;
import org.buggywuggy.project.ProjectDtos.CreateComponentRequest;
import org.buggywuggy.project.ProjectDtos.UpdateComponentRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ComponentService {

    private final ProjectService projects;
    private final ComponentRepository components;
    private final UserService users;

    public ComponentService(ProjectService projects, ComponentRepository components, UserService users) {
        this.projects = projects;
        this.components = components;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<ComponentResponse> list(BuggyWuggyPrincipal user, String projectIdOrKey) {
        ProjectAccess access = projects.access(user, projectIdOrKey);
        return components.findByProject(access.projectId()).stream().map(ComponentResponse::of).toList();
    }

    public ComponentResponse create(BuggyWuggyPrincipal user, String projectIdOrKey, CreateComponentRequest request) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        String name = request.name().trim();
        requireNameAvailable(access.projectId(), name, -1);

        ProjectComponent component = new ProjectComponent(access.projectId(), name);
        component.setDescription(blankToNull(request.description()));
        if (request.leadId() != null) {
            component.setLead(users.requireActiveUser(request.leadId(), "component.lead-invalid"));
        }
        if (request.defaultAssignee() != null) {
            component.setDefaultAssignee(request.defaultAssignee());
        }
        validate(component);
        return ComponentResponse.of(components.save(component));
    }

    public ComponentResponse update(BuggyWuggyPrincipal user, String projectIdOrKey, long componentId,
            UpdateComponentRequest request) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        ProjectComponent component = find(access, componentId);
        if (request.name() != null) {
            String name = request.name().trim();
            requireNameAvailable(access.projectId(), name, componentId);
            component.setName(name);
        }
        if (request.description() != null) {
            component.setDescription(blankToNull(request.description()));
        }
        if (Boolean.TRUE.equals(request.clearLead())) {
            component.setLead(null);
        } else if (request.leadId() != null) {
            component.setLead(users.requireActiveUser(request.leadId(), "component.lead-invalid"));
        }
        if (request.defaultAssignee() != null) {
            component.setDefaultAssignee(request.defaultAssignee());
        }
        validate(component);
        return ComponentResponse.of(component);
    }

    public void delete(BuggyWuggyPrincipal user, String projectIdOrKey, long componentId) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        components.delete(find(access, componentId));
    }

    private ProjectComponent find(ProjectAccess access, long componentId) {
        return components.findInProject(componentId, access.projectId())
                .orElseThrow(() -> notFound("component.not-found", componentId));
    }

    private void requireNameAvailable(long projectId, String name, long componentId) {
        if (components.existsByNameExcluding(projectId, name, componentId)) {
            throw conflict("component.name-taken", name);
        }
    }

    private static void validate(ProjectComponent component) {
        if (component.getDefaultAssignee() == ProjectComponent.DefaultAssignee.COMPONENT_LEAD
                && component.getLead() == null) {
            throw badRequest("component.lead-required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
