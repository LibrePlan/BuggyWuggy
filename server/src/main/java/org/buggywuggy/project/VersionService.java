/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.notFound;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.buggywuggy.project.ProjectDtos.CreateVersionRequest;
import org.buggywuggy.project.ProjectDtos.UpdateVersionRequest;
import org.buggywuggy.project.ProjectDtos.VersionResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VersionService {

    private final ProjectService projects;
    private final VersionRepository versions;

    public VersionService(ProjectService projects, VersionRepository versions) {
        this.projects = projects;
        this.versions = versions;
    }

    @Transactional(readOnly = true)
    public List<VersionResponse> list(BuggyWuggyPrincipal user, String projectIdOrKey) {
        ProjectAccess access = projects.access(user, projectIdOrKey);
        return versions.findByProject(access.projectId()).stream().map(VersionResponse::of).toList();
    }

    /** New versions are added at the end of the list. */
    public VersionResponse create(BuggyWuggyPrincipal user, String projectIdOrKey, CreateVersionRequest request) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        String name = request.name().trim();
        requireNameAvailable(access.projectId(), name, -1);

        ProjectVersion version = new ProjectVersion(access.projectId(), name,
                versions.maxPosition(access.projectId()) + 1);
        version.setDescription(blankToNull(request.description()));
        version.setStartDate(request.startDate());
        version.setReleaseDate(request.releaseDate());
        version.setReleased(Boolean.TRUE.equals(request.released()));
        validateDates(version);
        return VersionResponse.of(versions.save(version));
    }

    public VersionResponse update(BuggyWuggyPrincipal user, String projectIdOrKey, long versionId,
            UpdateVersionRequest request) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        ProjectVersion version = find(access, versionId);
        if (request.name() != null) {
            String name = request.name().trim();
            requireNameAvailable(access.projectId(), name, versionId);
            version.setName(name);
        }
        if (request.description() != null) {
            version.setDescription(blankToNull(request.description()));
        }
        if (Boolean.TRUE.equals(request.clearStartDate())) {
            version.setStartDate(null);
        } else if (request.startDate() != null) {
            version.setStartDate(request.startDate());
        }
        if (Boolean.TRUE.equals(request.clearReleaseDate())) {
            version.setReleaseDate(null);
        } else if (request.releaseDate() != null) {
            version.setReleaseDate(request.releaseDate());
        }
        if (request.released() != null) {
            version.setReleased(request.released());
        }
        if (request.archived() != null) {
            version.setArchived(request.archived());
        }
        validateDates(version);
        return VersionResponse.of(version);
    }

    public void delete(BuggyWuggyPrincipal user, String projectIdOrKey, long versionId) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        versions.delete(find(access, versionId));
    }

    /** Sets the order of all versions of the project; {@code versionIds} must list each exactly once. */
    public List<VersionResponse> reorder(BuggyWuggyPrincipal user, String projectIdOrKey, List<Long> versionIds) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        Map<Long, ProjectVersion> byId = versions.findByProject(access.projectId()).stream()
                .collect(Collectors.toMap(ProjectVersion::getId, Function.identity()));
        if (versionIds.size() != byId.size() || !byId.keySet().equals(new HashSet<>(versionIds))) {
            throw badRequest("version.order-mismatch");
        }
        for (int i = 0; i < versionIds.size(); i++) {
            byId.get(versionIds.get(i)).setPosition(i + 1);
        }
        return versionIds.stream().map(byId::get).map(VersionResponse::of).toList();
    }

    private ProjectVersion find(ProjectAccess access, long versionId) {
        return versions.findInProject(versionId, access.projectId())
                .orElseThrow(() -> notFound("version.not-found", versionId));
    }

    private void requireNameAvailable(long projectId, String name, long versionId) {
        if (versions.existsByNameExcluding(projectId, name, versionId)) {
            throw conflict("version.name-taken", name);
        }
    }

    private static void validateDates(ProjectVersion v) {
        if (v.getStartDate() != null && v.getReleaseDate() != null && v.getReleaseDate().isBefore(v.getStartDate())) {
            throw badRequest("version.dates-invalid");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
