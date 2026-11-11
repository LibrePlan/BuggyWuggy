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

public interface ComponentRepository extends JpaRepository<ProjectComponent, Long> {

    @Query("select c from ProjectComponent c left join fetch c.lead where c.projectId = :projectId order by lower(c.name)")
    List<ProjectComponent> findByProject(long projectId);

    @Query("select c from ProjectComponent c where c.id = :id and c.projectId = :projectId")
    Optional<ProjectComponent> findInProject(long id, long projectId);

    @Query("""
            select count(c) > 0 from ProjectComponent c
            where c.projectId = :projectId and lower(c.name) = lower(:name) and c.id <> :excludeId""")
    boolean existsByNameExcluding(long projectId, String name, long excludeId);
}
