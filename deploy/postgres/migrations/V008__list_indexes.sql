-- List and paging queries: audit log newest first, member directory by name, Desktop sessions newest first.
create index if not exists audit_events_recent on audit_events(organization_id,occurred_at);
create index if not exists members_directory on members(organization_id,display_name);
create index if not exists desktop_body_sessions_recent on member_desktop_body_sessions(organization_id,deleted_at,created_at);
