package org.buggywuggy.issue;

import static org.assertj.core.api.Assertions.assertThat;

import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;

/** Helpers shared by issue-related integration tests. */
abstract class IssueTestSupport extends IntegrationTest {

    protected String createIssue(ApiClient client, String project, String type, String summary) {
        return createIssue(client, project, type, summary, null);
    }

    protected String createIssue(ApiClient client, String project, String type, String summary, String parent) {
        var response = client.post("/api/v1/issues", """
                {"project": "%s", "issueTypeId": %d, "summary": "%s"%s}""".formatted(project, typeId(type), summary,
                parent == null ? "" : ", \"parent\": \"" + parent + "\""));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return response.json("$.key");
    }

    protected long typeId(String name) {
        return jdbc.queryForObject("select id from issue_type where name = ?", Long.class, name);
    }

    protected long resolutionId(String name) {
        return jdbc.queryForObject("select id from resolution where name = ?", Long.class, name);
    }
}
