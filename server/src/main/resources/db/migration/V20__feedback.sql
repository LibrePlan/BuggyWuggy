-- Feedback of donors to feedback@buggywuggy.org (Help → Send feedback).
--
-- The donation code itself, so that a feedback mail can prove that it comes from a supporting
-- installation; null for codes entered before this version (entering the code again fills it).
alter table license add column code text;

-- Mails that a person wrote (feedback) get a Reply-To with their address, and headers that
-- describe them ("Name: value" per line).
alter table mail_message add column reply_to text;
alter table mail_message add column headers text;
