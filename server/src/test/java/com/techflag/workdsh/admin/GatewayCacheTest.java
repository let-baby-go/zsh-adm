package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The gateway's per-call lookups stay out of the database while saves and key rotation still apply at once. */
class GatewayCacheTest {
    private static final String KEY = "cache-test-access-key-with-32-characters";
    private static final String NEXT_KEY = "cache-test-rotated-key-with-32-characters";

    abstract static class Scenario {
        @Autowired ModelProvidersController controller;
        @Autowired ModelProviders providers;
        @Autowired ModelGatewayKeys keys;
        @Autowired AuthService auth;
        @Autowired ObjectMapper json;

        abstract String admin();
        abstract JdbcTemplate db();

        String bearer;
        String org;

        static ModelProviders.Provider provider(String model) {
            return new ModelProviders.Provider("company", "Company", "https://api.example.com", "openai-completions", true,
                    List.of(new ModelProviders.Model(model, model, null, null, false)), false, "supplier-key-cache-test");
        }

        void save(String model, String accessKey) {
            bearer = "Bearer " + auth.login(admin(), "CachePassword123!").token();
            org = auth.actor(bearer).organizationId();
            long revision = db().queryForList("select revision from organization_model_providers where organization_id=?", Long.class, org).stream().findFirst().orElse(0L);
            controller.update(bearer, new ModelProviders.Update(revision, List.of(provider(model)), accessKey));
        }

        /** Simulates another instance changing the model list behind this one's back. */
        void renameModelDirectly(String model) throws Exception {
            var config = json.readTree(db().queryForObject("select providers_json from organization_model_providers where organization_id=?", String.class, org));
            ((ObjectNode) config.get(0).get("models").get(0)).put("id", model);
            db().update("update organization_model_providers set providers_json=? where organization_id=?", json.writeValueAsString(config), org);
        }

        long gatewayQueries() {
            return mockingDetails(db()).getInvocations().stream()
                    .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String sql
                            && (sql.contains("from organization_model_gateway ") || sql.contains("from organization_model_providers ")))
                    .count();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:sqlite:file:gateway-cache-test?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=cache@example.test",
            "workdsh.bootstrap.admin-password=CachePassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    @ActiveProfiles("enterprise-sqlite")
    class Enabled extends Scenario {
        @MockitoSpyBean JdbcTemplate db;
        String admin() { return "cache@example.test"; }
        JdbcTemplate db() { return db; }

        @Test
        void repeatedCallsDoNotQueryTheDatabase() {
            save("model-a", KEY);
            clearInvocations(db);
            Runnable call = () -> {
                String organization = keys.organizationFor(null, KEY);
                assertEquals("supplier-key-cache-test", providers.resolve(organization, "model-a", "openai-completions").apiKey());
                assertEquals(1, providers.catalog(organization).size());
            };
            call.run();
            long afterFirst = gatewayQueries();
            assertTrue(afterFirst > 0, "the first call loads from the database");
            for (int i = 0; i < 50; i++) call.run();
            assertEquals(afterFirst, gatewayQueries(), "the next 150 gateway lookups never touch the database");
        }

        @Test
        void savesAndKeyRotationApplyOnTheNextCall() {
            save("model-a", KEY);
            providers.resolve(keys.organizationFor(null, KEY), "model-a", "openai-completions");

            save("model-b", "");
            assertEquals(403, assertThrows(ResponseStatusException.class, () -> providers.resolve(org, "model-a", "openai-completions")).getStatusCode().value());
            assertNotNull(providers.resolve(org, "model-b", "openai-completions"));

            save("model-b", NEXT_KEY);
            assertEquals(401, assertThrows(ResponseStatusException.class, () -> keys.organizationFor(null, KEY)).getStatusCode().value(), "old key stops at once");
            assertEquals(org, keys.organizationFor(null, NEXT_KEY), "new key works at once");
        }

        @Test
        void unknownKeysAreNotRemembered() {
            save("model-a", KEY);
            String later = "cache-test-later-key-with-32-characters-x";
            assertThrows(ResponseStatusException.class, () -> keys.organizationFor(null, later));
            save("model-a", later);
            assertEquals(org, keys.organizationFor(null, later));
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:sqlite:file:gateway-cache-ttl-test?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=cache-ttl@example.test",
            "workdsh.bootstrap.admin-password=CachePassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "workdsh.gateway.cache-ttl=300ms"})
    @ActiveProfiles("enterprise-sqlite")
    class Expiry extends Scenario {
        @Autowired JdbcTemplate db;
        String admin() { return "cache-ttl@example.test"; }
        JdbcTemplate db() { return db; }

        @Test
        void changesMadeElsewhereAppearAfterTheTtl() throws Exception {
            save("model-a", KEY);
            assertNotNull(providers.resolve(org, "model-a", "openai-completions"));
            renameModelDirectly("model-z");
            assertNotNull(providers.resolve(org, "model-a", "openai-completions"), "still cached");
            Thread.sleep(400);
            assertNotNull(providers.resolve(org, "model-z", "openai-completions"), "reloaded after the TTL");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:sqlite:file:gateway-cache-off-test?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=cache-off@example.test",
            "workdsh.bootstrap.admin-password=CachePassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "workdsh.gateway.cache-ttl=0s"})
    @ActiveProfiles("enterprise-sqlite")
    class Disabled extends Scenario {
        @Autowired JdbcTemplate db;
        String admin() { return "cache-off@example.test"; }
        JdbcTemplate db() { return db; }

        @Test
        void zeroTtlReadsEveryTime() throws Exception {
            save("model-a", KEY);
            assertNotNull(providers.resolve(org, "model-a", "openai-completions"));
            renameModelDirectly("model-z");
            assertNotNull(providers.resolve(org, "model-z", "openai-completions"));
        }
    }
}
