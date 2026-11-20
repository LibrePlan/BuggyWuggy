-- Personal API tokens: scripts and other programs use the API with the rights of the user who
-- made the token. Only a SHA-256 hash of the token is kept; the token itself is shown once.
create table api_token (
    id           bigint generated always as identity primary key,
    user_id      bigint      not null references user_account (id) on delete cascade,
    name         text        not null,
    token_hash   text        not null unique,
    -- The first characters of the token, so its owner can tell tokens apart.
    prefix       text        not null,
    read_only    boolean     not null,
    created_at   timestamptz not null,
    -- The last day the token works; null: until it is removed.
    expires_on   date,
    last_used_at timestamptz
);

create index api_token_user on api_token (user_id);
