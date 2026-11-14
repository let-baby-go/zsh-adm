package com.techflag.workdsh.admin;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A classified model gateway failure, rendered in the caller's protocol error format by {@link ModelGateway}.
 *
 * <pre>
 * code                   status        meaning
 * invalid_request        400           malformed JSON, missing model, wrong protocol for the model
 * authentication_failed  401           internal access key missing or unknown
 * model_not_allowed      403           model not in an enabled interface of this organization
 * request_too_large      413           request body over 4 MiB
 * upstream_rejected      upstream 4xx  provider refused the request; its error body is relayed (key masked)
 * upstream_auth_failed   502           provider refused the supplier key (401/403): an administrator must fix it
 * upstream_error         502           provider answered 5xx
 * upstream_unavailable   502           connection to the provider failed
 * upstream_timeout       504           provider did not start responding within response-timeout
 * </pre>
 */
public class GatewayException extends ResponseStatusException {
    private final String code;
    private final byte[] upstreamBody;
    private final String retryAfter;

    public GatewayException(HttpStatusCode status, String code, String message) {
        this(status, code, message, null, null);
    }

    public GatewayException(HttpStatusCode status, String code, String message, byte[] upstreamBody, String retryAfter) {
        super(status, message);
        this.code = code;
        this.upstreamBody = upstreamBody;
        this.retryAfter = retryAfter;
    }

    public String code() { return code; }
    /** The provider's own error body, already masked; null when the gateway writes its own error. */
    public byte[] upstreamBody() { return upstreamBody; }
    public String retryAfter() { return retryAfter; }

    /** Code for errors raised outside the gateway classes (authorization, provider resolution). */
    static String codeFor(int status) {
        return switch (status) {
            case 400 -> "invalid_request";
            case 401 -> "authentication_failed";
            case 403 -> "model_not_allowed";
            case 413 -> "request_too_large";
            case 504 -> "upstream_timeout";
            default -> status >= 500 ? "upstream_error" : "invalid_request";
        };
    }
}
