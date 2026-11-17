-- Single sign-on with OpenID Connect (buggywuggy.security.sso). An identity at a provider
-- (the "sub" claim, which never changes, unlike usernames and e-mail addresses) belongs to one
-- account; an account has at most one identity per provider.
create table user_identity (
    id            bigint      generated always as identity primary key,
    user_id       bigint      not null references user_account (id) on delete cascade,
    -- The provider's id in the configuration, e.g. "keycloak".
    provider      text        not null,
    subject       text        not null,
    -- The e-mail address the provider gave at the last login, for administrators.
    email         text,
    created_at    timestamptz not null,
    last_login_at timestamptz,
    unique (provider, subject),
    unique (user_id, provider)
);
