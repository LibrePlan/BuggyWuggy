-- Existing statuses and workflow schemes remain shared until a project adds a board status.
alter table status add column project_id bigint references project (id) on delete cascade;
drop index status_name_uk;
create unique index status_name_uk on status (project_id, lower(name)) nulls not distinct;

alter table workflow_scheme add column project_id bigint unique references project (id) on delete cascade;
alter table workflow add column project_id bigint references project (id) on delete cascade;
