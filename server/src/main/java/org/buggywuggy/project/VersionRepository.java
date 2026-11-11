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

public interface VersionRepository extends JpaRepository<ProjectVersion, Long> {

    @Query("select v from ProjectVersion v where v.projectId = :projectId order by v.position, v.id")
    List<ProjectVersion> findByProject(long projectId);

    @Query("select v from ProjectVersion v where v.id = :id and v.projectId = :projectId")
    Optional<ProjectVersion> findInProject(long id, long projectId);

    @Query("""
            select count(v) > 0 from ProjectVersion v
            where v.projectId = :projectId and lower(v.name) = lower(:name) and v.id <> :excludeId""")
    boolean existsByNameExcluding(long projectId, String name, long excludeId);

    @Query("select coalesce(max(v.position), 0) from ProjectVersion v where v.projectId = :projectId")
    int maxPosition(long projectId);
}
