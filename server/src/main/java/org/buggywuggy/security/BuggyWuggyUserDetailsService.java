/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import java.util.HashSet;
import java.util.Set;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserAccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuggyWuggyUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;
    private final JdbcTemplate jdbc;

    public BuggyWuggyUserDetailsService(UserAccountRepository users, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public BuggyWuggyPrincipal loadUserByUsername(String username) {
        UserAccount user = users.findByUsername(username)
                .filter(u -> !u.isPlaceholder() && u.getPasswordHash() != null)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return new BuggyWuggyPrincipal(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getPasswordHash(), user.isActive(), authoritiesOf(user));
    }

    private Set<String> authoritiesOf(UserAccount user) {
        Set<String> authorities = new HashSet<>();
        if (user.isSystemAdmin()) {
            authorities.add(Authorities.SYSTEM_ADMIN);
            authorities.addAll(Authorities.GLOBAL_PERMISSIONS);
        } else {
            authorities.addAll(jdbc.queryForList("""
                    select distinct g.permission
                    from global_permission_grant g
                    join group_membership m on m.group_id = g.group_id
                    where m.user_id = ?""", String.class, user.getId()));
        }
        return authorities;
    }
}
