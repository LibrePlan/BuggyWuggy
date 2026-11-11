-- Workflows, workflow schemes and permission schemes. See docs/domain-model.md §7.

create table workflow (
    id          bigint generated always as identity primary key,
    name        text not null,
    description text
);
create unique index workflow_name_uk on workflow (lower(name));

create table workflow_status (
    workflow_id bigint  not null references workflow (id) on delete cascade,
    status_id   bigint  not null references status (id),
    initial     boolean not null default false,
    primary key (workflow_id, status_id)
);
create unique index workflow_status_single_initial_uk on workflow_status (workflow_id) where initial;

create table workflow_transition (
    id             bigint generated always as identity primary key,
    workflow_id    bigint not null references workflow (id) on delete cascade,
    name           text   not null,
    from_status_id bigint,                      -- null = global transition (from any status)
    to_status_id   bigint not null,
    position       int    not null,
    constraint workflow_transition_from_fk foreign key (workflow_id, from_status_id)
        references workflow_status (workflow_id, status_id) on delete cascade,
    constraint workflow_transition_to_fk foreign key (workflow_id, to_status_id)
        references workflow_status (workflow_id, status_id) on delete cascade,
    constraint workflow_transition_uk unique nulls not distinct (workflow_id, from_status_id, to_status_id)
);

create table workflow_scheme (
    id          bigint generated always as identity primary key,
    name        text not null,
    description text
);
create unique index workflow_scheme_name_uk on workflow_scheme (lower(name));

create table workflow_scheme_entry (
    id                 bigint generated always as identity primary key,
    workflow_scheme_id bigint not null references workflow_scheme (id) on delete cascade,
    issue_type_id      bigint references issue_type (id) on delete cascade,  -- null = scheme default
    workflow_id        bigint not null references workflow (id),
    constraint workflow_scheme_entry_uk unique nulls not distinct (workflow_scheme_id, issue_type_id)
);

create table permission_scheme (
    id          bigint generated always as identity primary key,
    name        text not null,
    description text
);
create unique index permission_scheme_name_uk on permission_scheme (lower(name));

create table permission_grant (
    id                   bigint generated always as identity primary key,
    permission_scheme_id bigint not null references permission_scheme (id) on delete cascade,
    permission           text   not null,
    holder_type          text   not null,
    holder_ref           bigint,  -- project_role / user_group / user_account id, depending on holder_type
    constraint permission_grant_permission_ck check (permission in (
        'BROWSE_PROJECT', 'CREATE_ISSUE', 'EDIT_ISSUE', 'DELETE_ISSUE', 'ASSIGN_ISSUE',
        'ASSIGNABLE_USER', 'TRANSITION_ISSUE', 'ADD_COMMENT', 'EDIT_ALL_COMMENTS',
        'EDIT_OWN_COMMENTS', 'DELETE_ALL_COMMENTS', 'DELETE_OWN_COMMENTS', 'CREATE_ATTACHMENT',
        'DELETE_ALL_ATTACHMENTS', 'DELETE_OWN_ATTACHMENTS', 'LINK_ISSUE', 'MANAGE_WATCHERS',
        'ADMINISTER_PROJECT')),
    constraint permission_grant_holder_type_ck check (holder_type in (
        'PROJECT_ROLE', 'GROUP', 'USER', 'REPORTER', 'ASSIGNEE', 'PROJECT_LEAD',
        'ANYONE_LOGGED_IN', 'ANONYMOUS')),
    constraint permission_grant_holder_ref_ck check (
        (holder_type in ('PROJECT_ROLE', 'GROUP', 'USER')) = (holder_ref is not null)),
    constraint permission_grant_uk unique nulls not distinct
        (permission_scheme_id, permission, holder_type, holder_ref)
);
