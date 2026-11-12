/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserGroupRepository extends JpaRepository<UserGroup, Long> {

    @Query("select g from UserGroup g where lower(g.name) = lower(:name)")
    Optional<UserGroup> findByName(String name);

    @Query("select count(g) > 0 from UserGroup g where lower(g.name) = lower(:name) and g.id <> :excludeId")
    boolean existsByNameExcluding(String name, long excludeId);

    @Query(value = """
            select new org.buggywuggy.user.GroupResponse(g.id, g.name, g.description, size(g.members),
                case when lower(g.name) in ('administrators', 'users') then true else false end)
            from UserGroup g order by lower(g.name)""",
            countQuery = "select count(g) from UserGroup g")
    Page<GroupResponse> findAllWithMemberCount(Pageable pageable);

    /** Groups whose name matches a {@link org.buggywuggy.common.SqlLike} pattern. */
    @Query(value = """
            select new org.buggywuggy.user.GroupResponse(g.id, g.name, g.description, size(g.members),
                case when lower(g.name) in ('administrators', 'users') then true else false end)
            from UserGroup g where lower(g.name) like :pattern escape '\\' order by lower(g.name)""",
            countQuery = "select count(g) from UserGroup g where lower(g.name) like :pattern escape '\\'")
    Page<GroupResponse> searchWithMemberCount(String pattern, Pageable pageable);
}
