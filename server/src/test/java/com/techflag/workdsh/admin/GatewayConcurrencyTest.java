package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP: every concurrent stream must start relaying at once. The measure is each caller's time to first
 * byte while the upstream keeps every stream open; total time would hide streams queued behind busy threads,
 * because their upstream bytes sit buffered in the gateway until a thread is free.
 */
class GatewayConcurrencyTest {
    private static final String ACCESS_KEY = "concurrency-access-key-with-32-characters";
    private static final long HOLD_MILLIS = 3000;

    abstract static class Scenario {
        @LocalServerPort int port;
        @Autowired ModelProvidersController providers;
        @Autowired AuthService auth;
        @Autowired JdbcTemplate db;
        @Autowired ObjectMapper json;

        HttpServer upstream;
        final HttpClient client = HttpClient.newBuilder().executor(Executors.newCachedThreadPool()).build();

        abstract String admin();

        @BeforeEach
        void start() throws Exception {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.setExecutor(Executors.newCachedThreadPool());
            upstream.createContext("/v1/messages", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write("event: ping\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(HOLD_MILLIS);
                    out.write("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            upstream.start();
            String bearer = "Bearer " + auth.login(admin(), "ConcurrencyPassword123!").token();
            String org = auth.actor(bearer).organizationId();
            long revision = db.queryForList("select revision from organization_model_providers where organization_id=?", Long.class, org).stream().findFirst().orElse(0L);
            providers.update(bearer, new ModelProviders.Update(revision, List.of(new ModelProviders.Provider("claude", "Claude",
                    "https://example.test", "anthropic-messages", true,
                    List.of(new ModelProviders.Model("claude-model", "Claude", null, null, false)), false, "supplier-key")), ACCESS_KEY));
            var config = json.readTree(db.queryForObject("select providers_json from organization_model_providers where organization_id=?", String.class, org));
            ((ObjectNode) config.get(0)).put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort());
            db.update("update organization_model_providers set providers_json=? where organization_id=?", json.writeValueAsString(config), org);
        }

        @AfterEach
        void stop() { upstream.stop(0); }

        HttpRequest stream() {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/model-gateway/v1/messages"))
                    .timeout(Duration.ofSeconds(60)).header("x-api-key", ACCESS_KEY).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude-model\",\"stream\":true,\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .build();
        }

        /** Per stream: status and milliseconds until the first body byte; the rest of the body is drained. */
        record Started(int status, long firstByteMillis, String body) {}

        List<Started> openTogether(int count) throws Exception {
            long started = System.nanoTime();
            List<CompletableFuture<Started>> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                calls.add(client.sendAsync(stream(), HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
                    try (InputStream in = response.body()) {
                        int first = in.read();
                        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                        String rest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        return new Started(response.statusCode(), millis, (first < 0 ? "" : String.valueOf((char) first)) + rest);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }));
            }
            List<Started> results = new ArrayList<>();
            for (var call : calls) results.add(call.get());
            return results;
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.datasource.url=jdbc:sqlite:file:concurrency-test?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=concurrency@example.test",
            "workdsh.bootstrap.admin-password=ConcurrencyPassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    @ActiveProfiles("enterprise-sqlite")
    class WithDefaults extends Scenario {
        String admin() { return "concurrency@example.test"; }

        @Test
        void everyConcurrentStreamStartsAtOnce() throws Exception {
            var results = openTogether(16);
            var firstBytes = new ArrayList<Long>();
            for (var result : results) {
                assertEquals(200, result.status());
                assertTrue(result.body().contains("message_stop"));
                firstBytes.add(result.firstByteMillis());
            }
            Collections.sort(firstBytes);
            // Queued streams would see their first byte only after an earlier stream ends (HOLD_MILLIS).
            assertTrue(firstBytes.get(firstBytes.size() - 1) < HOLD_MILLIS / 2, "first bytes (ms): " + firstBytes);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.datasource.url=jdbc:sqlite:file:concurrency-busy-test?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=concurrency-busy@example.test",
            "workdsh.bootstrap.admin-password=ConcurrencyPassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "workdsh.gateway.max-concurrent-requests=2"})
    @ActiveProfiles("enterprise-sqlite")
    class AtTheLimit extends Scenario {
        @Autowired GatewayLimiter limiter;
        String admin() { return "concurrency-busy@example.test"; }

        @Test
        void streamsBeyondTheLimitAreRefusedImmediately() throws Exception {
            var results = openTogether(4);
            long served = results.stream().filter(r -> r.status() == 200).count();
            var refused = results.stream().filter(r -> r.status() == 503).toList();
            assertEquals(2, served, "the configured number of streams is served");
            assertEquals(2, refused.size(), "the rest are refused rather than queued");
            for (var r : refused) {
                assertTrue(r.firstByteMillis() < HOLD_MILLIS / 2, "refusal is immediate");
                assertTrue(r.body().contains("gateway_busy"), r.body());
                assertTrue(r.body().contains("\"type\":\"error\""), "Anthropic callers get Anthropic error format");
            }
            assertEquals(0, limiter.inUse(), "finished and refused calls give their slots back");
            assertTrue(openTogether(2).stream().allMatch(r -> r.status() == 200), "freed slots serve new calls");
        }
    }
}
