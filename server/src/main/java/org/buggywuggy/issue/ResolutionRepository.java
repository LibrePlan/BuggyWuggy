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

public interface ResolutionRepository extends JpaRepository<Resolution, Long> {

    @Query("select x from Resolution x order by x.position, x.id")
    List<Resolution> findAllOrdered();

    @Query("select x from Resolution x where x.isDefault = true")
    Optional<Resolution> findDefault();
}
