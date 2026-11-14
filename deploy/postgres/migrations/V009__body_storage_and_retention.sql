-- Organization body retention, last activity per Desktop session, and optional external body storage.
alter table organization_policies add column if not exists body_retention_days integer not null default 0;
alter table member_desktop_body_sessions add column if not exists last_received_at bigint not null default 0;
update member_desktop_body_sessions set last_received_at=created_at where last_received_at=0;
alter table member_desktop_body_records alter column body_text drop not null;
alter table member_desktop_body_records add column if not exists body_sha256 varchar(64);
alter table member_desktop_body_records add column if not exists storage_key varchar(512);
