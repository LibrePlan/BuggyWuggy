-- One optional attachment per queued mail (feedback: the details as JSON).
alter table mail_message add column attachment_name varchar(255);
alter table mail_message add column attachment_type varchar(100);
alter table mail_message add column attachment_data bytea;
