/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRoleActorRepository extends JpaRepository<ProjectRoleActor, Long> {

    @Query("""
            select a from ProjectRoleActor a left join fetch a.user left join fetch a.group
            where a.projectId = :projectId""")
    List<ProjectRoleActor> findByProject(long projectId);

    @Query("""
            select a from ProjectRoleActor a
            where a.projectId = :projectId and a.projectRoleId = :roleId and a.user.id = :userId""")
    Optional<ProjectRoleActor> findUserActor(long projectId, long roleId, long userId);

    @Query("""
            select a from ProjectRoleActor a
            where a.projectId = :projectId and a.projectRoleId = :roleId and a.group.id = :groupId""")
    Optional<ProjectRoleActor> findGroupActor(long projectId, long roleId, long groupId);
}
