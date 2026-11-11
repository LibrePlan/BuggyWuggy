-- Saved filters, transactional outbox, user preferences and application settings.
-- See docs/domain-model.md §10.

create table saved_filter (
    id          bigint generated always as identity primary key,
    owner_id    bigint      not null references user_account (id) on delete cascade,
    name        text        not null,
    description text,
    query       jsonb       not null,
    shared_with text        not null default 'PRIVATE',
    project_id  bigint      references project (id) on delete cascade,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    constraint saved_filter_shared_with_ck check (shared_with in ('PRIVATE', 'LOGGED_IN', 'PROJECT')),
    constraint saved_filter_project_ck check ((shared_with = 'PROJECT') = (project_id is not null))
);
create unique index saved_filter_owner_name_uk on saved_filter (owner_id, lower(name));

create table outbox_event (
    id           bigint generated always as identity primary key,
    event_type   text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null,
    processed_at timestamptz,
    attempts     int         not null default 0,
    last_error   text
);
create index outbox_event_pending_idx on outbox_event (created_at) where processed_at is null;

create table user_preference (
    user_id bigint not null references user_account (id) on delete cascade,
    key     text   not null,
    value   text   not null,
    primary key (user_id, key)
);

create table application_setting (
    key   text  primary key,
    value jsonb not null
);
