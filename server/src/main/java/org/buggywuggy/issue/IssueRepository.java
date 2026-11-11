/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IssueRepository extends JpaRepository<Issue, Long> {

    @Query("select i from Issue i where i.projectId = :projectId and i.number = :number")
    Optional<Issue> findByProjectAndNumber(long projectId, long number);

    @Query(value = """
            select i from Issue i
            join fetch i.issueType join fetch i.status left join fetch i.priority left join fetch i.assignee
            where i.projectId = :projectId
            order by i.number desc""",
            countQuery = "select count(i) from Issue i where i.projectId = :projectId")
    Page<Issue> findByProject(long projectId, Pageable pageable);
}
