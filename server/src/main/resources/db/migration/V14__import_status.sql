-- What a running import does right now, for the status line in the administration:
-- e.g. "Issue 12 of 140" or, while waiting for a rate limit, until when.
alter table import_job
    add column status_text   text,
    add column waiting_until timestamptz;
