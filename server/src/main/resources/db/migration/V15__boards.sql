-- Kanban boards, one per project. Each column shows one or more statuses; moving an issue to
-- another column performs a workflow transition. The board without a project is the default,
-- set by the administrator: a project uses it until a project administrator gives the
-- project its own columns.
create table board (
    id            bigint generated always as identity primary key,
    project_id    bigint unique references project (id) on delete cascade,
    -- How the board is divided into horizontal lanes unless the user chooses otherwise.
    swimlanes     text not null default 'NONE',
    -- Resolved issues stay on the board for this many days after they were resolved.
    resolved_days int  not null default 14,
    constraint board_swimlanes_ck check (swimlanes in ('NONE', 'ASSIGNEE', 'EPIC', 'PRIORITY')),
    constraint board_resolved_days_ck check (resolved_days between 0 and 365)
);
create unique index board_single_default_uk on board ((project_id is null)) where project_id is null;

create table board_column (
    id       bigint generated always as identity primary key,
    board_id bigint not null references board (id) on delete cascade,
    name     text   not null,
    position int    not null
);
create index board_column_board_ix on board_column (board_id, position);

-- A status is in at most one column of a board.
create table board_column_status (
    board_column_id bigint not null references board_column (id) on delete cascade,
    board_id        bigint not null references board (id) on delete cascade,
    status_id       bigint not null references status (id) on delete cascade,
    primary key (board_column_id, status_id),
    constraint board_column_status_uk unique (board_id, status_id)
);

-- The order of the issues on the board of their project: lower first. Issues that were never
-- moved have none and come after the others, oldest first.
alter table issue add column board_rank double precision;
create index issue_board_rank_ix on issue (project_id, board_rank);

-- The default board: a column per status category.
insert into board (project_id, swimlanes, resolved_days) values (null, 'NONE', 14);
insert into board_column (board_id, name, position)
select b.id, c.name, c.position
from board b
cross join (values ('To Do', 0), ('In Progress', 1), ('Done', 2)) as c (name, position)
where b.project_id is null;
insert into board_column_status (board_column_id, board_id, status_id)
select c.id, c.board_id, s.id
from board_column c
join status s on (c.name = 'To Do' and s.category = 'TODO')
              or (c.name = 'In Progress' and s.category = 'IN_PROGRESS')
              or (c.name = 'Done' and s.category = 'DONE');
