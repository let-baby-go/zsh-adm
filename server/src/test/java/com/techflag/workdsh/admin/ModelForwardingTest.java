package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Every gateway protocol path against a local upstream: OpenAI non-stream/stream, Anthropic non-stream/native SSE. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:forwarding-test?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=forwarding@example.test",
        "workdsh.bootstrap.admin-password=ForwardingPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class ModelForwardingTest {
    private static final String ACCESS_KEY = "forwarding-access-key-with-32-characters";
    private static final String ANTHROPIC_SSE = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}\n\n"
            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"native\"}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    @Autowired ModelGateway gateway;
    @Autowired ModelProvidersController providers;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    private HttpServer upstream;
    private final Map<String, String> seen = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            seen.put("openai-auth", exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.put("openai-body", body);
            byte[] reply = (body.contains("\"stream\":true")
                    ? "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"open-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"streamed\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
                    : "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"open-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"whole\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", body.contains("\"stream\":true") ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        upstream.createContext("/v1/messages", exchange -> {
            seen.put("anthropic-key", exchange.getRequestHeaders().getFirst("x-api-key"));
            seen.put("anthropic-version", exchange.getRequestHeaders().getFirst("anthropic-version"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean stream = body.contains("\"stream\":true");
            byte[] reply = (stream ? ANTHROPIC_SSE
                    : "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-model\",\"content\":[{\"type\":\"text\",\"text\":\"whole-message\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        upstream.start();
        String bearer = "Bearer " + auth.login("forwarding@example.test", "ForwardingPassword123!").token();
        String org = auth.actor(bearer).organizationId();
        long revision = db.queryForList("select revision from organization_model_providers where organization_id=?", Long.class, org)
                .stream().findFirst().orElse(0L);
        providers.update(bearer, new ModelProviders.Update(revision, List.of(
                new ModelProviders.Provider("open", "Open", "https://example.test", "openai-completions", true,
                        List.of(new ModelProviders.Model("open-model", "Open", null, null, false)), false, "open-supplier-key"),
                new ModelProviders.Provider("claude", "Claude", "https://example.test", "anthropic-messages", true,
                        List.of(new ModelProviders.Model("claude-model", "Claude", null, null, false)), false, "claude-supplier-key")),
                ACCESS_KEY));
        // Test-only loopback upstream; saved configuration still requires HTTPS.
        var config = json.readTree(db.queryForObject("select providers_json from organization_model_providers where organization_id=?", String.class, org));
        for (var provider : config) ((ObjectNode) provider).put("baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort());
        db.update("update organization_model_providers set providers_json=? where organization_id=?", json.writeValueAsString(config), org);
    }

    @AfterEach
    void stop() { upstream.stop(0); }

    private String call(String path, String body) throws Exception {
        var request = new MockHttpServletRequest();
        request.setRequestURI(path);
        var response = gateway.forward(request, "Bearer " + ACCESS_KEY, null, body.getBytes(StandardCharsets.UTF_8));
        if (body.contains("\"stream\":true")) {
            assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"), "proxies must not buffer streamed answers");
        }
        var out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void openAiNonStreamingAndStreaming() throws Exception {
        String whole = call("/api/model-gateway/v1/chat/completions", "{\"model\":\"open-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertTrue(whole.contains("whole"));
        String streamed = call("/api/model-gateway/v1/chat/completions", "{\"model\":\"open-model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertTrue(streamed.contains("streamed"));
        assertTrue(streamed.endsWith("data: [DONE]\n\n"));
        assertEquals("Bearer open-supplier-key", seen.get("openai-auth"));
    }

    @Test
    void openAiRequestAndResponseAreRelayedByteForByte() throws Exception {
        String request = "{\"model\":\"open-model\",  \"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"vendor_extension\":{\"reasoning\":\"high\"},\"stream_options\":{\"include_usage\":true}}";
        String response = call("/api/model-gateway/v1/chat/completions", request);
        assertEquals(request, seen.get("openai-body"), "unknown fields and formatting reach the provider unchanged");
        assertTrue(response.contains("\"prompt_tokens\":2"), "provider usage is relayed");
    }

    @Test
    void anthropicNonStreamingAndNativeStreaming() throws Exception {
        String whole = call("/api/model-gateway/v1/messages", "{\"model\":\"claude-model\",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertTrue(whole.contains("whole-message"));
        String streamed = call("/api/model-gateway/v1/messages", "{\"model\":\"claude-model\",\"stream\":true,\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(ANTHROPIC_SSE, streamed, "native Anthropic SSE is relayed byte for byte");
        assertEquals("claude-supplier-key", seen.get("anthropic-key"));
        assertEquals("2023-06-01", seen.get("anthropic-version"));
    }

    @Test
    void protocolMismatchAndUnknownModelAreRejected() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/model-gateway/v1/messages");
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> gateway.forward(request, null, ACCESS_KEY,
                "{\"model\":\"open-model\"}".getBytes(StandardCharsets.UTF_8))).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> gateway.forward(request, null, ACCESS_KEY,
                "{\"model\":\"missing\"}".getBytes(StandardCharsets.UTF_8))).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> gateway.forward(request, null, "wrong-key-wrong-key-wrong-key-wrong",
                "{\"model\":\"open-model\"}".getBytes(StandardCharsets.UTF_8))).getStatusCode().value());
    }
}
