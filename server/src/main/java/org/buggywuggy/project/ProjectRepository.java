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

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("select p from Project p join fetch p.lead where p.projectKey = :key")
    Optional<Project> findByKey(String key);

    @Query("select p from Project p join fetch p.lead where p.id in :ids")
    List<Project> findAllWithLeadByIdIn(List<Long> ids);

    @Query("select count(p) > 0 from Project p where lower(p.name) = lower(:name) and p.id <> :excludeId")
    boolean existsByNameExcluding(String name, long excludeId);
}
