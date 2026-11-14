package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Desktop visible text kept in an external store: write, read, hash comparison, erase, rollback and migration. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:external-body-test?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=external-body@example.test",
        "workdsh.bootstrap.admin-password=ExternalBodyPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class ExternalBodyStoreTest {
    /** In-memory stand-in for an object store. */
    static class MemoryStore implements BodyStore {
        final Map<String, String> objects = new ConcurrentHashMap<>();
        public boolean external() { return true; }
        public void put(String key, String text) { objects.put(key, text); }
        public String get(String key) { return objects.get(key); }
        public void delete(Collection<String> keys) { keys.forEach(objects::remove); }
    }

    @TestConfiguration
    static class Store {
        @Bean @Primary MemoryStore memoryBodyStore() { return new MemoryStore(); }
    }

    @Autowired MemoryStore store;
    @Autowired DesktopVisibleSessions sessions;
    @Autowired AdminSessionsController admin;
    @Autowired BodyObjects objects;
    @Autowired BodyStoreMigration migration;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate transactions;

    private String bearer;
    private String org;

    @BeforeEach
    void login() {
        bearer = "Bearer " + auth.login("external-body@example.test", "ExternalBodyPassword123!").token();
        org = auth.actor(bearer).organizationId();
    }

    private JsonNode snapshot(String session, int revision, String requestId, String... texts) {
        ObjectNode root = json.createObjectNode().put("version", 1).put("deviceId", "device-1").put("sessionId", session)
                .put("revision", revision).put("requestId", requestId);
        ArrayNode entries = root.putArray("entries");
        for (int i = 0; i < texts.length; i++) {
            entries.addObject().put("seq", i).put("recordId", session + "-r" + i).put("role", i % 2 == 0 ? "user" : "assistant").put("text", texts[i]);
        }
        return root;
    }

    @Test
    void textLivesInTheStoreAndRowsKeepOnlyHashes() {
        var first = sessions.ingest(bearer, snapshot("s-store", 1, "q1", "第一条问题"));
        sessions.ingest(bearer, snapshot("s-store", 2, "q2", "第一条问题", "第一条回答"));
        var rows = db.queryForList("select body_text,body_sha256,storage_key from member_desktop_body_records where organization_id=? and client_session_id=? order by record_seq", org, "s-store");
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertNull(row.get("body_text"));
            assertEquals(64, ((String) row.get("body_sha256")).length());
            assertTrue(store.objects.containsKey((String) row.get("storage_key")));
        }
        assertEquals("第一条回答", store.objects.get((String) rows.get(1).get("storage_key")));

        var conflict = assertThrows(ResponseStatusException.class,
                () -> sessions.ingest(bearer, snapshot("s-store", 3, "q3", "被改过的问题", "第一条回答", "新问题")));
        assertEquals(409, conflict.getStatusCode().value(), "accepted text is immutable, checked by hash");

        var detail = admin.detail(bearer, auth.actor(bearer).id(), first.sessionId(), 0);
        assertEquals("第一条问题", detail.messages().get(0).content().get(0).path("text").asText());
        assertEquals("第一条回答", detail.messages().get(1).content().get(0).path("text").asText());

        int before = store.objects.size();
        sessions.delete(bearer, "device-1", "s-store");
        assertEquals(before - 2, store.objects.size(), "objects are deleted once the erase commits");
        assertEquals(410, assertThrows(ResponseStatusException.class,
                () -> sessions.ingest(bearer, snapshot("s-store", 3, "q4", "第一条问题", "第一条回答", "新问题"))).getStatusCode().value());
    }

    @Test
    void objectsWrittenInARolledBackTransactionAreRemoved() {
        transactions.executeWithoutResult(status -> {
            objects.put("rollback-probe", "text");
            assertTrue(store.objects.containsKey("rollback-probe"));
            status.setRollbackOnly();
        });
        assertFalse(store.objects.containsKey("rollback-probe"));
    }

    @Test
    void existingInlineTextMigratesOnceAndStaysReadable() {
        String member = auth.actor(bearer).id();
        db.update("insert into member_desktop_body_sessions(organization_id,member_id,client_session_id,device_id,server_session_id,created_at,revision,record_count,deleted_at) values (?,?,?,?,?,?,1,2,0)",
                org, member, "s-legacy", "device-1", "desktop-body:legacy", System.currentTimeMillis());
        for (int i = 0; i < 2; i++) {
            db.update("insert into member_desktop_body_records(organization_id,member_id,client_session_id,record_seq,record_id,role,body_text,received_at) values (?,?,?,?,?,?,?,?)",
                    org, member, "s-legacy", i, "legacy-" + i, i == 0 ? "user" : "assistant", "旧正文" + i, System.currentTimeMillis());
        }
        assertTrue(migration.migrate() >= 2);
        assertEquals(0, migration.migrate(), "a second run finds nothing left");
        var rows = db.queryForList("select body_text,body_sha256,storage_key from member_desktop_body_records where client_session_id=?", "s-legacy");
        rows.forEach(row -> { assertNull(row.get("body_text")); assertNotNull(row.get("body_sha256")); assertNotNull(row.get("storage_key")); });
        var detail = admin.detail(bearer, member, "desktop-body:legacy", 0);
        assertEquals(List.of("旧正文0", "旧正文1"), detail.messages().stream().map(m -> m.content().get(0).path("text").asText()).toList());
    }
}
