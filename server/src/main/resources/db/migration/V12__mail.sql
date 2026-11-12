-- E-mail notifications: languages, editable mail templates and outgoing mail.
-- See docs/domain-model.md §10.

-- Languages in which mails (and later the UI) are available. Users choose one in their profile.
create table language (
    code       text    primary key,
    name       text    not null,
    enabled    boolean not null default true,
    is_default boolean not null default false,
    constraint language_code_ck check (code ~ '^[a-z]{2,3}(-[a-z0-9]{2,8})?$'),
    constraint language_default_enabled_ck check (not is_default or enabled)
);
create unique index language_single_default_uk on language (is_default) where is_default;

insert into language (code, name, enabled, is_default) values
    ('en', 'English', true, true),
    ('nl', 'Nederlands', true, false);

-- Templates changed by administrators. Without a row, the built-in default
-- (classpath mail-templates/<language>/<key>.html) is used.
create table mail_template (
    template_key  text        not null,
    language_code text        not null references language (code) on delete cascade,
    subject       text        not null,
    body          text        not null,
    updated_at    timestamptz not null,
    updated_by    bigint      references user_account (id) on delete set null,
    primary key (template_key, language_code)
);

-- Issue events are bundled per issue and actor; the issue may be deleted meanwhile, so no FK.
alter table outbox_event
    add column issue_id bigint,
    add column actor_id bigint;
create index outbox_event_issue_idx on outbox_event (issue_id, actor_id) where processed_at is null;

-- Rendered mails waiting to be sent, sent, or given up on.
create table mail_message (
    id              bigint generated always as identity primary key,
    recipient_id    bigint      references user_account (id) on delete set null,
    to_address      text        not null,
    subject         text        not null,
    html_body       text        not null,
    text_body       text        not null,
    status          text        not null default 'PENDING',
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null,
    last_error      text,
    created_at      timestamptz not null,
    sent_at         timestamptz,
    constraint mail_message_status_ck check (status in ('PENDING', 'SENT', 'FAILED'))
);
create index mail_message_pending_idx on mail_message (next_attempt_at) where status = 'PENDING';
create index mail_message_created_idx on mail_message (created_at);
