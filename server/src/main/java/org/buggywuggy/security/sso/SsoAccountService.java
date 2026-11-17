/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.buggywuggy.security.sso.SsoProperties.GroupMapping;
import org.buggywuggy.security.sso.SsoProperties.LinkBy;
import org.buggywuggy.security.sso.SsoProperties.Provider;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserDtos;
import org.buggywuggy.user.UserGroup;
import org.buggywuggy.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the BuggyWuggy account of someone who logged in at an identity provider: through an
 * earlier login, by linking an existing account, or by creating one, as the provider's
 * settings allow. Also keeps the mapped group memberships in line with the provider.
 */
@Service
public class SsoAccountService {

    private static final Logger log = LoggerFactory.getLogger(SsoAccountService.class);
    private static final Pattern USERNAME = Pattern.compile(UserDtos.USERNAME_PATTERN);

    /** Why a login was refused; {@code code} is a message key of the web UI. */
    public static class SsoException extends RuntimeException {

        private final String code;

        SsoException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record Account(long id, String username, String email, boolean active, boolean placeholder) {
    }

    private final SsoProperties properties;
    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final Clock clock;

    public SsoAccountService(SsoProperties properties, JdbcTemplate jdbc, UserService userService, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.userService = userService;
        this.clock = clock;
    }

    /** The id of the account for {@code user}, who logged in at provider {@code providerId}. */
    @Transactional
    public long resolve(String providerId, OidcUser user) {
        Provider provider = properties.providers().get(providerId);
        if (provider == null) {
            throw new SsoException("sso.unknown-provider", "Unknown provider " + providerId);
        }
        String subject = user.getSubject();
        String email = blankToNull(user.getEmail());
        boolean emailVerified = Boolean.TRUE.equals(user.getEmailVerified());
        String who = providerId + " user " + subject;

        Account account = linked(providerId, subject).orElse(null);
        if (account == null) {
            account = link(provider, providerId, user, email, emailVerified, who)
                    .or(() -> create(provider, providerId, user, email, who))
                    .orElseThrow(() -> new SsoException("sso.no-account", who + " has no BuggyWuggy account"));
        }
        if (!account.active()) {
            throw new SsoException("sso.inactive", who + " belongs to the deactivated account " + account.username());
        }
        jdbc.update("update user_identity set email = ?, last_login_at = ? where provider = ? and subject = ?",
                email, Timestamp.from(clock.instant()), providerId, subject);
        if (provider.syncGroups()) {
            syncGroups(provider, providerId, account, groups(user.getClaims().get(provider.groupsClaim())));
        }
        return account.id();
    }

    private Optional<Account> linked(String providerId, String subject) {
        return accounts("""
                select u.id, u.username, u.email, u.active, u.placeholder
                from user_identity i join user_account u on u.id = i.user_id
                where i.provider = ? and i.subject = ?""", providerId, subject);
    }

    /** An existing account (also a placeholder of imported issues) for a first login. */
    private Optional<Account> link(Provider provider, String providerId, OidcUser user, String email,
            boolean emailVerified, String who) {
        Optional<Account> found = switch (provider.linkUsersBy()) {
            case NONE -> Optional.empty();
            case EMAIL -> {
                if (email == null) {
                    yield Optional.empty();
                }
                Optional<Account> byEmail = accounts("""
                        select id, username, email, active, placeholder from user_account
                        where lower(email) = lower(?)""", email);
                if (byEmail.isPresent() && !emailVerified) {
                    throw new SsoException("sso.email-not-verified",
                            who + " has the unverified e-mail address of account " + byEmail.get().username());
                }
                yield byEmail;
            }
            case USERNAME -> {
                String username = blankToNull(user.getClaimAsString(provider.usernameClaim()));
                yield username == null ? Optional.empty() : accounts("""
                        select id, username, email, active, placeholder from user_account
                        where lower(username) = lower(?)""", username);
            }
        };
        if (found.isEmpty()) {
            return found;
        }
        Account account = found.get();
        Integer other = jdbc.queryForObject("select count(*) from user_identity where user_id = ? and provider = ?",
                Integer.class, account.id(), providerId);
        if (other != null && other > 0) {
            throw new SsoException("sso.already-linked",
                    who + ": account " + account.username() + " is linked to another " + providerId + " identity");
        }
        if (account.placeholder()) {
            // Someone from imported issues logs in: the placeholder becomes their account.
            jdbc.update("""
                    update user_account set placeholder = false, active = true, email = coalesce(email, ?),
                    display_name = coalesce(?, display_name) where id = ?""",
                    email, blankToNull(user.getFullName()), account.id());
            jdbc.update("""
                    insert into group_membership (group_id, user_id)
                    select id, ? from user_group where name = ? on conflict do nothing""",
                    account.id(), UserGroup.USERS);
            account = new Account(account.id(), account.username(), account.email(), true, false);
            log.info("Placeholder account {} claimed by {}", account.username(), who);
        }
        addIdentity(account.id(), providerId, user.getSubject(), email);
        log.info("Account {} linked to {} (by {})", account.username(), who,
                provider.linkUsersBy().name().toLowerCase());
        return Optional.of(account);
    }

