package com.techflag.workdsh.admin;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-way move of existing inline text to the external store, enabled with workdsh.body-store.migrate-existing
 * while an external store is configured. Runs in the background after startup, in small batches, and can be
 * interrupted and restarted at any point: each row is uploaded first and switched to its object only if it is
 * still inline.
 */
@Component
public class BodyStoreMigration {
    private static final Logger log = LoggerFactory.getLogger("workdsh.maintenance");
    private static final int BATCH = 200;

    private final JdbcTemplate db;
    private final BodyStore store;
    private final boolean enabled;

    public BodyStoreMigration(JdbcTemplate db, BodyStore store, @Value("${workdsh.body-store.migrate-existing:false}") boolean enabled) {
        this.db = db; this.store = store; this.enabled = enabled;
    }

    private record Row(String organizationId, String memberId, String clientSessionId, long seq, String text) {}

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) return;
        if (!store.external()) { log.warn("maintenance body-migration skipped: body store is the database"); return; }
        var worker = new Thread(() -> {
            try { log.info("maintenance body-migration moved={}", migrate()); }
            catch (RuntimeException e) { log.warn("maintenance body-migration stopped reason={}", e.getClass().getSimpleName()); }
        }, "body-store-migration");
        worker.setDaemon(true);
        worker.start();
    }

    /** Moves every inline row; returns how many rows were moved. */
    long migrate() {
        long moved = 0;
        while (true) {
            List<Row> batch = db.query("select organization_id,member_id,client_session_id,record_seq,body_text from member_desktop_body_records where storage_key is null and body_text is not null limit " + BATCH,
                    (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)));
            if (batch.isEmpty()) return moved;
            for (var row : batch) {
                String key = BodyObjects.key(row.organizationId(), row.memberId(), row.clientSessionId(), row.seq());
                store.put(key, row.text());
                moved += db.update("update member_desktop_body_records set storage_key=?,body_text=null,body_sha256=coalesce(body_sha256,?) where organization_id=? and member_id=? and client_session_id=? and record_seq=? and storage_key is null",
                        key, BodyObjects.sha256(row.text()), row.organizationId(), row.memberId(), row.clientSessionId(), row.seq());
            }
        }
    }
}
