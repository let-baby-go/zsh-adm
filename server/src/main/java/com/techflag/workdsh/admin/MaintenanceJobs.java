package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily housekeeping, by default at 03:30 Beijing time (workdsh.maintenance.cron / .zone):
 * expired login sessions are removed, organization body retention is applied, and table sizes are logged.
 * Each step runs even if an earlier one fails.
 */
@Component
@EnableScheduling
public class MaintenanceJobs {
    private static final Logger log = LoggerFactory.getLogger("workdsh.maintenance");
    /** Tables that only grow without retention; their row counts are the capacity signal. */
    static final List<String> GROWING_TABLES = List.of("member_desktop_body_records", "member_desktop_body_receipts",
            "member_desktop_body_sessions", "audit_events", "collaboration_materials", "collaboration_notifications",
            "collaboration_messages", "auth_sessions");

    private final JdbcTemplate db;
    private final DesktopBodyRetention retention;

    public MaintenanceJobs(JdbcTemplate db, DesktopBodyRetention retention) { this.db = db; this.retention = retention; }

    @Scheduled(cron = "${workdsh.maintenance.cron:0 30 3 * * *}", zone = "${workdsh.maintenance.zone:Asia/Shanghai}")
    public void daily() {
        step("auth-sessions", () -> removeExpiredLogins(Instant.now()));
        step("body-retention", () -> retention.purge(System.currentTimeMillis()));
        step("capacity", this::logCapacity);
    }

    private static void step(String name, Runnable work) {
        try { work.run(); }
        catch (RuntimeException e) { log.warn("maintenance step={} failed reason={}", name, e.getClass().getSimpleName()); }
    }

    /** Expired login sessions are never valid again; deleted in batches to keep SQLite's single writer free. */
    int removeExpiredLogins(Instant now) {
        int total = 0;
        while (true) {
            var batch = db.queryForList("select token_hash from auth_sessions where expires_at<? limit 500", String.class, Timestamp.from(now));
            if (batch.isEmpty()) break;
            for (String token : batch) total += db.update("delete from auth_sessions where token_hash=?", token);
            if (batch.size() < 500) break;
        }
        if (total > 0) log.info("maintenance auth-sessions removed={}", total);
        return total;
    }

    Map<String, Long> logCapacity() {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : GROWING_TABLES) rows.put(table, db.queryForObject("select count(*) from " + table, Long.class));
        log.info("maintenance capacity rows={}", rows);
        return rows;
    }
}
