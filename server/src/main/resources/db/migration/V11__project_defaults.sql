-- Defaults applied when a project is created. See docs/domain-model.md §7.

-- Which permission / workflow scheme a new project gets.
alter table permission_scheme add column is_default boolean not null default false;
update permission_scheme set is_default = true where name = 'Default Permission Scheme';
create unique index permission_scheme_single_default_uk on permission_scheme (is_default) where is_default;

alter table workflow_scheme add column is_default boolean not null default false;
update workflow_scheme set is_default = true where name = 'Default Workflow Scheme';
create unique index workflow_scheme_single_default_uk on workflow_scheme (is_default) where is_default;

-- Groups that are added to a project role when a project is created.
-- (The project lead is always added to the Administrators role as well.)
create table project_role_default_actor (
    project_role_id bigint not null references project_role (id) on delete cascade,
    group_id        bigint not null references user_group (id) on delete cascade,
    primary key (project_role_id, group_id)
);

insert into project_role_default_actor (project_role_id, group_id)
select r.id, g.id
from (values ('Users', 'users'), ('Administrators', 'administrators')) as d (role_name, group_name)
join project_role r on r.name = d.role_name
join user_group g on g.name = d.group_name;
