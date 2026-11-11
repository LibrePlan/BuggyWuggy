-- Append-only change history and import bookkeeping. See docs/domain-model.md §8.

create table change_group (
    id         bigint generated always as identity primary key,
    issue_id   bigint      not null references issue (id) on delete cascade,
    author_id  bigint      references user_account (id) on delete set null,
    created_at timestamptz not null
);
create index change_group_issue_idx on change_group (issue_id, created_at);

create table change_item (
    id              bigint generated always as identity primary key,
    change_group_id bigint not null references change_group (id) on delete cascade,
    field_key       text   not null,
    field_kind      text   not null default 'SYSTEM',
    old_value       text,
    old_text        text,
    new_value       text,
    new_text        text,
    constraint change_item_field_kind_ck check (field_kind in ('SYSTEM', 'CUSTOM'))
);
create index change_item_group_idx on change_item (change_group_id);

create table import_source (
    id         bigint generated always as identity primary key,
    kind       text        not null,
    base_url   text,
    label      text        not null,
    created_at timestamptz not null,
    constraint import_source_kind_ck check (kind in ('JIRA_BACKUP', 'JIRA_REST'))
);

create table external_ref (
    id               bigint generated always as identity primary key,
    import_source_id bigint      not null references import_source (id) on delete cascade,
    source_type      text        not null,
    source_id        text        not null,
    local_type       text        not null,
    local_id         bigint      not null,
    imported_at      timestamptz not null,
    constraint external_ref_source_uk unique (import_source_id, source_type, source_id)
);
create index external_ref_local_idx on external_ref (local_type, local_id);
