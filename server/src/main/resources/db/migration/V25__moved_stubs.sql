-- Resolutions only BuggyWuggy itself sets (Moved): not offered when resolving an issue.
alter table resolution add column selectable boolean not null default true;
update resolution set selectable = false where lower(name) = 'moved';

-- The stub that stays behind when an issue moves to another project points to it, and cannot
-- be transitioned or moved itself. When the moved issue is deleted, the stub is an ordinary
-- closed issue again.
alter table issue add column moved_to_id bigint references issue (id) on delete set null;
create index issue_moved_to_idx on issue (moved_to_id) where moved_to_id is not null;

update issue s set moved_to_id = l.target_issue_id
from issue_link l join issue_link_type t on t.id = l.link_type_id
where lower(t.name) = 'moved' and l.source_issue_id = s.id;
