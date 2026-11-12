/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.ApiClient.FilePart;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class AttachmentIntegrationTest extends IssueTestSupport {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
    private static final byte[] HTML = "<script>alert('x')</script>".getBytes(StandardCharsets.UTF_8);

    private Path fileOf(long attachmentId) {
        String key = jdbc.queryForObject("select storage_key from attachment where id = ?", String.class,
                attachmentId);
        return Path.of("target/test-data/attachments", key.substring(0, 2), key);
    }

    private static long id(ApiClient.Response response, int index) {
        return ((Number) response.json("$[" + index + "].id")).longValue();
    }

    @Test
    void uploadsListsDownloadsAndDeletes() throws Exception {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Bug", "Broken layout");

        var uploaded = admin.upload("/api/v1/issues/" + key + "/attachments", List.of(
                new FilePart("screenshot.png", "image/png", PNG),
                new FilePart("page.html", "text/html", HTML)));
        assertThat(uploaded.status()).as(uploaded.toString()).isEqualTo(201);
        assertThat((List<String>) uploaded.json("$[*].fileName")).containsExactly("screenshot.png", "page.html");
        assertThat((List<Boolean>) uploaded.json("$[*].previewable")).containsExactly(true, false);
        assertThat((Integer) uploaded.json("$[0].size")).isEqualTo(PNG.length);
        long png = id(uploaded, 0);
        long html = id(uploaded, 1);

        assertThat(Files.readAllBytes(fileOf(png))).isEqualTo(PNG);
        assertThat(jdbc.queryForObject("select sha256 from attachment where id = ?", String.class, png))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PNG)));
        assertThat((List<String>) admin.get("/api/v1/issues/" + key + "/attachments").json("$[*].fileName"))
                .containsExactly("screenshot.png", "page.html");

        // Images are shown inline; anything else is a download that the browser must not interpret.
        var image = admin.download("/api/v1/attachments/" + png + "/content");
        assertThat(image.status()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(PNG);
        assertThat(image.header("Content-Type")).isEqualTo("image/png");
        assertThat(image.header("Content-Disposition")).startsWith("inline");
        var page = admin.download("/api/v1/attachments/" + html + "/content");
        assertThat(page.header("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(page.header("Content-Disposition")).startsWith("attachment").contains("page.html");
        assertThat(page.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(page.header("Content-Security-Policy")).contains("sandbox");

        assertThat((List<String>) admin.get("/api/v1/issues/" + key + "/history")
                .json("$[*].items[?(@.field == 'attachment')].toText")).containsExactly("screenshot.png", "page.html");

        Path file = fileOf(html);
        assertThat(admin.delete("/api/v1/issues/" + key + "/attachments/" + html).status()).isEqualTo(204);
        assertThat(file).doesNotExist();
        assertThat(admin.download("/api/v1/attachments/" + html + "/content").status()).isEqualTo(404);
        assertThat((List<String>) admin.get("/api/v1/issues/" + key + "/history")
                .json("$[*].items[?(@.field == 'attachment' && @.fromText != null)].fromText")).containsExactly("page.html");
    }

    @Test
    void respectsPermissions() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SECRET", "Secret");
        admin.delete("/api/v1/projects/SECRET/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        String key = createIssue(admin, "BUG", "Task", "Shared");
        String secret = createIssue(admin, "SECRET", "Task", "Secret");
        long adminFile = id(admin.upload("/api/v1/issues/" + key + "/attachments",
                List.of(new FilePart("admin.txt", "text/plain", "a".getBytes(StandardCharsets.UTF_8)))), 0);
        long secretFile = id(admin.upload("/api/v1/issues/" + secret + "/attachments",
                List.of(new FilePart("secret.txt", "text/plain", "s".getBytes(StandardCharsets.UTF_8)))), 0);
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");

        // Users may attach files and delete their own, but not those of others.
        var own = otto.upload("/api/v1/issues/" + key + "/attachments",
                List.of(new FilePart("otto.txt", "text/plain", "o".getBytes(StandardCharsets.UTF_8))));
        assertThat(own.status()).isEqualTo(201);
        assertThat((Boolean) own.json("$[0].canDelete")).isTrue();
        assertThat(otto.delete("/api/v1/issues/" + key + "/attachments/" + adminFile).status()).isEqualTo(403);
        assertThat(otto.delete("/api/v1/issues/" + key + "/attachments/" + id(own, 0)).status()).isEqualTo(204);

        // Files of issues otto cannot see do not exist for him.
        var hidden = otto.download("/api/v1/attachments/" + secretFile + "/content");
        assertThat(hidden.status()).isEqualTo(404);
    }

    @Test
    void deletingIssuesAndProjectsRemovesTheirFiles() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String parent = createIssue(admin, "BUG", "Task", "Parent");
        String child = createIssue(admin, "BUG", "Sub-task", "Child", parent);
        String other = createIssue(admin, "BUG", "Task", "Other");
        FilePart file = new FilePart("notes.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        long parentFile = id(admin.upload("/api/v1/issues/" + parent + "/attachments", List.of(file)), 0);
        long childFile = id(admin.upload("/api/v1/issues/" + child + "/attachments", List.of(file)), 0);
        long otherFile = id(admin.upload("/api/v1/issues/" + other + "/attachments", List.of(file)), 0);
        Path parentPath = fileOf(parentFile);
        Path childPath = fileOf(childFile);
        Path otherPath = fileOf(otherFile);

        assertThat(admin.delete("/api/v1/issues/" + parent).status()).isEqualTo(204);
        assertThat(parentPath).doesNotExist();
        assertThat(childPath).doesNotExist();
        assertThat(otherPath).exists();

        assertThat(admin.delete("/api/v1/projects/BUG").status()).isEqualTo(204);
        assertThat(otherPath).doesNotExist();
    }

    @Test
    void rejectsTooLargeFilesAndCleansFileNames() throws Exception {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Uploads");

        // The test configuration allows 1 MB per file.
        var tooLarge = admin.upload("/api/v1/issues/" + key + "/attachments",
                List.of(new FilePart("big.bin", "application/octet-stream", new byte[1_200_000])));
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat((String) tooLarge.json("$.code")).isEqualTo("attachment.too-large");
        assertThat((Integer) admin.get("/api/v1/attachments/settings").json("$.maxFileSize")).isEqualTo(1_048_576);

        var sneaky = admin.upload("/api/v1/issues/" + key + "/attachments",
                List.of(new FilePart("..\\\\..\\\\evil.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8))));
        assertThat(sneaky.status()).as(sneaky.toString()).isEqualTo(201);
        assertThat((String) sneaky.json("$[0].fileName")).isEqualTo("evil.svg");
        // SVG can contain scripts, so it is not previewable.
        assertThat((Boolean) sneaky.json("$[0].previewable")).isFalse();

        var none = admin.upload("/api/v1/issues/" + key + "/attachments", List.of());
        assertThat(none.status()).isEqualTo(400);
    }

    @Test
    void uploadsToArchivedProjectsAreRefusedWithoutLeavingFiles() throws Exception {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Archived later");
        admin.patch("/api/v1/projects/BUG", "{\"archived\": true}");
        long before;
        try (var files = Files.walk(Path.of("target/test-data/attachments"))) {
            before = files.filter(Files::isRegularFile).count();
        }
        var response = admin.upload("/api/v1/issues/" + key + "/attachments",
                List.of(new FilePart("late.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))));
        assertThat(response.status()).isEqualTo(409);
        try (var files = Files.walk(Path.of("target/test-data/attachments"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(before);
        }
    }
}
