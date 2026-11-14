package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Organization body retention, its policy validation, and the daily maintenance steps. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:retention-test?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=retention@example.test",
        "workdsh.bootstrap.admin-password=RetentionPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class RetentionAndMaintenanceTest {
    @Autowired OrganizationController organization;
    @Autowired DesktopVisibleSessions sessions;
    @Autowired DesktopBodyRetention retention;
    @Autowired MaintenanceJobs maintenance;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    private JsonNode snapshot(String session, String text) {
        ObjectNode root = json.createObjectNode().put("version", 1).put("deviceId", "device-r").put("sessionId", session)
                .put("revision", 1).put("requestId", session + "-q1");
        root.putArray("entries").addObject().put("seq", 0).put("recordId", session + "-r0").put("role", "user").put("text", text);
        return root;
    }

    @Test
    void retentionErasesOnlyExpiredSessionsOfThatOrganization() {
        String bearer = "Bearer " + auth.login("retention@example.test", "RetentionPassword123!").token();
        String org = auth.actor(bearer).organizationId();
        var current = organization.policy(org);
        assertEquals(0, current.bodyRetentionDays(), "text is kept indefinitely by default");
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> organization.updatePolicy(bearer,
                new OrganizationController.Policy("ORGANIZATION", true, current.revision(), 3651))).getStatusCode().value());
        var saved = organization.updatePolicy(bearer, new OrganizationController.Policy("ORGANIZATION", true, current.revision(), 30));
        assertEquals(30, saved.bodyRetentionDays());

        sessions.ingest(bearer, snapshot("old-session", "四十天前的正文"));
        sessions.ingest(bearer, snapshot("recent-session", "昨天的正文"));
        long now = System.currentTimeMillis();
        db.update("update member_desktop_body_sessions set last_received_at=? where client_session_id=?", now - TimeUnit.DAYS.toMillis(40), "old-session");
        db.update("update member_desktop_body_sessions set last_received_at=? where client_session_id=?", now - TimeUnit.DAYS.toMillis(1), "recent-session");

        assertEquals(1, retention.purge(now));
        assertEquals(0, db.queryForObject("select count(*) from member_desktop_body_records where client_session_id='old-session'", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from member_desktop_body_records where client_session_id='recent-session'", Integer.class));
        assertTrue(db.queryForObject("select deleted_at from member_desktop_body_sessions where client_session_id='old-session'", Long.class) > 0);
        assertTrue(sessions.status(bearer, "device-r", "old-session").deleted());
        assertEquals(0, retention.purge(now), "already erased sessions are not touched again");

        organization.updatePolicy(bearer, new OrganizationController.Policy("ORGANIZATION", true, saved.revision(), 0));
        db.update("update member_desktop_body_sessions set last_received_at=? where client_session_id=?", now - TimeUnit.DAYS.toMillis(4000), "recent-session");
        assertEquals(0, retention.purge(now), "0 days keeps text indefinitely");
    }

    @Test
    void maintenanceRemovesExpiredLoginsAndReportsCapacity() {
        String bearer = "Bearer " + auth.login("retention@example.test", "RetentionPassword123!").token();
        String member = auth.actor(bearer).id();
        db.update("insert into auth_sessions(token_hash,member_id,expires_at) values (?,?,?)", UUID.randomUUID().toString().replace("-", ""), member,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        assertTrue(maintenance.removeExpiredLogins(Instant.now()) >= 1);
        assertNotNull(auth.actor(bearer), "valid logins survive");
        var capacity = maintenance.logCapacity();
        assertEquals(MaintenanceJobs.GROWING_TABLES, capacity.keySet().stream().toList());
        maintenance.daily();
    }
}
