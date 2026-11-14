package com.techflag.workdsh.admin;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Upstream time limits shared by every gateway transport.
 *
 * <ul>
 * <li>connect-timeout: establishing the upstream connection.</li>
 * <li>response-timeout: from sending the request until the upstream response starts (headers, or the whole
 *     non-streaming body for Spring AI).</li>
 * <li>stream-idle-timeout: longest silence allowed while relaying an upstream body.</li>
 * </ul>
 * The total length of one streamed response is bounded separately by spring.mvc.async.request-timeout.
 */
@Component
public class GatewaySettings {
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Duration streamIdleTimeout;

    public GatewaySettings(@Value("${workdsh.gateway.connect-timeout:15s}") Duration connectTimeout,
                           @Value("${workdsh.gateway.response-timeout:5m}") Duration responseTimeout,
                           @Value("${workdsh.gateway.stream-idle-timeout:5m}") Duration streamIdleTimeout) {
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.streamIdleTimeout = streamIdleTimeout;
    }

    public Duration connectTimeout() { return connectTimeout; }
    public Duration responseTimeout() { return responseTimeout; }
    public Duration streamIdleTimeout() { return streamIdleTimeout; }
}
