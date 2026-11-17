-- The issue type Feature: a new capability or an improvement, after Story. Importers map
-- "feature", "new feature", "enhancement" and "improvement" to it.
update issue_type set position = position + 1
where position > (select position from issue_type where lower(name) = 'story')
  and not exists (select 1 from issue_type where lower(name) = 'feature');

insert into issue_type (name, description, icon, hierarchy_level, position)
select 'Feature', 'A new capability or an improvement.', 'feature', 0,
       coalesce((select position from issue_type where lower(name) = 'story'), 0) + 1
where not exists (select 1 from issue_type where lower(name) = 'feature');
