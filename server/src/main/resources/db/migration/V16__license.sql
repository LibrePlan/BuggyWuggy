-- Quotation requests for a license, each with the challenge that the signed license code
-- must answer, and the licenses entered by administrators. A valid license hides the
-- donation text on the login page until it expires.
create table license_request (
    challenge     text primary key,
    created_at    timestamptz not null,
    created_by    bigint references user_account (id) on delete set null,
    country_code  text        not null,
    users         int         not null,
    currency      text        not null,
    yearly_total  numeric     not null,
    company_name  text        not null,
    contact_name  text        not null,
    contact_email text        not null,
    contact_phone text
);

create table license (
    id               bigint generated always as identity primary key,
    challenge        text        not null references license_request (challenge),
    valid_until      date        not null,
    activated_at     timestamptz not null,
    activated_by     bigint references user_account (id) on delete set null,
    -- When sales@buggywuggy.org was told that the license is about to expire.
    reminder_sent_at timestamptz,
    constraint license_challenge_uk unique (challenge)
);
