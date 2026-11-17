/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * A demo build ({@code ./mvnw -Ddemo}): here switched on by a bean instead of the build
 * information. Mail and two-factor login are configured, and must stay off.
 */
@Import(DemoModeIntegrationTest.DemoBuild.class)
@TestPropertySource(properties = {"buggywuggy.mail.host=localhost", "buggywuggy.security.two-factor.enabled=true"})
class DemoModeIntegrationTest extends IssueTestSupport {

    @TestConfiguration
    static class DemoBuild {
        @Bean
        @Primary
        DemoMode demoBuild() {
            return new DemoMode(true);
        }
    }

    @Autowired
    DemoSetup demoSetup;

    ApiClient admin;

    @BeforeEach
    void demo() {
        admin = setupAndLoginAdmin();
        demoSetup.ensure();
        demoSetup.ensure(); // a second start changes nothing
    }

    private ApiClient demoUser() {
        ApiClient client = client();
        var login = client.login(DemoMode.USERNAME, DemoMode.PASSWORD);
        assertThat(login.status()).as(login.toString()).isEqualTo(200);
        assertThat(login.<String>json("$.step")).isEqualTo("DONE");
        return client;
    }

    @Test
    void tellsTheLoginPageAndSwitchesOffMailAndTwoFactorLogin() {
        var version = client().get("/api/v1/version");
        assertThat(version.<String>json("$.demo.username")).isEqualTo("demo");
        assertThat(version.<String>json("$.demo.password")).isEqualTo("demo");
        assertThat(version.<String>json("$.demo.projectKey")).isEqualTo("DEMO");

        var mail = admin.get("/api/v1/admin/mail/status");
        assertThat(mail.<Boolean>json("$.demo")).isTrue();
        assertThat(mail.<Boolean>json("$.configured")).isFalse();
        assertThat(admin.post("/api/v1/admin/mail/test", null).status()).isNotIn(200, 202, 204);

        // Two-factor login is configured, but nobody gets the second step.
        assertThat(client().login(ADMIN, ADMIN_PASSWORD).<String>json("$.step")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select count(*) from user_account where username = 'demo'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from project where key = 'DEMO'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theDemoAccountMayDoAnythingWithIssuesInTheDemoProject() {
        ApiClient demo = demoUser();
        var myself = demo.get("/api/v1/myself");
        assertThat(myself.<List<String>>json("$.globalPermissions")).doesNotContain("ADMINISTER", "CREATE_PROJECT");

        String key = createIssue(demo, "DEMO", "Bug", "Try me");
        assertThat(demo.patch("/api/v1/issues/" + key, """
                {"summary": "Tried"}""").status()).isEqualTo(200);
        assertThat(demo.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Hello"}""").status()).isEqualTo(201);
        List<Integer> transitions = demo.get("/api/v1/issues/" + key + "/transitions").json("$[*].id");
        assertThat(transitions).isNotEmpty();
        assertThat(demo.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d}""".formatted(transitions.get(0))).status()).isEqualTo(200);
        assertThat(demo.delete("/api/v1/issues/" + key).status()).isEqualTo(204);
        // But not the project itself.
        assertThat(demo.patch("/api/v1/projects/DEMO", """
                {"name": "Mine"}""").status()).isEqualTo(403);
        assertThat(demo.post("/api/v1/projects", """
                {"key": "MINE", "name": "Mine"}""").status()).isEqualTo(403);
    }

    @Test
    void theDemoAccountOnlyReadsOtherProjectsWhereOthersWorkAsUsual() {
        createProject(admin, "BW", "BuggyWuggy");
        String key = createIssue(admin, "BW", "Bug", "A real bug");
        long mails = jdbc.queryForObject("select count(*) from mail_message", Long.class);

        ApiClient demo = demoUser();
        assertThat(demo.get("/api/v1/issues/" + key).status()).isEqualTo(200);
        assertThat(demo.get("/api/v1/projects/BW/my-permissions").<List<String>>json("$.permissions"))
                .containsExactly("BROWSE_PROJECT");
        assertThat(demo.patch("/api/v1/issues/" + key, """
                {"summary": "Defaced"}""").status()).isEqualTo(403);
        assertThat(demo.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Spam"}""").status()).isEqualTo(403);
        assertThat(demo.post("/api/v1/issues", """
                {"project": "BW", "issueTypeId": %d, "summary": "Spam"}""".formatted(typeId("Bug"))).status())
                .isEqualTo(403);
        assertThat(demo.get("/api/v1/issues/" + key + "/transitions").<List<Object>>json("$")).isEmpty();
        assertThat(demo.delete("/api/v1/issues/" + key).status()).isEqualTo(403);
        assertThat(demo.get("/api/v1/projects/BW/assignable-users?query=demo").<List<String>>json("$[*].username"))
                .doesNotContain("demo");

        // Dogfooding: an ordinary user works on the real project as usual.
        createUser(admin, "erin", "erin-password-123");
        jdbc.update("""
                insert into project_role_actor (project_id, project_role_id, user_id)
                select p.id, r.id, u.id from project p, project_role r, user_account u
                where p.key = 'BW' and r.name = 'Developers' and u.username = 'erin'""");
        ApiClient erin = loginAs("erin", "erin-password-123");
        assertThat(erin.patch("/api/v1/issues/" + key, """
                {"summary": "A real bug, confirmed"}""").status()).isEqualTo(200);
        assertThat(erin.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Confirmed"}""").status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from mail_message", Long.class)).isEqualTo(mails);
    }

    @Test
    void theDemoAccountKeepsItsNameAndPassword() {
        ApiClient demo = demoUser();
        assertThat(demo.patch("/api/v1/myself", """
                {"displayName": "Hacked"}""").status()).isEqualTo(403);
        assertThat(demo.patch("/api/v1/myself", """
                {"email": "me@example.org"}""").status()).isEqualTo(403);
        assertThat(demo.put("/api/v1/myself/password", """
                {"currentPassword": "demo", "newPassword": "something-else-123"}""").status()).isEqualTo(403);
        assertThat(demo.patch("/api/v1/myself", """
                {"timeZone": "Europe/Amsterdam"}""").status()).isEqualTo(200);
        // An administrator still manages the account.
        long id = jdbc.queryForObject("select id from user_account where username = 'demo'", Long.class);
        assertThat(admin.patch("/api/v1/admin/users/" + id, """
                {"displayName": "Visitor"}""").status()).isEqualTo(200);
    }
}
