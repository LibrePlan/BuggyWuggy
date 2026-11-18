package org.buggywuggy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
@Transactional
class DatabaseMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seedsDefaultLookups() {
        assertThat(count("issue_type")).isEqualTo(6);
        assertThat(count("priority")).isEqualTo(5);
        assertThat(count("resolution")).isEqualTo(6);
        assertThat(count("status")).isEqualTo(5);
        assertThat(count("project_role")).isEqualTo(3);
    }

    @Test
    void defaultWorkflowHasOneInitialStatusAndAllTransitions() {
        Integer initial = jdbc.queryForObject("""
                select count(*) from workflow_status ws join workflow w on w.id = ws.workflow_id
                where w.name = 'Default' and ws.initial""", Integer.class);
        Integer transitions = jdbc.queryForObject("""
                select count(*) from workflow_transition t join workflow w on w.id = t.workflow_id
                where w.name = 'Default'""", Integer.class);
        assertThat(initial).isEqualTo(1);
        assertThat(transitions).isEqualTo(12);
    }

    @Test
    void administratorsRoleHasEveryProjectPermission() {
        Integer perms = jdbc.queryForObject("""
                select count(distinct pg.permission) from permission_grant pg
                join project_role r on r.id = pg.holder_ref
                where pg.holder_type = 'PROJECT_ROLE' and r.name = 'Administrators'""", Integer.class);
        assertThat(perms).isEqualTo(18);
    }

    @Test
    void fullTextSearchFindsIssueBySummaryAndStemmedWord() {
        long issueId = createIssue("Login fails when password contains unicode", "Users report errors.");

        assertThat(searchIssues("login")).containsExactly(issueId);
        assertThat(searchIssues("failing")).containsExactly(issueId);   // English stemming
        assertThat(searchIssues("reporting")).containsExactly(issueId); // matches description
        assertThat(searchIssues("logout")).isEmpty();
    }

    @Test
    void trigramIndexSupportsPartialSummaryMatch() {
        long issueId = createIssue("Authentication timeout on slow networks", null);
        Long found = jdbc.queryForObject(
                "select id from issue where summary ilike ? ", Long.class, "%authent%");
        assertThat(found).isEqualTo(issueId);
    }

    @Test
    void rejectsInvalidProjectKey() {
        long userId = createUser("keytester");
        assertThatThrownBy(() -> createProject("bad-key", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers ---------------------------------------------------------------

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private java.util.List<Long> searchIssues(String term) {
        return jdbc.queryForList(
                "select id from issue where search_vector @@ (plainto_tsquery('simple', ?) || plainto_tsquery('english', ?))",
                Long.class, term, term);
    }

    private long createUser(String username) {
        return jdbc.queryForObject("""
                insert into user_account (username, display_name, email, created_at)
                values (?, ?, ?, now()) returning id""", Long.class, username, username, username + "@example.org");
    }

    private long createProject(String key, long leadId) {
        return jdbc.queryForObject("""
                insert into project (key, name, lead_id, permission_scheme_id, workflow_scheme_id, created_at)
                values (?, ?, ?,
                        (select id from permission_scheme where name = 'Default Permission Scheme'),
                        (select id from workflow_scheme where name = 'Default Workflow Scheme'),
                        now())
                returning id""", Long.class, key, "Project " + key, leadId);
    }

    private long createIssue(String summary, String description) {
        long userId = createUser("user" + System.nanoTime());
        long projectId = createProject("T" + (System.nanoTime() % 1_000_000), userId);
        long number = jdbc.queryForObject(
                "update project set issue_counter = issue_counter + 1 where id = ? returning issue_counter",
                Long.class, projectId);
        return jdbc.queryForObject("""
                insert into issue (project_id, number, issue_type_id, status_id, summary, description,
                                   reporter_id, creator_id, created_at, updated_at)
                values (?, ?, (select id from issue_type where name = 'Bug'),
                        (select id from status where name = 'Open'), ?, ?, ?, ?, now(), now())
                returning id""", Long.class, projectId, number, summary, description, userId, userId);
    }
}
