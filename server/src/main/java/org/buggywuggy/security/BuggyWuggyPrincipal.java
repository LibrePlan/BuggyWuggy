/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import java.io.Serial;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated user, stored in the HTTP session. Global authorities are fixed for the
 * lifetime of the session; when they change, the user's sessions are invalidated
 * (see {@link SessionInvalidator}). Project permissions are not stored here; they are
 * evaluated per request.
 */
public final class BuggyWuggyPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final String displayName;
    private final boolean enabled;
    private final Set<GrantedAuthority> authorities;
    private String password;

    public BuggyWuggyPrincipal(long id, String username, String displayName, String password, boolean enabled,
            Collection<String> authorities) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.enabled = enabled;
        this.authorities = authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toUnmodifiableSet());
    }

    public long id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean hasAuthority(String authority) {
        return authorities.stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    public boolean isSystemAdmin() {
        return hasAuthority(Authorities.SYSTEM_ADMIN);
    }

    public Set<String> globalPermissions() {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Authorities.GLOBAL_PERMISSIONS::contains)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BuggyWuggyPrincipal other && other.id == id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "BuggyWuggyPrincipal[id=" + id + ", username=" + username + "]";
    }
}
