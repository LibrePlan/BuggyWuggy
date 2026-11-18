-- Issues from mailboxes, and comments that are sent to the person who mailed.
alter table import_source drop constraint import_source_kind_ck;
alter table import_source add constraint import_source_kind_ck
    check (kind in ('JIRA_BACKUP', 'JIRA_REST', 'JIRA', 'FORGEJO', 'GITLAB', 'GITHUB', 'REDMINE', 'TRAC', 'BUGZILLA',
                    'MAIL'));

-- External: also sent by e-mail to the person the issue came from. Internal (the default): only
-- for the people in BuggyWuggy.
alter table comment add column external boolean not null default false;

-- Every mail a mailbox handled: what became of it, and who sent it (for answers by e-mail). A
-- mail is handled once, also when moving it to its folder failed.
create table inbound_mail (
    id           bigint generated always as identity primary key,
    mailbox      text        not null,
    message_id   text        not null,
    sender       text,
    sender_name  text,
    subject      text,
    received_at  timestamptz,
    processed_at timestamptz not null,
    action       text        not null,
    reason       text,
    issue_id     bigint references issue (id) on delete set null,
    constraint inbound_mail_action_ck check (action in ('CREATED', 'SPAM', 'IGNORED')),
    constraint inbound_mail_uk unique (mailbox, message_id)
);
create index inbound_mail_issue_ix on inbound_mail (issue_id);
create index inbound_mail_processed_ix on inbound_mail (processed_at desc);
