-- Moving an issue to another project. The issue keeps its id and gets a number in the new
-- project; in its old place comes a closed stub issue with resolution Moved, a comment and a
-- Moved link to it.

insert into resolution (name, description, position, is_default)
select 'Moved', 'The issue was moved to another project.', coalesce(max(position), 0) + 1, false from resolution
where not exists (select 1 from resolution where lower(name) = 'moved');

insert into issue_link_type (name, outward_description, inward_description)
select 'Moved', 'moved to', 'moved from'
where not exists (select 1 from issue_link_type where lower(name) = 'moved');
