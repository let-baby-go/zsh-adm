package com.techflag.workdsh.admin;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies each organization's body_retention_days: a Desktop session whose last upload is older than that is
 * erased exactly like a member's own delete (text removed, tombstone kept). 0 days keeps text indefinitely.
 */
@Component
public class DesktopBodyRetention {
    private static final Logger log = LoggerFactory.getLogger("workdsh.maintenance");
    private static final int BATCH = 100;

    private final JdbcTemplate db;
    private final DatabaseDialect dialect;
    private final DesktopVisibleSessions sessions;
    private final TransactionTemplate transactions;

    public DesktopBodyRetention(JdbcTemplate db, DatabaseDialect dialect, DesktopVisibleSessions sessions, TransactionTemplate transactions) {
        this.db = db; this.dialect = dialect; this.sessions = sessions; this.transactions = transactions;
    }

    private record Expired(String organizationId, String memberId, String clientSessionId) {}

    private static final String LAST_ACTIVITY = "(case when last_received_at=0 then created_at else last_received_at end)";

    /** Erases expired sessions, one transaction per session; returns how many were erased. */
    public int purge(long now) {
        int erased = 0;
        var policies = db.query("select organization_id,body_retention_days from organization_policies where body_retention_days>0",
                (rs, n) -> new Object[] {rs.getString(1), rs.getInt(2)});
        for (Object[] policy : policies) {
            String org = (String) policy[0];
            long cutoff = now - TimeUnit.DAYS.toMillis((Integer) policy[1]);
            while (true) {
                var batch = db.query("select organization_id,member_id,client_session_id from member_desktop_body_sessions where organization_id=? and deleted_at=0 and "
                                + LAST_ACTIVITY + "<? order by " + LAST_ACTIVITY + " limit " + BATCH,
                        (rs, n) -> new Expired(rs.getString(1), rs.getString(2), rs.getString(3)), org, cutoff);
                if (batch.isEmpty()) break;
                for (var session : batch) {
                    // Re-check under the row lock: an upload may have arrived since the batch was read.
                    Boolean done = transactions.execute(status -> {
                        var still = db.queryForList(dialect.lock("select client_session_id from member_desktop_body_sessions where organization_id=? and member_id=? and client_session_id=? and deleted_at=0 and "
                                + LAST_ACTIVITY + "<?"), String.class, session.organizationId(), session.memberId(), session.clientSessionId(), cutoff);
                        if (still.isEmpty()) return false;
                        sessions.erase(session.organizationId(), session.memberId(), session.clientSessionId(), now);
                        return true;
                    });
                    if (Boolean.TRUE.equals(done)) erased++;
                }
                if (batch.size() < BATCH) break;
            }
        }
        if (erased > 0) log.info("maintenance body-retention erasedSessions={}", erased);
        return erased;
    }
}
