package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Timeouts, cancellation and error classification over real HTTP with shortened limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:file:resilience-test?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=resilience@example.test",
        "workdsh.bootstrap.admin-password=ResiliencePassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "workdsh.gateway.connect-timeout=1s",
        "workdsh.gateway.response-timeout=1s",
        "workdsh.gateway.stream-idle-timeout=1s",
        "spring.mvc.async.request-timeout=4s"})
@ActiveProfiles("enterprise-sqlite")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayResilienceTest {
    private static final String ACCESS_KEY = "resilience-access-key-with-32-characters";
    private static final String SUPPLIER_KEY = "supplier-secret-resilience";

    @LocalServerPort int port;
    @Autowired ModelProvidersController providers;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    private HttpServer upstream;
    private String bearer;
    private final HttpClient client = HttpClient.newBuilder().executor(Executors.newCachedThreadPool()).build();
    private final CompletableFuture<Long> disconnectSeenAt = new CompletableFuture<>();

    /** model id → upstream path prefix; every scenario is its own provider. */
    private static final Map<String, String> OPENAI = Map.of(
            "slow-headers", "/slow", "bad-request", "/bad", "limited", "/limited", "down", "/down",
            "idle", "/idle", "long", "/long", "disconnect", "/disconnect", "refused", "");
    private static final Map<String, String> ANTHROPIC = Map.of("bad-key", "/badkey", "anthropic-down", "/down");

    @BeforeAll
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.createContext("/slow/", exchange -> { sleep(3000); reply(exchange, 200, "{}"); });
        upstream.createContext("/bad/", exchange -> reply(exchange, 400,
                "{\"error\":{\"message\":\"unknown parameter for key " + SUPPLIER_KEY + "\",\"type\":\"invalid_request_error\"}}"));
        upstream.createContext("/limited/", exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "7");
            reply(exchange, 429, "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit\"}}");
        });
        upstream.createContext("/down/", exchange -> reply(exchange, 503, "upstream maintenance"));
        upstream.createContext("/badkey/", exchange -> reply(exchange, 401, "{\"error\":\"bad key\"}"));
        upstream.createContext("/idle/", exchange -> stream(exchange, 1, 5000, 0));
        upstream.createContext("/long/", exchange -> stream(exchange, 30, 0, 200));
        upstream.createContext("/disconnect/", exchange -> {
            try { stream(exchange, 100, 0, 100); }
            catch (IOException e) { disconnectSeenAt.complete(System.nanoTime()); }
        });
        upstream.start();

        bearer = "Bearer " + auth.login("resilience@example.test", "ResiliencePassword123!").token();
        String org = auth.actor(bearer).organizationId();
        List<ModelProviders.Provider> list = new ArrayList<>();
        OPENAI.keySet().forEach(model -> list.add(provider(model, "openai-completions")));
        ANTHROPIC.keySet().forEach(model -> list.add(provider(model, "anthropic-messages")));
        providers.update(bearer, new ModelProviders.Update(0, list, ACCESS_KEY));
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        var config = json.readTree(db.queryForObject("select providers_json from organization_model_providers where organization_id=?", String.class, org));
        for (JsonNode provider : config) {
            String model = provider.path("models").get(0).path("id").asText();
            String path = OPENAI.containsKey(model) ? OPENAI.get(model) : ANTHROPIC.get(model);
            ((ObjectNode) provider).put("baseUrl", model.equals("refused") ? "http://127.0.0.1:1" : base + path);
        }
        db.update("update organization_model_providers set providers_json=? where organization_id=?", json.writeValueAsString(config), org);
    }

    @AfterAll
    void stop() { upstream.stop(0); }

    private static ModelProviders.Provider provider(String model, String protocol) {
        return new ModelProviders.Provider("p-" + model, model, "https://example.test", protocol, true,
                List.of(new ModelProviders.Model(model, model, null, null, false)), false, SUPPLIER_KEY);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    /** SSE chunks: {@code count} events, a pause after the first, then a gap between the rest. */
    private static void stream(HttpExchange exchange, int count, long pauseAfterFirst, long gap) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            for (int i = 0; i < count; i++) {
                out.write(("data: {\"n\":" + i + "}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleep(i == 0 ? pauseAfterFirst : gap);
            }
            out.write("data: END\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private HttpRequest call(String model, boolean stream) {
        boolean anthropic = ANTHROPIC.containsKey(model);
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/model-gateway/v1/" + (anthropic ? "messages" : "chat/completions")))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + ACCESS_KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + model + "\",\"stream\":" + stream + ",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
    }

    private HttpResponse<String> send(String model, boolean stream) throws Exception {
        return client.send(call(model, stream), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws IOException { return json.readTree(response.body()); }

    @Test
    void slowProviderTimesOutWith504() throws Exception {
        long started = System.nanoTime();
        var response = send("slow-headers", false);
        assertEquals(504, response.statusCode());
        assertEquals("upstream_timeout", body(response).path("error").path("code").asText());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
    }

    @Test
    void providerRejectionIsRelayedWithKeyMasked() throws Exception {
        var response = send("bad-request", false);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("unknown parameter"));
        assertFalse(response.body().contains(SUPPLIER_KEY));
        assertTrue(response.body().contains("***"));
    }

    @Test
    void rateLimitKeepsStatusAndRetryAfter() throws Exception {
        var response = send("limited", false);
        assertEquals(429, response.statusCode());
        assertEquals("7", response.headers().firstValue("Retry-After").orElse(null));
        assertEquals("rate_limit", body(response).path("error").path("type").asText());
    }

    @Test
    void providerFailuresAreClassified() throws Exception {
        var down = send("down", false);
        assertEquals(502, down.statusCode());
        assertEquals("upstream_error", body(down).path("error").path("code").asText());
        assertFalse(down.body().contains("maintenance"), "provider 5xx bodies are not relayed");

        var refused = send("refused", false);
        assertEquals(502, refused.statusCode());
        assertEquals("upstream_unavailable", body(refused).path("error").path("code").asText());

        var badKey = send("bad-key", true);
        assertEquals(502, badKey.statusCode());
        assertEquals("error", body(badKey).path("type").asText(), "Anthropic callers get Anthropic error format");
        assertEquals("upstream_auth_failed", body(badKey).path("error").path("type").asText());

        var anthropicDown = send("anthropic-down", false);
        assertEquals(502, anthropicDown.statusCode());
        assertEquals("upstream_error", body(anthropicDown).path("error").path("type").asText(), "Spring AI path classified too");
    }

    @Test
    void gatewayOwnErrorsUseProtocolFormat() throws Exception {
        var unknown = send("not-configured", false);
        assertEquals(403, unknown.statusCode());
        assertEquals("model_not_allowed", body(unknown).path("error").path("code").asText());
        var wrongKey = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/model-gateway/v1/messages"))
                .header("x-api-key", "wrong-key-wrong-key-wrong-key-wrong-key").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"bad-key\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrongKey.statusCode());
        assertEquals("authentication_failed", body(wrongKey).path("error").path("type").asText());
    }

    @Test
    void silentStreamIsClosedAfterIdleTimeout() throws Exception {
        long started = System.nanoTime();
        var response = send("idle", true);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"n\":0"));
        assertFalse(response.body().contains("END"));
        assertTrue(elapsed < 3500, "idle stream ended after " + elapsed + " ms");
    }

    @Test
    void responseDurationLimitIsConfigurable() throws Exception {
        long started = System.nanoTime();
        var response = send("long", true);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertFalse(response.body().contains("END"));
        assertTrue(elapsed >= 3500 && elapsed < 5800, "stream cut after " + elapsed + " ms");
    }

    @Test
    void defaultResponseDurationAllowsLongAnswers() throws Exception {
        String config = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(config.contains("request-timeout: ${WORKDSH_GATEWAY_MAX_RESPONSE_DURATION:30m}"));
    }

    @Test
    void callerDisconnectCancelsUpstream() throws Exception {
        var response = client.send(call("disconnect", true), HttpResponse.BodyHandlers.ofInputStream());
        long closedAt;
        try (InputStream in = response.body()) {
            assertTrue(in.read(new byte[16]) > 0);
            closedAt = System.nanoTime();
        }
        long seen = disconnectSeenAt.get(5, TimeUnit.SECONDS);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(seen - closedAt) < 3000, "upstream kept streaming after the caller left");
    }

    @Test
    void consoleErrorsCarryDetail() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/model-providers"))
                .header("Authorization", bearer).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"expectedRevision\":99,\"providers\":[],\"accessKey\":\"\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, response.statusCode());
        assertEquals("Configuration changed; refresh before saving", body(response).path("detail").asText());
    }
}