    private Optional<Account> create(Provider provider, String providerId, OidcUser user, String email, String who) {
        if (!provider.createUsers()) {
            return Optional.empty();
        }
        String username = blankToNull(user.getClaimAsString(provider.usernameClaim()));
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new SsoException("sso.invalid-username",
                    who + " has no usable claim " + provider.usernameClaim() + ": " + username);
        }
        if (email == null) {
            throw new SsoException("sso.no-email", who + " has no e-mail address");
        }
        if (accounts("select id, username, email, active, placeholder from user_account where lower(username) = lower(?)",
                username).isPresent()) {
            throw new SsoException("sso.username-taken", who + ": the username " + username + " is taken");
        }
        if (accounts("select id, username, email, active, placeholder from user_account where lower(email) = lower(?)",
                email).isPresent()) {
            throw new SsoException("sso.email-taken", who + ": the e-mail address " + email + " is taken");
        }
        String name = Optional.ofNullable(blankToNull(user.getFullName())).orElse(username);
        UserAccount created = userService.createUser(username, name, email, null, false, List.of(UserGroup.USERS));
        addIdentity(created.getId(), providerId, user.getSubject(), email);
        log.info("Account {} created for {}", username, who);
        return Optional.of(new Account(created.getId(), username, email, true, false));
    }

    private void addIdentity(long userId, String providerId, String subject, String email) {
        jdbc.update("""
                insert into user_identity (user_id, provider, subject, email, created_at) values (?, ?, ?, ?, ?)""",
                userId, providerId, subject, email, Timestamp.from(clock.instant()));
    }

    /** Membership of each mapped BuggyWuggy group follows the provider; other groups are left alone. */
    private void syncGroups(Provider provider, String providerId, Account account, Set<String> idpGroups) {
        Set<String> managed = new LinkedHashSet<>();
        Set<String> wanted = new LinkedHashSet<>();
        for (GroupMapping m : provider.groupMapping()) {
            managed.add(m.group());
            if (idpGroups.contains(m.idpGroup())) {
                wanted.add(m.group());
            }
        }
        for (String group : managed) {
            List<Long> ids = jdbc.queryForList("select id from user_group where name = ?", Long.class, group);
            if (ids.isEmpty()) {
                log.warn("Single sign-on provider '{}' maps to the group '{}', which does not exist", providerId, group);
                continue;
            }
            long groupId = ids.getFirst();
            int changed = wanted.contains(group)
                    ? jdbc.update("insert into group_membership (group_id, user_id) values (?, ?) on conflict do nothing",
                            groupId, account.id())
                    : jdbc.update("delete from group_membership where group_id = ? and user_id = ?", groupId, account.id());
            if (changed > 0) {
                log.info("{} {} group {} (single sign-on provider {})", account.username(),
                        wanted.contains(group) ? "joined" : "left", group, providerId);
            }
        }
    }

    /** A groups claim: a list of names, or one name. */
    static Set<String> groups(Object claim) {
        Set<String> groups = new LinkedHashSet<>();
        if (claim instanceof Collection<?> values) {
            values.forEach(v -> groups.add(String.valueOf(v)));
        } else if (claim instanceof String value && !value.isBlank()) {
            groups.add(value);
        }
        return groups;
    }

    private Optional<Account> accounts(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getBoolean(5)), args).stream().findFirst();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
