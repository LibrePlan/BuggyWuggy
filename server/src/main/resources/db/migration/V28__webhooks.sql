-- Outgoing webhooks: BuggyWuggy posts what happens to issues to a URL, signed with a secret.

create table webhook (
    id               bigint generated always as identity primary key,
    name             text        not null,
    url              text        not null,
    -- issue.created, issue.changed, issue.commented, issue.deleted
    events           text[]      not null,
    -- All projects, or only those in webhook_project (a removed project does not widen it).
    all_projects     boolean     not null,
    active           boolean     not null,
    -- The signing secret, encrypted with data-dir/secrets/webhook.key.
    secret_encrypted bytea       not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create table webhook_project (
    webhook_id bigint not null references webhook (id) on delete cascade,
    project_id bigint not null references project (id) on delete cascade,
    primary key (webhook_id, project_id)
);

-- One post of one event to one webhook. The body is kept as sent: the signature covers it.
create table webhook_delivery (
    id              bigint generated always as identity primary key,
    webhook_id      bigint      not null references webhook (id) on delete cascade,
    event_type      text        not null,
    body            text        not null,
    status          text        not null default 'PENDING',
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null,
    created_at      timestamptz not null,
    sent_at         timestamptz,
    response_status int,
    last_error      text
);

create index webhook_delivery_pending on webhook_delivery (webhook_id, id) where status = 'PENDING';
create index webhook_delivery_webhook on webhook_delivery (webhook_id, created_at);
