/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Single sign-on with OpenID Connect ({@code buggywuggy.security.sso.*}). Without providers,
 * BuggyWuggy has only its own password login. See docs/single-sign-on.md.
 *
 * @param passwordLogin who may still log in with a BuggyWuggy password
 * @param providers the identity providers by id; the id is part of the redirect URI
 *        {@code <base-url>/login/oauth2/code/<id>}
 */
@ConfigurationProperties("buggywuggy.security.sso")
public record SsoProperties(@DefaultValue("all") PasswordLogin passwordLogin, Map<String, Provider> providers) {

    static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    public SsoProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        providers.forEach((id, provider) -> provider.validate(id));
        if (passwordLogin != PasswordLogin.ALL && providers.isEmpty()) {
            throw new IllegalStateException("buggywuggy.security.sso.password-login is " + passwordLogin
                    + ", but no single sign-on provider is configured: nobody could log in");
        }
    }

    public boolean enabled() {
        return !providers.isEmpty();
    }

    /** Who may log in with a password besides single sign-on. */
    public enum PasswordLogin {
        /** Everyone with a password. */
        ALL,
        /** Only system administrators, e.g. as emergency access when the identity provider is down. */
        SYSTEM_ADMINS,
        /** Nobody: single sign-on only. */
        NONE
    }

    /** How an identity that logs in for the first time is matched with an existing account. */
    public enum LinkBy {
        /** Not at all: the account must have been linked before, or is created. */
        NONE,
        /** The e-mail address, when the provider says it is verified. */
        EMAIL,
        /** The username (the claim {@code username-claim}). */
        USERNAME
    }

    /**
     * One OpenID Connect provider.
     *
     * @param displayName shown on the login button: "Log in with ..."
     * @param issuerUri the issuer; its {@code /.well-known/openid-configuration} describes the rest
     * @param clientSecret empty for a public client (then PKCE alone protects the code)
     * @param usernameClaim the claim that becomes the username of a new account
     * @param createUsers create an account at the first login of someone without one; false in
     *        high-security installations, where an administrator creates every account first
     * @param linkUsersBy how a first login finds an existing account (also placeholder accounts
     *        of imported issues, which then become regular accounts)
     * @param groupsClaim the claim with the user's groups at the provider; empty: groups are not
     *        synchronised
     * @param groupMapping provider group to BuggyWuggy group; at every login, membership of the
     *        BuggyWuggy groups named here follows the provider
     * @param logoutAtProvider logging out of BuggyWuggy also logs out at the provider
     */
    public record Provider(
            String displayName,
            String issuerUri,
            String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue({"openid", "profile", "email"}) List<String> scopes,
            @DefaultValue("preferred_username") String usernameClaim,
            @DefaultValue("false") boolean createUsers,
            @DefaultValue("email") LinkBy linkUsersBy,
            @DefaultValue("") String groupsClaim,
            @DefaultValue List<GroupMapping> groupMapping,
            @DefaultValue("true") boolean logoutAtProvider) {

        public Provider {
            scopes = List.copyOf(scopes);
            groupMapping = groupMapping == null ? List.of() : List.copyOf(groupMapping);
        }

        void validate(String id) {
            String prefix = "buggywuggy.security.sso.providers." + id;
            if (!PROVIDER_ID.matcher(id).matches()) {
                throw new IllegalStateException(prefix + ": the id may only have lower-case letters, digits and -");
            }
            if (issuerUri == null || issuerUri.isBlank()) {
                throw new IllegalStateException(prefix + ".issuer-uri is missing");
            }
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalStateException(prefix + ".client-id is missing");
            }
            if (!scopes.contains("openid")) {
                throw new IllegalStateException(prefix + ".scopes must contain openid");
            }
            for (GroupMapping m : groupMapping) {
                if (m.idpGroup() == null || m.idpGroup().isBlank() || m.group() == null || m.group().isBlank()) {
                    throw new IllegalStateException(prefix + ".group-mapping needs idp-group and group in every entry");
                }
            }
            if (!groupMapping.isEmpty() && groupsClaim.isBlank()) {
                throw new IllegalStateException(prefix + ".group-mapping needs groups-claim");
            }
        }

        public String name(String id) {
            return displayName == null || displayName.isBlank() ? id : displayName;
        }

        public boolean syncGroups() {
            return !groupsClaim.isBlank();
        }
    }

    /** {@code idp-group} at the provider gives membership of the BuggyWuggy group {@code group}. */
    public record GroupMapping(String idpGroup, String group) {
    }
}
