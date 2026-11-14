package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** HTTP surface of the model gateway: authorize → resolve provider → forward. */
@RestController
public class ModelGateway {
    private static final Logger log = LoggerFactory.getLogger("workdsh.gateway");
    private static final String GATEWAY_PREFIX = "/api/model-gateway/";

    private final AuthService auth;
    private final ModelGatewayKeys keys;
    private final ModelProviders providers;
    private final ModelForwarder forwarder;
    private final ObjectMapper json;

    public ModelGateway(AuthService auth, ModelGatewayKeys keys, ModelProviders providers, ModelForwarder forwarder, ObjectMapper json) {
        this.auth = auth; this.keys = keys; this.providers = providers; this.forwarder = forwarder; this.json = json;
    }

    public record Config(boolean hasAccessKey) {}
    public record Update(String accessKey) {}
    record Route(String org) {}
    private record Parsed(String model, boolean streaming) {}

    @GetMapping("/api/admin/model-gateway")
    public Config read(@RequestHeader("Authorization") String bearer) {
        return new Config(keys.configured(auth.actor(bearer)));
    }

    @PutMapping("/api/admin/model-gateway")
    public Config update(@RequestHeader("Authorization") String bearer, @RequestBody Update u) {
        var actor = auth.actor(bearer);
        if (u == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Internal access configuration required");
        keys.update(actor, u.accessKey());
        return new Config(keys.configured(actor));
    }

    @PostMapping("/api/admin/model-gateway/reveal-key")
    public ResponseEntity<Map<String, String>> reveal(@RequestHeader("Authorization") String bearer) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("key", keys.reveal(auth.actor(bearer))));
    }

    @GetMapping("/api/model-gateway/v1/models")
    public Map<String, Object> catalog(@RequestHeader(value = "Authorization", required = false) String bearer,
                                       @RequestHeader(value = "x-api-key", required = false) String key) {
        return Map.of("object", "list", "data", providers.catalog(authorize(bearer, key).org()));
    }

    Route authorize(String authorization, String apiKey) { return new Route(keys.organizationFor(authorization, apiKey)); }

    @PostMapping(value = {"/api/model-gateway/v1/messages", "/api/model-gateway/v1/chat/completions"})
    public ResponseEntity<StreamingResponseBody> forward(HttpServletRequest request,
                                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestHeader(value = "x-api-key", required = false) String apiKey,
                                                         @RequestBody byte[] body) {
        long started = System.nanoTime();
        var route = authorize(authorization, apiKey);
        boolean anthropic = request.getRequestURI().endsWith("/messages");
        if (body.length > 4 * 1024 * 1024) throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large", "Request body exceeds 4 MiB");
        var parsed = parse(body);
        var connection = providers.resolve(route.org(), parsed.model(), anthropic ? "anthropic-messages" : "openai-completions");
        return forwarder.forward(new ModelForwarder.Call(route.org(), parsed.model(), anthropic, parsed.streaming(),
                connection, body, request.getHeader("anthropic-version"), started));
    }

    private Parsed parse(byte[] body) {
        try {
            var input = json.readTree(body);
            if (input == null || !input.hasNonNull("model") || !input.get("model").isTextual()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "Model ID required");
            }
            return new Parsed(input.get("model").asText(), input.path("stream").asBoolean(false));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "Invalid JSON request");
        }
    }

    /** All stream threads busy (spring.task.execution.pool.max-size): refuse now instead of queueing silently. */
    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    ResponseEntity<?> busy(HttpServletRequest request) {
        log.warn("gateway rejected uri={} status=503 outcome=gateway_busy", request.getRequestURI());
        if (request.getAttribute(ModelForwarder.PENDING_UPSTREAM) instanceof java.io.InputStream upstream) {
            try { upstream.close(); } catch (java.io.IOException ignored) { }
        }
        return error(new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_busy",
                "Too many concurrent model responses; retry shortly"), request);
    }

    /**
     * Model API callers (DSH, SDKs) receive errors in their protocol's own format; administrator endpoints in
     * this controller receive the console's problem-detail format.
     */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> error(ResponseStatusException error, HttpServletRequest request) {
        int status = error.getStatusCode().value();
        String uri = request.getRequestURI();
        if (!uri.startsWith(GATEWAY_PREFIX)) {
            return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason()));
        }
        var gateway = error instanceof GatewayException g ? g : null;
        String code = gateway != null ? gateway.code() : GatewayException.codeFor(status);
        if (gateway == null) log.info("gateway rejected uri={} status={} outcome={}", uri, status, code);
        var response = ResponseEntity.status(status).header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON);
        if (gateway != null && gateway.retryAfter() != null) response.header("Retry-After", gateway.retryAfter());
        if (gateway != null && gateway.upstreamBody() != null) return response.body(gateway.upstreamBody());
        String message = error.getReason() == null ? "Request failed" : error.getReason();
        Object body = uri.endsWith("/messages")
                ? Map.of("type", "error", "error", Map.of("type", code, "message", message))
                : Map.of("error", Map.of("message", message, "type", code, "code", code));
        return response.body(body);
    }
}
