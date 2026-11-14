package com.techflag.workdsh.admin;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Sends an authorized, resolved request upstream; see {@link UpstreamEndpoint} for the protocol matrix.
 *
 * Request bodies are forwarded byte for byte. Response bodies are relayed byte for byte, except Anthropic
 * non-streaming responses, which pass through Spring AI.
 */
@Component
public class ModelForwarder {
    private static final Logger log = LoggerFactory.getLogger("workdsh.gateway");
    private static final int MAX_ERROR_BODY = 16 * 1024;

    /** One forwarded call; {@code body} is the caller's request exactly as received. */
    record Call(String organizationId, String model, boolean anthropic, boolean streaming,
                ModelProviders.Resolved connection, byte[] body, String anthropicVersion, long startedNanos) {
        String protocol() { return anthropic ? "anthropic-messages" : "openai-completions"; }
        String version() { return anthropicVersion == null ? "2023-06-01" : anthropicVersion; }
    }

    private final SpringAiModelClient springAi;
    private final GatewaySettings settings;
    private final HttpClient client;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "gateway-stream-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /** Request attribute holding the open upstream body until a stream thread takes it over. */
    static final String PENDING_UPSTREAM = ModelForwarder.class.getName() + ".upstream";

    /** Threads that write responses to callers (Spring MVC async); full means a new stream would be refused. */
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor streams;

    public ModelForwarder(SpringAiModelClient springAi, GatewaySettings settings,
                          @org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor")
                          org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor streams) {
        this.springAi = springAi;
        this.settings = settings;
        this.streams = streams;
        this.client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    ResponseEntity<StreamingResponseBody> forward(Call call) {
        // Refuse before the provider starts generating (and billing) an answer nobody can receive.
        if (streams.getActiveCount() >= streams.getMaxPoolSize()) {
            throw failed(call, new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_busy", "Too many concurrent model responses; retry shortly"));
        }
        if (call.anthropic() && !call.streaming()) return anthropicViaSpringAi(call);
        var target = call.anthropic() ? UpstreamEndpoint.anthropic(call.connection().baseUrl()) : UpstreamEndpoint.openAi(call.connection().baseUrl());
        var request = HttpRequest.newBuilder(target.uri()).timeout(settings.responseTimeout()).header("Content-Type", "application/json");
        if (call.anthropic()) request.header("x-api-key", call.connection().apiKey()).header("anthropic-version", call.version());
        else request.header("Authorization", "Bearer " + call.connection().apiKey());
        HttpResponse<InputStream> reply;
        try {
            reply = client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(call.body())).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw failed(call, new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Model provider did not respond in time"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(call, new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "Model provider connection failed"));
        } catch (IOException e) {
            throw failed(call, new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "Model provider connection failed"));
        }
        if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
            byte[] error;
            try (var in = reply.body()) { error = in.readNBytes(MAX_ERROR_BODY); } catch (IOException e) { error = new byte[0]; }
            throw failed(call, rejected(call, reply.statusCode(), error, reply.headers().firstValue("Retry-After").orElse(null)));
        }
        String contentType = reply.headers().firstValue("Content-Type").orElse(call.streaming() ? "text/event-stream" : "application/json");
        // X-Accel-Buffering: nginx (and compatible proxies) relay each chunk immediately even without proxy_buffering off.
        // If no stream thread is free after all, ModelGateway closes this body, which cancels the upstream request.
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes != null) attributes.setAttribute(PENDING_UPSTREAM, reply.body(), org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .header("Content-Type", contentType).body(relay(call, reply.body()));
    }

    private ResponseEntity<StreamingResponseBody> anthropicViaSpringAi(Call call) {
        byte[] result;
        try {
            result = springAi.completeMessages(call.connection().baseUrl(), call.connection().apiKey(), call.version(), call.body());
        } catch (RestClientResponseException e) {
            String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
            throw failed(call, rejected(call, e.getStatusCode().value(), e.getResponseBodyAsByteArray(), retryAfter));
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException || e.getCause() instanceof java.net.SocketTimeoutException;
            throw failed(call, timeout
                    ? new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "Model provider did not respond in time")
                    : new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "Model provider connection failed"));
        } catch (Exception e) {
            throw failed(call, new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Model provider response could not be read"));
        }
        logOutcome(call, 200, "completed", result.length);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Content-Type", "application/json").body(out -> out.write(result));
    }

    /** Classifies a non-2xx provider answer. The supplier key never reaches the caller, even if echoed. */
    private GatewayException rejected(Call call, int status, byte[] body, String retryAfter) {
        if (status == 401 || status == 403) {
            return new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_auth_failed", "Model provider rejected the configured supplier key");
        }
        if (status >= 500) {
            return new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_error", "Model provider failed with status " + status);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        String key = call.connection().apiKey();
        if (key != null && !key.isEmpty()) text = text.replace(key, "***");
        boolean json = text.stripLeading().startsWith("{");
        return new GatewayException(HttpStatusCode.valueOf(status), "upstream_rejected", "Model provider rejected the request with status " + status,
                json ? text.getBytes(StandardCharsets.UTF_8) : null, retryAfter);
    }

    /**
     * Copies the upstream body to the caller. Closing the upstream stream cancels the upstream request, which
     * happens when the caller disconnects, when the upstream stays silent longer than stream-idle-timeout, and
     * when the copy ends normally.
     */
    private StreamingResponseBody relay(Call call, InputStream upstream) {
        return out -> {
            long idleNanos = settings.streamIdleTimeout().toNanos();
            long period = Math.max(50, Math.min(1000, settings.streamIdleTimeout().toMillis() / 4));
            var lastRead = new AtomicLong(System.nanoTime());
            var idle = new AtomicBoolean();
            var guard = watchdog.scheduleAtFixedRate(() -> {
                if (System.nanoTime() - lastRead.get() > idleNanos && idle.compareAndSet(false, true)) closeQuietly(upstream);
            }, period, period, TimeUnit.MILLISECONDS);
            String outcome = "completed";
            long bytes = 0;
            try (upstream) {
                byte[] buffer = new byte[8192];
                while (true) {
                    int n;
                    try { n = upstream.read(buffer); }
                    catch (IOException e) { outcome = idle.get() ? "upstream_idle_timeout" : "upstream_failed"; break; }
                    if (n == -1) break;
                    lastRead.set(System.nanoTime());
                    try { out.write(buffer, 0, n); out.flush(); }
                    catch (IOException e) { outcome = "client_disconnected"; throw e; }
                    bytes += n;
                }
            } finally {
                guard.cancel(false);
                logOutcome(call, 200, outcome, bytes);
            }
        };
    }

    private GatewayException failed(Call call, GatewayException error) {
        logOutcome(call, error.getStatusCode().value(), error.code(), 0);
        return error;
    }

    private static void logOutcome(Call call, int status, String outcome, long bytes) {
        log.info("gateway org={} model={} protocol={} stream={} status={} outcome={} bytes={} durationMs={}",
                call.organizationId(), call.model(), call.protocol(), call.streaming(), status, outcome, bytes,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - call.startedNanos()));
    }

    private static void closeQuietly(InputStream in) {
        try { in.close(); } catch (IOException ignored) { }
    }
}
