-- Admin-editable global lookup lists. See docs/domain-model.md §5.5, §6, §7.2.

create table issue_type (
    id              bigint generated always as identity primary key,
    name            text     not null,
    description     text,
    icon            text,
    hierarchy_level smallint not null,
    position        int      not null,
    constraint issue_type_hierarchy_level_ck check (hierarchy_level in (-1, 0, 1))
);
create unique index issue_type_name_uk on issue_type (lower(name));

create table priority (
    id          bigint generated always as identity primary key,
    name        text    not null,
    description text,
    icon        text,
    color       text,
    position    int     not null,
    is_default  boolean not null default false
);
create unique index priority_name_uk on priority (lower(name));
create unique index priority_single_default_uk on priority (is_default) where is_default;

create table resolution (
    id          bigint generated always as identity primary key,
    name        text    not null,
    description text,
    position    int     not null,
    is_default  boolean not null default false
);
create unique index resolution_name_uk on resolution (lower(name));
create unique index resolution_single_default_uk on resolution (is_default) where is_default;

create table status (
    id          bigint generated always as identity primary key,
    name        text not null,
    description text,
    category    text not null,
    position    int  not null,
    constraint status_category_ck check (category in ('TODO', 'IN_PROGRESS', 'DONE'))
);
create unique index status_name_uk on status (lower(name));

create table issue_link_type (
    id                  bigint generated always as identity primary key,
    name                text not null,
    outward_description text not null,
    inward_description  text not null
);
create unique index issue_link_type_name_uk on issue_link_type (lower(name));

create table project_role (
    id          bigint generated always as identity primary key,
    name        text not null,
    description text
);
create unique index project_role_name_uk on project_role (lower(name));
