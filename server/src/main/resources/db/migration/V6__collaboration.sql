-- Comments, attachments, issue links and watchers. See docs/domain-model.md §6, §9.

create table comment (
    id               bigint generated always as identity primary key,
    issue_id         bigint      not null references issue (id) on delete cascade,
    author_id        bigint      references user_account (id) on delete set null,
    body             text        not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    update_author_id bigint      references user_account (id) on delete set null,
    version          bigint      not null default 0,
    search_vector    tsvector generated always as (
        setweight(to_tsvector('simple'::regconfig, body), 'C') ||
        setweight(to_tsvector('english'::regconfig, body), 'C')
    ) stored
);
create index comment_issue_idx on comment (issue_id, created_at);
create index comment_author_idx on comment (author_id);
create index comment_search_vector_idx on comment using gin (search_vector);

create table attachment (
    id          bigint generated always as identity primary key,
    issue_id    bigint      not null references issue (id) on delete cascade,
    author_id   bigint      references user_account (id) on delete set null,
    file_name   text        not null,
    media_type  text        not null,
    size_bytes  bigint      not null,
    sha256      text        not null,
    storage_key text        not null unique,
    created_at  timestamptz not null,
    constraint attachment_size_ck check (size_bytes >= 0)
);
create index attachment_issue_idx on attachment (issue_id);

create table issue_link (
    id              bigint generated always as identity primary key,
    link_type_id    bigint      not null references issue_link_type (id) on delete cascade,
    source_issue_id bigint      not null references issue (id) on delete cascade,
    target_issue_id bigint      not null references issue (id) on delete cascade,
    created_at      timestamptz not null,
    constraint issue_link_not_self_ck check (source_issue_id <> target_issue_id),
    constraint issue_link_uk unique (link_type_id, source_issue_id, target_issue_id)
);
create index issue_link_source_idx on issue_link (source_issue_id);
create index issue_link_target_idx on issue_link (target_issue_id);

create table watcher (
    issue_id   bigint      not null references issue (id) on delete cascade,
    user_id    bigint      not null references user_account (id) on delete cascade,
    created_at timestamptz not null,
    primary key (issue_id, user_id)
);
create index watcher_user_idx on watcher (user_id);
