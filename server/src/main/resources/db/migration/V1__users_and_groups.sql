-- Users, groups and global permissions. See docs/domain-model.md §3.

create table user_account (
    id            bigint generated always as identity primary key,
    username      text        not null,
    display_name  text        not null,
    email         text,
    password_hash text,
    active        boolean     not null default true,
    placeholder   boolean     not null default false,
    system_admin  boolean     not null default false,
    locale        text,
    time_zone     text,
    created_at    timestamptz not null,
    last_login_at timestamptz,
    version       bigint      not null default 0,
    constraint user_account_placeholder_inactive check (not placeholder or not active)
);
create unique index user_account_username_uk on user_account (lower(username));
create unique index user_account_email_uk on user_account (lower(email)) where email is not null;

create table user_group (
    id          bigint generated always as identity primary key,
    name        text not null,
    description text
);
create unique index user_group_name_uk on user_group (lower(name));

create table group_membership (
    group_id bigint not null references user_group (id) on delete cascade,
    user_id  bigint not null references user_account (id) on delete cascade,
    primary key (group_id, user_id)
);
create index group_membership_user_idx on group_membership (user_id);

create table password_reset_token (
    id         bigint generated always as identity primary key,
    user_id    bigint      not null references user_account (id) on delete cascade,
    token_hash text        not null unique,
    expires_at timestamptz not null,
    used_at    timestamptz
);
create index password_reset_token_user_idx on password_reset_token (user_id);

create table global_permission_grant (
    id         bigint generated always as identity primary key,
    permission text   not null,
    group_id   bigint not null references user_group (id) on delete cascade,
    constraint global_permission_grant_permission_ck
        check (permission in ('ADMINISTER', 'CREATE_PROJECT', 'BROWSE_USERS')),
    constraint global_permission_grant_uk unique (permission, group_id)
);
