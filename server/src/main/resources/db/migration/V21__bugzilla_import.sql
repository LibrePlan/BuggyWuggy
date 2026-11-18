-- Importing from Bugzilla.
alter table import_source drop constraint import_source_kind_ck;
alter table import_source add constraint import_source_kind_ck
    check (kind in ('JIRA_BACKUP', 'JIRA_REST', 'JIRA', 'FORGEJO', 'GITLAB', 'GITHUB', 'REDMINE', 'TRAC', 'BUGZILLA'));
