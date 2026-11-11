/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    // lower() matches the case-insensitive unique indexes, so these lookups use them.

    @Query("select u from UserAccount u where lower(u.username) = lower(:username)")
    Optional<UserAccount> findByUsername(String username);

    @Query("select count(u) > 0 from UserAccount u where lower(u.username) = lower(:username)")
    boolean existsByUsername(String username);

    @Query("select count(u) > 0 from UserAccount u where lower(u.email) = lower(:email) and u.id <> :excludeId")
    boolean existsByEmailExcluding(String email, long excludeId);

    @Query("select count(u) from UserAccount u where u.systemAdmin = true and u.active = true and u.placeholder = false")
    long countActiveSystemAdmins();

    @Query("select u from UserAccount u where u.placeholder = false order by lower(u.username)")
    Page<UserAccount> findAllRegular(Pageable pageable);

    /** {@code pattern} is a LIKE pattern on lower-cased values, using {@code \} as escape character. */
    @Query("""
            select u from UserAccount u
            where u.placeholder = false
              and (lower(u.username) like :pattern escape '\\'
                   or lower(u.displayName) like :pattern escape '\\'
                   or lower(u.email) like :pattern escape '\\')
            order by lower(u.username)""")
    Page<UserAccount> searchRegular(String pattern, Pageable pageable);

    @Query("select u from UserAccount u join u.groups g where g.id = :groupId order by lower(u.username)")
    Page<UserAccount> findByGroup(long groupId, Pageable pageable);

    /** Bulk update, so a login does not bump the optimistic-locking version. */
    @Modifying
    @Query("update UserAccount u set u.lastLoginAt = :at where u.id = :id")
    void updateLastLogin(long id, Instant at);
}
