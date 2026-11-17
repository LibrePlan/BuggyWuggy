/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.buggywuggy.security.sso.SsoProperties.GroupMapping;
import org.buggywuggy.security.sso.SsoProperties.LinkBy;
import org.buggywuggy.security.sso.SsoProperties.PasswordLogin;
import org.buggywuggy.security.sso.SsoProperties.Provider;
import org.junit.jupiter.api.Test;

class SsoPropertiesTest {

    private static Provider provider(String issuer, List<GroupMapping> mapping, String groupsClaim) {
        return new Provider("Test", issuer, "buggywuggy", "secret", List.of("openid", "email"),
                "preferred_username", false, LinkBy.EMAIL, groupsClaim, mapping, true);
    }

    @Test
    void refusesSettingsThatLockEveryoneOutOrCannotWork() {
        assertThatThrownBy(() -> new SsoProperties(PasswordLogin.NONE, Map.of()))
                .hasMessageContaining("nobody could log in");
        assertThatThrownBy(() -> new SsoProperties(PasswordLogin.ALL,
                Map.of("Key Cloak", provider("https://idp.example.org", List.of(), ""))))
                .hasMessageContaining("lower-case");
        assertThatThrownBy(() -> new SsoProperties(PasswordLogin.ALL,
                Map.of("idp", provider("", List.of(), ""))))
                .hasMessageContaining("issuer-uri");
        assertThatThrownBy(() -> new SsoProperties(PasswordLogin.ALL,
                Map.of("idp", provider("https://idp.example.org", List.of(new GroupMapping("a", "b")), ""))))
                .hasMessageContaining("groups-claim");

        var ok = new SsoProperties(PasswordLogin.SYSTEM_ADMINS,
                Map.of("idp", provider("https://idp.example.org", List.of(new GroupMapping("a", "b")), "groups")));
        assertThat(ok.enabled()).isTrue();
        assertThat(new SsoProperties(PasswordLogin.ALL, null).enabled()).isFalse();
    }

    @Test
    void anUnreachableProviderDoesNotStopBuggyWuggy() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        var registrations = new SsoClientRegistrations(new SsoProperties(PasswordLogin.ALL,
                Map.of("idp", provider("http://localhost:" + closedPort, List.of(), ""))), Clock.systemUTC());
        assertThat(registrations.findByRegistrationId("idp")).isNull();
        assertThat(registrations.findByRegistrationId("other")).isNull();
    }

    @Test
    void readsAGroupsClaimAsListOrSingleName() {
        assertThat(SsoAccountService.groups(List.of("a", "b"))).containsExactly("a", "b");
        assertThat(SsoAccountService.groups("a")).containsExactly("a");
        assertThat(SsoAccountService.groups(null)).isEmpty();
    }

    @Test
    void keepsRedirectsInsideBuggyWuggy() {
        assertThat(SsoLoginHandlers.safeNext("/projects/ABC")).isEqualTo("/projects/ABC");
        assertThat(SsoLoginHandlers.safeNext("https://evil.example")).isEqualTo("/");
        assertThat(SsoLoginHandlers.safeNext("//evil.example")).isEqualTo("/");
        assertThat(SsoLoginHandlers.safeNext("/\\evil.example")).isEqualTo("/");
        assertThat(SsoLoginHandlers.safeNext(null)).isEqualTo("/");
    }
}
