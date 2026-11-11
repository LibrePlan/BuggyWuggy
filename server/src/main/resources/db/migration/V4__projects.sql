-- Projects, components, versions and project role membership. See docs/domain-model.md §4, §7.2.

create table project (
    id                   bigint generated always as identity primary key,
    key                  text        not null,
    name                 text        not null,
    description          text,
    lead_id              bigint      not null references user_account (id),
    default_assignee     text        not null default 'UNASSIGNED',
    issue_counter        bigint      not null default 0,
    permission_scheme_id bigint      not null references permission_scheme (id),
    workflow_scheme_id   bigint      not null references workflow_scheme (id),
    archived             boolean     not null default false,
    created_at           timestamptz not null,
    version              bigint      not null default 0,
    constraint project_key_format_ck check (key ~ '^[A-Z][A-Z0-9_]{1,9}$'),
    constraint project_default_assignee_ck check (default_assignee in ('UNASSIGNED', 'PROJECT_LEAD')),
    constraint project_issue_counter_ck check (issue_counter >= 0)
);
create unique index project_key_uk on project (key);
create unique index project_name_uk on project (lower(name));

create table project_key_history (
    old_key    text   primary key,
    project_id bigint not null references project (id) on delete cascade
);

create table component (
    id               bigint generated always as identity primary key,
    project_id       bigint not null references project (id) on delete cascade,
    name             text   not null,
    description      text,
    lead_id          bigint references user_account (id) on delete set null,
    default_assignee text   not null default 'PROJECT_DEFAULT',
    constraint component_default_assignee_ck
        check (default_assignee in ('PROJECT_DEFAULT', 'UNASSIGNED', 'PROJECT_LEAD', 'COMPONENT_LEAD'))
);
create unique index component_name_uk on component (project_id, lower(name));

create table project_version (
    id           bigint generated always as identity primary key,
    project_id   bigint  not null references project (id) on delete cascade,
    name         text    not null,
    description  text,
    start_date   date,
    release_date date,
    released     boolean not null default false,
    archived     boolean not null default false,
    position     int     not null
);
create unique index project_version_name_uk on project_version (project_id, lower(name));

create table project_role_actor (
    id              bigint generated always as identity primary key,
    project_id      bigint not null references project (id) on delete cascade,
    project_role_id bigint not null references project_role (id) on delete cascade,
    user_id         bigint references user_account (id) on delete cascade,
    group_id        bigint references user_group (id) on delete cascade,
    constraint project_role_actor_one_holder_ck check (num_nonnulls(user_id, group_id) = 1),
    constraint project_role_actor_uk unique nulls not distinct (project_id, project_role_id, user_id, group_id)
);
create index project_role_actor_user_idx on project_role_actor (user_id) where user_id is not null;
create index project_role_actor_group_idx on project_role_actor (group_id) where group_id is not null;
