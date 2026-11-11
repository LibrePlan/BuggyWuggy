/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The web UI is served by the server (it is on the classpath through the web module). */
class WebAppIntegrationTest extends IntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> browserGet(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").GET().build(), BodyHandlers.ofString());
    }

    @Test
    void servesIndexForUiRoutesWithoutLogin() throws Exception {
        for (String path : List.of("/", "/projects", "/browse/BUG-12", "/login")) {
            var response = browserGet(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains("<div id=\"root\"></div>");
            assertThat(response.headers().firstValue("Cache-Control")).as(path).hasValue("no-cache");
        }
    }

    @Test
    void servesHashedAssetsWithLongCaching() throws Exception {
        Matcher asset = Pattern.compile("/assets/[^\"]+\\.js").matcher(browserGet("/").body());
        assertThat(asset.find()).isTrue();
        var response = browserGet(asset.group());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("max-age=31536000", "immutable");

        assertThat(browserGet("/assets/does-not-exist.js").statusCode()).isEqualTo(404);
    }

    @Test
    void unknownApiPathsAreNotAnsweredWithTheUi() throws Exception {
        assertThat(browserGet("/api/v1/does-not-exist").statusCode()).isEqualTo(401);
        ApiClient admin = setupAndLoginAdmin();
        var response = admin.get("/api/v1/does-not-exist");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("<div id=\"root\">");
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiMarksResponseFieldsRequiredAndNullable() {
        var spec = client().get("/api/openapi");
        assertThat(spec.status()).isEqualTo(200);
        List<String> required = spec.json("$.components.schemas.IssueResponse.required");
        assertThat(required).contains("id", "key", "summary", "assignee", "description", "children");
        // Nullable: a reference wrapped in oneOf with null, a plain field with a "null" type.
        List<Map<String, Object>> assignee = spec.json("$.components.schemas.IssueResponse.properties.assignee.oneOf");
        assertThat(assignee).hasSize(2);
        assertThat((List<String>) spec.json("$.components.schemas.IssueResponse.properties.description.type"))
                .contains("string", "null");
        assertThat((Object) spec.json("$.components.schemas.IssueResponse.properties.summary.type")).isEqualTo("string");
        // Requests are left alone: optional fields may be omitted.
        List<String> requestRequired = spec.json("$.components.schemas.CreateIssueRequest.required");
        assertThat(requestRequired).doesNotContain("description", "assigneeId");
    }
}
