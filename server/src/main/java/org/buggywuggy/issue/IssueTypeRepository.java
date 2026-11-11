/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IssueTypeRepository extends JpaRepository<IssueType, Long> {

    @Query("select x from IssueType x order by x.position, x.id")
    List<IssueType> findAllOrdered();
}
