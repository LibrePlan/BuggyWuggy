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

public interface ProjectRoleRepository extends JpaRepository<ProjectRole, Long> {

    @Query("select r from ProjectRole r order by lower(r.name)")
    List<ProjectRole> findAllOrdered();

    @Query("select r from ProjectRole r where r.name = :name")
    Optional<ProjectRole> findByName(String name);
}
