-- Issues and their multi-valued system fields. See docs/domain-model.md §5, §9.

create extension if not exists pg_trgm;

create table issue (
    id            bigint generated always as identity primary key,
    project_id    bigint      not null references project (id) on delete cascade,
    number        bigint      not null,
    issue_type_id bigint      not null references issue_type (id),
    status_id     bigint      not null references status (id),
    priority_id   bigint      references priority (id),
    resolution_id bigint      references resolution (id),
    parent_id     bigint      references issue (id),   -- parent/child rules enforced in the domain layer
    summary       text        not null,
    description   text,
    reporter_id   bigint      references user_account (id) on delete set null,
    assignee_id   bigint      references user_account (id) on delete set null,
    creator_id    bigint      references user_account (id) on delete set null,
    due_date      date,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    resolved_at   timestamptz,
    version       bigint      not null default 0,
    search_vector tsvector generated always as (
        setweight(to_tsvector('simple'::regconfig, summary), 'A') ||
        setweight(to_tsvector('english'::regconfig, summary), 'A') ||
        setweight(to_tsvector('simple'::regconfig, coalesce(description, '')), 'B') ||
        setweight(to_tsvector('english'::regconfig, coalesce(description, '')), 'B')
    ) stored,
    constraint issue_number_ck check (number > 0),
    constraint issue_not_own_parent_ck check (parent_id is null or parent_id <> id),
    constraint issue_project_number_uk unique (project_id, number)
);
create index issue_issue_type_idx on issue (issue_type_id);
create index issue_status_idx on issue (status_id);
create index issue_priority_idx on issue (priority_id);
create index issue_resolution_idx on issue (resolution_id);
create index issue_parent_idx on issue (parent_id) where parent_id is not null;
create index issue_reporter_idx on issue (reporter_id);
create index issue_assignee_idx on issue (assignee_id);
create index issue_created_at_idx on issue (created_at);
create index issue_updated_at_idx on issue (updated_at);
create index issue_due_date_idx on issue (due_date) where due_date is not null;
create index issue_search_vector_idx on issue using gin (search_vector);
create index issue_summary_trgm_idx on issue using gin (summary gin_trgm_ops);

-- Old keys of issues that were moved to another project, so they keep resolving.
create table issue_key_history (
    project_id bigint not null references project (id) on delete cascade,
    number     bigint not null,
    issue_id   bigint not null references issue (id) on delete cascade,
    primary key (project_id, number)
);
create index issue_key_history_issue_idx on issue_key_history (issue_id);

create table issue_label (
    issue_id bigint not null references issue (id) on delete cascade,
    label    text   not null,
    primary key (issue_id, label),
    constraint issue_label_format_ck check (label ~ '^\S+$')
);
create index issue_label_label_idx on issue_label (label);

create table issue_component (
    issue_id     bigint not null references issue (id) on delete cascade,
    component_id bigint not null references component (id) on delete cascade,
    primary key (issue_id, component_id)
);
create index issue_component_component_idx on issue_component (component_id);

create table issue_version (
    issue_id   bigint not null references issue (id) on delete cascade,
    version_id bigint not null references project_version (id) on delete cascade,
    kind       text   not null,
    primary key (issue_id, version_id, kind),
    constraint issue_version_kind_ck check (kind in ('AFFECTS', 'FIX'))
);
create index issue_version_version_idx on issue_version (version_id, kind);
