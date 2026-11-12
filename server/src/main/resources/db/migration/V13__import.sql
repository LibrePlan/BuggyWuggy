-- Importing from other issue trackers. See docs/domain-model.md §8.

-- One source per tracker, server and project there (e.g. a Forgejo repository).
alter table import_source drop constraint import_source_kind_ck;
alter table import_source add constraint import_source_kind_ck
    check (kind in ('JIRA_BACKUP', 'JIRA_REST', 'JIRA', 'FORGEJO', 'GITLAB', 'GITHUB', 'REDMINE', 'TRAC'));
alter table import_source add column project_path text;
create unique index import_source_uk
    on import_source (kind, lower(coalesce(base_url, '')), lower(coalesce(project_path, '')));

-- Where an imported issue came from: shown on the issue and in its history.
alter table issue
    add column origin_source_id bigint references import_source (id) on delete set null,
    add column origin_key       text,
    add column origin_url       text;

-- Links to things outside BuggyWuggy, such as the pull requests that referenced an issue.
create table issue_remote_link (
    id         bigint generated always as identity primary key,
    issue_id   bigint      not null references issue (id) on delete cascade,
    url        text        not null,
    title      text        not null,
    relation   text        not null,
    created_at timestamptz not null,
    constraint issue_remote_link_uk unique (issue_id, url),
    constraint issue_remote_link_relation_ck check (relation in ('PULL_REQUEST', 'OTHER'))
);

-- An import run. Credentials are never stored here.
create table import_job (
    id               bigint generated always as identity primary key,
    import_source_id bigint      not null references import_source (id) on delete cascade,
    project_id       bigint      references project (id) on delete set null,
    status           text        not null,
    options          text        not null,
    created_by       bigint      references user_account (id) on delete set null,
    created_at       timestamptz not null,
    finished_at      timestamptz,
    issues_total     int         not null default 0,
    issues_done      int         not null default 0,
    issues_imported  int         not null default 0,
    comments         int         not null default 0,
    attachments      int         not null default 0,
    users_created    int         not null default 0,
    warnings         int         not null default 0,
    message          text,
    constraint import_job_status_ck
        check (status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'))
);
create index import_job_created_idx on import_job (created_at desc);

create table import_job_log (
    id      bigint generated always as identity primary key,
    job_id  bigint      not null references import_job (id) on delete cascade,
    at      timestamptz not null,
    level   text        not null,
    message text        not null,
    constraint import_job_log_level_ck check (level in ('INFO', 'WARN', 'ERROR'))
);
create index import_job_log_job_idx on import_job_log (job_id, id);
