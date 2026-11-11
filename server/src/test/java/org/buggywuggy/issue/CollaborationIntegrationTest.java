/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class CollaborationIntegrationTest extends IssueTestSupport {

    private long linkTypeId(String name) {
        return jdbc.queryForObject("select id from issue_link_type where name = ?", Long.class, name);
    }

    // --- comments --------------------------------------------------------------------

    @Test
    void commentsCanBeAddedEditedAndDeletedByTheirAuthor() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Crash on save");
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");
        long issueVersion = ((Number) admin.get("/api/v1/issues/" + key).json("$.version")).longValue();
        String updatedBefore = admin.get("/api/v1/issues/" + key).json("$.updatedAt");

        var created = otto.post("/api/v1/issues/" + key + "/comments", """
                {"body": "  It happens **every** time.\\n"}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((String) created.json("$.body")).isEqualTo("It happens **every** time.");
        assertThat((String) created.json("$.author.username")).isEqualTo("otto");
        assertThat((Boolean) created.json("$.canEdit")).isTrue();
        assertThat((Boolean) created.json("$.canDelete")).isTrue();
        long commentId = ((Number) created.json("$.id")).longValue();

        // A comment updates the issue's timestamp but not its version, and makes the author a watcher.
        var issue = admin.get("/api/v1/issues/" + key);
        assertThat(((Number) issue.json("$.version")).longValue()).isEqualTo(issueVersion);
        assertThat((String) issue.json("$.updatedAt")).isGreaterThan(updatedBefore);
        assertThat((Boolean) otto.get("/api/v1/issues/" + key + "/watchers").json("$.watching")).isTrue();

        var edited = otto.patch("/api/v1/issues/" + key + "/comments/" + commentId, """
                {"body": "It happens sometimes.", "version": 0}""");
        assertThat(edited.status()).as(edited.toString()).isEqualTo(200);
        assertThat((String) edited.json("$.updateAuthor.username")).isEqualTo("otto");
        assertThat((Integer) edited.json("$.version")).isEqualTo(1);

        var stale = otto.patch("/api/v1/issues/" + key + "/comments/" + commentId, """
                {"body": "Stale edit", "version": 0}""");
        assertThat(stale.status()).isEqualTo(409);

        assertThat(otto.delete("/api/v1/issues/" + key + "/comments/" + commentId).status()).isEqualTo(204);
        assertThat((List<Object>) admin.get("/api/v1/issues/" + key + "/comments").json("$")).isEmpty();
    }

    @Test
    void othersCommentsNeedTheAllPermissions() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Crash on save");
        long commentId = ((Number) admin.post("/api/v1/issues/" + key + "/comments", """
                {"body": "By the admin"}""").json("$.id")).longValue();
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");

        var list = otto.get("/api/v1/issues/" + key + "/comments");
        assertThat((List<Boolean>) list.json("$[*].canEdit")).containsExactly(false);
        assertThat(otto.patch("/api/v1/issues/" + key + "/comments/" + commentId, """
                {"body": "Hijacked"}""").status()).isEqualTo(403);
        assertThat(otto.delete("/api/v1/issues/" + key + "/comments/" + commentId).status()).isEqualTo(403);

        // Administrators may edit everyone's comments.
        long ottoComment = ((Number) otto.post("/api/v1/issues/" + key + "/comments", """
                {"body": "By otto"}""").json("$.id")).longValue();
        assertThat(admin.patch("/api/v1/issues/" + key + "/comments/" + ottoComment, """
                {"body": "Moderated"}""").status()).isEqualTo(200);
    }

    @Test
    void commentValidationAndNotFound() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Crash on save");
        var blank = admin.post("/api/v1/issues/" + key + "/comments", """
                {"body": "   "}""");
        assertThat(blank.status()).isEqualTo(400);
        assertThat((List<String>) blank.json("$.errors[*].field")).containsExactly("body");
        var missing = admin.patch("/api/v1/issues/" + key + "/comments/999999", """
                {"body": "x"}""");
        assertThat(missing.status()).isEqualTo(404);
        assertThat((String) missing.json("$.code")).isEqualTo("comment.not-found");
    }

    // --- links -----------------------------------------------------------------------

    @Test
    void linksAreShownFromBothSidesAndRecordedInHistory() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "OPS", "Operations");
        String a = createIssue(admin, "BUG", "Task", "Database migration");
        String b = createIssue(admin, "OPS", "Task", "Deploy release");

        assertThat((List<String>) admin.get("/api/v1/issue-link-types").json("$[*].name"))
                .containsExactly("Blocks", "Cloners", "Duplicate", "Relates");

        var created = admin.post("/api/v1/issues/" + a + "/links", """
                {"linkTypeId": %d, "issue": "%s"}""".formatted(linkTypeId("Blocks"), b));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((String) created.json("$.direction")).isEqualTo("OUTWARD");
        assertThat((String) created.json("$.description")).isEqualTo("blocks");
        assertThat((String) created.json("$.issue.key")).isEqualTo(b);
        long linkId = ((Number) created.json("$.id")).longValue();

        var fromB = admin.get("/api/v1/issues/" + b + "/links");
        assertThat((List<String>) fromB.json("$[*].description")).containsExactly("is blocked by");
        assertThat((List<String>) fromB.json("$[*].issue.key")).containsExactly(a);

        // The same link again, also when created from the other side, is a conflict.
        var duplicate = admin.post("/api/v1/issues/" + b + "/links", """
                {"linkTypeId": %d, "issue": "%s", "direction": "INWARD"}""".formatted(linkTypeId("Blocks"), a));
        assertThat(duplicate.status()).isEqualTo(409);

        assertThat((List<String>) admin.get("/api/v1/issues/" + a + "/history")
                .json("$[*].items[?(@.field == 'link')].toText")).containsExactly("blocks " + b);
        assertThat((List<String>) admin.get("/api/v1/issues/" + b + "/history")
                .json("$[*].items[?(@.field == 'link')].toText")).containsExactly("is blocked by " + a);

        assertThat(admin.delete("/api/v1/issues/" + b + "/links/" + linkId).status()).isEqualTo(204);
        assertThat((List<Object>) admin.get("/api/v1/issues/" + a + "/links").json("$")).isEmpty();
        // Added first (no old value), then removed (no new value).
        assertThat((List<String>) admin.get("/api/v1/issues/" + a + "/history")
                .json("$[*].items[?(@.field == 'link')].fromText")).containsExactly(null, "blocks " + b);
        assertThat((List<String>) admin.get("/api/v1/issues/" + a + "/history")
                .json("$[*].items[?(@.field == 'link')].toText")).containsExactly("blocks " + b, null);
    }

    @Test
    void linksRespectPermissionsAndVisibility() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SECRET", "Secret");
        admin.delete("/api/v1/projects/SECRET/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        String a = createIssue(admin, "BUG", "Task", "Visible");
        String hidden = createIssue(admin, "SECRET", "Task", "Hidden");
        admin.post("/api/v1/issues/" + a + "/links", """
                {"linkTypeId": %d, "issue": "%s"}""".formatted(linkTypeId("Relates"), hidden));
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");

        // Links to issues otto cannot see are left out.
        assertThat((List<Object>) otto.get("/api/v1/issues/" + a + "/links").json("$")).isEmpty();
        // Plain users may not link (LINK_ISSUE is for developers and administrators).
        String c = createIssue(admin, "BUG", "Task", "Other");
        assertThat(otto.post("/api/v1/issues/" + a + "/links", """
                {"linkTypeId": %d, "issue": "%s"}""".formatted(linkTypeId("Relates"), c)).status()).isEqualTo(403);

        var self = admin.post("/api/v1/issues/" + a + "/links", """
                {"linkTypeId": %d, "issue": "%s"}""".formatted(linkTypeId("Relates"), a));
        assertThat((String) self.json("$.code")).isEqualTo("link.self");
        var unknown = admin.post("/api/v1/issues/" + a + "/links", """
                {"linkTypeId": %d, "issue": "BUG-999"}""".formatted(linkTypeId("Relates")));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat((String) unknown.json("$.code")).isEqualTo("link.issue-not-found");
    }

    // --- watchers --------------------------------------------------------------------

    @Test
    void usersWatchThemselvesAndManagersManageOthers() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Crash on save");
        long ottoId = createUser(admin, "otto", "otto-password-12");
        long adminId = ((Number) admin.get("/api/v1/myself").json("$.id")).longValue();
        ApiClient otto = loginAs("otto", "otto-password-12");

        // The creator watches automatically. Otto may see the count, but not who.
        var forOtto = otto.get("/api/v1/issues/" + key + "/watchers");
        assertThat((Boolean) forOtto.json("$.watching")).isFalse();
        assertThat((Integer) forOtto.json("$.count")).isEqualTo(1);
        assertThat((Object) forOtto.json("$.watchers")).isNull();

        var watched = otto.put("/api/v1/issues/" + key + "/watchers/" + ottoId, null);
        assertThat(watched.status()).as(watched.toString()).isEqualTo(200);
        assertThat((Boolean) watched.json("$.watching")).isTrue();
        assertThat((Integer) watched.json("$.count")).isEqualTo(2);

        assertThat(otto.delete("/api/v1/issues/" + key + "/watchers/" + adminId).status()).isEqualTo(403);
        assertThat((List<String>) admin.get("/api/v1/issues/" + key + "/watchers").json("$.watchers[*].username"))
                .containsExactlyInAnyOrder(ADMIN, "otto");

        var removed = admin.delete("/api/v1/issues/" + key + "/watchers/" + ottoId);
        assertThat((List<String>) removed.json("$.watchers[*].username")).containsExactly(ADMIN);
        assertThat(admin.put("/api/v1/issues/" + key + "/watchers/" + ottoId, null).status()).isEqualTo(200);
    }
}
