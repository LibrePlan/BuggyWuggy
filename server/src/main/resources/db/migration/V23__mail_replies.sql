-- Answers by e-mail to external comments, and splitting an answer off into an issue of its own.

-- Message-ID: of the mail an external comment was sent as, or of the mail a received answer
-- came in. A received answer points at the external comment it answers, keeps the text it
-- quoted apart, and may have been split off into another issue.
alter table comment
    add column mail_message_id    text,
    add column received           boolean not null default false,
    add column reply_to_comment_id bigint references comment (id) on delete set null,
    add column quoted             text,
    add column split_issue_id     bigint references issue (id) on delete set null;
create unique index comment_mail_message_uk on comment (mail_message_id) where external;

-- Files that came with an answer, so that splitting it off can take them along.
alter table attachment add column comment_id bigint references comment (id) on delete set null;

-- Who an issue's external comments go to: the sender of the mail it was made of, or of the
-- answer it was split off from.
create table issue_mail_contact (
    issue_id   bigint primary key references issue (id) on delete cascade,
    mailbox    text not null,
    address    text not null,
    name       text,
    subject    text,
    message_id text
);
insert into issue_mail_contact (issue_id, mailbox, address, name, subject, message_id)
select distinct on (issue_id) issue_id, mailbox, sender, sender_name, subject, message_id
from inbound_mail
where action = 'CREATED' and issue_id is not null and sender is not null
order by issue_id, id;

alter table inbound_mail drop constraint inbound_mail_action_ck;
alter table inbound_mail add constraint inbound_mail_action_ck check (action in ('CREATED', 'COMMENTED', 'SPAM', 'IGNORED'));
alter table inbound_mail add column comment_id bigint references comment (id) on delete set null;

insert into issue_link_type (name, outward_description, inward_description)
select 'Split', 'split to', 'split from'
where not exists (select 1 from issue_link_type where lower(name) = 'split');
