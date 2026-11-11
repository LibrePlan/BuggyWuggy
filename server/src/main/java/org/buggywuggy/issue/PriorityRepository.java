/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PriorityRepository extends JpaRepository<Priority, Long> {

    @Query("select x from Priority x order by x.position, x.id")
    List<Priority> findAllOrdered();

    @Query("select x from Priority x where x.isDefault = true")
    Optional<Priority> findDefault();
}
