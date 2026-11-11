-- Default data for a fresh installation. No users are created here: the first
-- administrator is created through the setup flow.

insert into user_group (name, description) values
    ('administrators', 'BuggyWuggy administrators'),
    ('users',          'All BuggyWuggy users');

insert into global_permission_grant (permission, group_id)
select p.permission, g.id
from (values ('ADMINISTER', 'administrators'),
             ('CREATE_PROJECT', 'administrators'),
             ('BROWSE_USERS', 'users')) as p (permission, group_name)
join user_group g on g.name = p.group_name;

insert into issue_type (name, description, icon, hierarchy_level, position) values
    ('Epic',     'A large body of work that contains other issues.', 'epic',     1, 1),
    ('Story',    'A requirement expressed from a user''s perspective.', 'story', 0, 2),
    ('Task',     'A piece of work that needs to be done.',          'task',     0, 3),
    ('Bug',      'A problem that impairs or prevents functionality.', 'bug',    0, 4),
    ('Sub-task', 'A smaller piece of work within another issue.',   'subtask', -1, 5);

insert into priority (name, description, icon, color, position, is_default) values
    ('Highest', 'Blocks development or production use.',     'highest', '#cd1317', 1, false),
    ('High',    'Serious problem that could block progress.', 'high',    '#e9494a', 2, false),
    ('Medium',  'Has the potential to affect progress.',      'medium',  '#e97f33', 3, true),
    ('Low',     'Minor problem or easily worked around.',     'low',     '#2d8738', 4, false),
    ('Lowest',  'Trivial problem with little or no impact.',  'lowest',  '#57a55a', 5, false);

insert into resolution (name, description, position, is_default) values
    ('Fixed',            'A fix for this issue has been implemented.',                  1, false),
    ('Won''t Fix',       'The problem described is an issue which will never be fixed.', 2, false),
    ('Duplicate',        'The problem is a duplicate of an existing issue.',            3, false),
    ('Cannot Reproduce', 'The problem could not be reproduced.',                        4, false),
    ('Done',             'Work has been completed on this issue.',                      5, true);

insert into status (name, description, category, position) values
    ('Open',        'The issue is open and ready to be worked on.',              'TODO',        1),
    ('In Progress', 'The issue is actively being worked on.',                    'IN_PROGRESS', 2),
    ('Reopened',    'The issue was resolved, but was found to need more work.',  'TODO',        3),
    ('Resolved',    'A resolution has been taken; awaiting verification.',       'DONE',        4),
    ('Closed',      'The issue is considered finished.',                         'DONE',        5);

insert into issue_link_type (name, outward_description, inward_description) values
    ('Blocks',    'blocks',     'is blocked by'),
    ('Relates',   'relates to', 'relates to'),
    ('Duplicate', 'duplicates', 'is duplicated by'),
    ('Cloners',   'clones',     'is cloned by');

insert into project_role (name, description) values
    ('Administrators', 'Administers the project.'),
    ('Developers',     'Works on issues in the project.'),
    ('Users',          'Reports and follows issues in the project.');

-- Default workflow -----------------------------------------------------------

insert into workflow (name, description) values
    ('Default', 'The default BuggyWuggy workflow.');

insert into workflow_status (workflow_id, status_id, initial)
select w.id, s.id, s.name = 'Open'
from workflow w cross join status s
where w.name = 'Default';

insert into workflow_transition (workflow_id, name, from_status_id, to_status_id, position)
select w.id, t.name, f.id, d.id, t.position
from (values ('Start Progress', 'Open',        'In Progress', 1),
             ('Start Progress', 'Reopened',    'In Progress', 1),
             ('Stop Progress',  'In Progress', 'Open',        2),
             ('Resolve Issue',  'Open',        'Resolved',    3),
             ('Resolve Issue',  'In Progress', 'Resolved',    3),
             ('Resolve Issue',  'Reopened',    'Resolved',    3),
             ('Close Issue',    'Open',        'Closed',      4),
             ('Close Issue',    'In Progress', 'Closed',      4),
             ('Close Issue',    'Reopened',    'Closed',      4),
             ('Close Issue',    'Resolved',    'Closed',      4),
             ('Reopen Issue',   'Resolved',    'Reopened',    5),
             ('Reopen Issue',   'Closed',      'Reopened',    5))
     as t (name, from_status, to_status, position)
join workflow w on w.name = 'Default'
join status f on f.name = t.from_status
join status d on d.name = t.to_status;

insert into workflow_scheme (name, description) values
    ('Default Workflow Scheme', 'Uses the Default workflow for all issue types.');

insert into workflow_scheme_entry (workflow_scheme_id, issue_type_id, workflow_id)
select ws.id, null, w.id
from workflow_scheme ws join workflow w on w.name = 'Default'
where ws.name = 'Default Workflow Scheme';

-- Default permission scheme ----------------------------------------------------

insert into permission_scheme (name, description) values
    ('Default Permission Scheme', 'Grants permissions based on project roles.');

insert into permission_grant (permission_scheme_id, permission, holder_type, holder_ref)
select ps.id, g.permission, 'PROJECT_ROLE', r.id
from (values
        -- Users: report and follow issues
        ('Users', 'BROWSE_PROJECT'), ('Users', 'CREATE_ISSUE'), ('Users', 'ADD_COMMENT'),
        ('Users', 'EDIT_OWN_COMMENTS'), ('Users', 'DELETE_OWN_COMMENTS'),
        ('Users', 'CREATE_ATTACHMENT'), ('Users', 'DELETE_OWN_ATTACHMENTS'),
        -- Developers: work on issues
        ('Developers', 'BROWSE_PROJECT'), ('Developers', 'CREATE_ISSUE'), ('Developers', 'EDIT_ISSUE'),
        ('Developers', 'ASSIGN_ISSUE'), ('Developers', 'ASSIGNABLE_USER'), ('Developers', 'TRANSITION_ISSUE'),
        ('Developers', 'ADD_COMMENT'), ('Developers', 'EDIT_OWN_COMMENTS'), ('Developers', 'DELETE_OWN_COMMENTS'),
        ('Developers', 'CREATE_ATTACHMENT'), ('Developers', 'DELETE_OWN_ATTACHMENTS'),
        ('Developers', 'LINK_ISSUE'), ('Developers', 'MANAGE_WATCHERS'),
        -- Administrators: everything
        ('Administrators', 'BROWSE_PROJECT'), ('Administrators', 'CREATE_ISSUE'), ('Administrators', 'EDIT_ISSUE'),
        ('Administrators', 'DELETE_ISSUE'), ('Administrators', 'ASSIGN_ISSUE'), ('Administrators', 'ASSIGNABLE_USER'),
        ('Administrators', 'TRANSITION_ISSUE'), ('Administrators', 'ADD_COMMENT'),
        ('Administrators', 'EDIT_ALL_COMMENTS'), ('Administrators', 'EDIT_OWN_COMMENTS'),
        ('Administrators', 'DELETE_ALL_COMMENTS'), ('Administrators', 'DELETE_OWN_COMMENTS'),
        ('Administrators', 'CREATE_ATTACHMENT'), ('Administrators', 'DELETE_ALL_ATTACHMENTS'),
        ('Administrators', 'DELETE_OWN_ATTACHMENTS'), ('Administrators', 'LINK_ISSUE'),
        ('Administrators', 'MANAGE_WATCHERS'), ('Administrators', 'ADMINISTER_PROJECT'))
     as g (role_name, permission)
join project_role r on r.name = g.role_name
cross join permission_scheme ps
where ps.name = 'Default Permission Scheme';
