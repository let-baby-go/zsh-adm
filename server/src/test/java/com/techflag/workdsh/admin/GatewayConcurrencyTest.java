package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP: concurrent streams must not queue behind a small async thread pool. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:file:concurrency-test?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=concurrency@example.test",
        "workdsh.bootstrap.admin-password=ConcurrencyPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class GatewayConcurrencyTest {
    private static final String ACCESS_KEY = "concurrency-access-key-with-32-characters";
    private static final int STREAMS = 16;
    private static final long HOLD_MILLIS = 1500;

    @LocalServerPort int port;
    @Autowired ModelProvidersController providers;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @Test
    void concurrentStreamsRunInParallel() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        try {
            String bearer = "Bearer " + auth.login("concurrency@example.test", "ConcurrencyPassword123!").token();
            String org = auth.actor(bearer).organizationId();
            providers.update(bearer, new ModelProviders.Update(0, List.of(new ModelProviders.Provider("claude", "Claude",
                    "https://example.test", "anthropic-messages", true,
                    List.of(new ModelProviders.Model("claude-model", "Claude", null, null, false)), false, "supplier-key")), ACCESS_KEY));
            var config = json.readTree(db.queryForObject("select providers_json from organization_model_providers where organization_id=?", String.class, org));
            ((ObjectNode) config.get(0)).put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort());
            db.update("update organization_model_providers set providers_json=? where organization_id=?", json.writeValueAsString(config), org);

            var client = HttpClient.newBuilder().executor(Executors.newCachedThreadPool()).build();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/model-gateway/v1/messages"))
                    .timeout(Duration.ofSeconds(60)).header("x-api-key", ACCESS_KEY).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude-model\",\"stream\":true,\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .build();
            long started = System.nanoTime();
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < STREAMS; i++) calls.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            for (var call : calls) {
                var response = call.get();
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("message_stop"));
            }
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
            // In parallel all streams finish in about one hold period; queued behind 8 threads they take two or more.
            assertTrue(elapsed < HOLD_MILLIS * 2, STREAMS + " streams took " + elapsed + " ms");
        } finally {
            upstream.stop(0);
        }
    }
}
