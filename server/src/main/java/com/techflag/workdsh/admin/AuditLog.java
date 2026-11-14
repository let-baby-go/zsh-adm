package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Organization operation log; the single writer and reader of audit_events. */
@Component
public class AuditLog {
    /** actorName is null when the actor no longer exists. */
    public record Entry(String id, String actorId, String action, String targetId, Instant occurredAt, String actorName) {}

    private final JdbcTemplate db;

    public AuditLog(JdbcTemplate db) { this.db = db; }

    /** Joins the caller's transaction, so a rolled-back operation leaves no audit row. */
    public void record(AuthService.Actor actor, String action, String targetId) {
        db.update("insert into audit_events(id,organization_id,actor_id,action,target_id,occurred_at) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actor.organizationId(), actor.id(), action, targetId,
                Timestamp.from(Instant.now()));
    }

    /** Newest first; served by index audit_events_recent(organization_id, occurred_at). */
    public List<Entry> page(String organizationId, int offset, int limit) {
        return db.query("""
                select a.id,a.actor_id,a.action,a.target_id,a.occurred_at,m.display_name
                from audit_events a left join members m on m.id=a.actor_id and m.organization_id=a.organization_id
                where a.organization_id=? order by a.occurred_at desc,a.id desc limit ? offset ?
                """,
                (rs, n) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getString(6)), organizationId, limit, offset);
    }
}
