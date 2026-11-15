-- Two-factor authentication (TOTP, RFC 6238), used when buggywuggy.security.two-factor.enabled
-- is on. A user without a row here goes through the enrollment wizard at the next login; an
-- administrator resets a user's two-factor login by deleting the row and the recovery codes.
create table user_two_factor (
    user_id     bigint      primary key references user_account (id) on delete cascade,
    -- The TOTP secret, encrypted with AES-GCM under data-dir/secrets/two-factor.key:
    -- 12-byte nonce followed by ciphertext and tag.
    secret      bytea       not null,
    enrolled_at timestamptz not null,
    -- The last accepted time step (Unix time / 30 s): a code is never accepted twice.
    last_step   bigint      not null
);

-- One-time codes for logging in without the authenticator app; SHA-256 of the code.
create table user_recovery_code (
    id        bigint      generated always as identity primary key,
    user_id   bigint      not null references user_account (id) on delete cascade,
    code_hash bytea       not null,
    used_at   timestamptz,
    unique (user_id, code_hash)
);
