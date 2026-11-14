package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-to-server authentication for /api/internal/ routes (header X-WorkDSH-Service-Key).
 *
 * <pre>
 * 503 Service key is not configured   WORKDSH_SECRETS_SERVICE_KEY missing or shorter than 32 characters
 * 403 Service key required            header absent
 * 403 Invalid service key             header present but different
 * </pre>
 * Both caller failures stay 403 so a caller cannot tell a missing route from a missing key.
 */
@Component
public class ServiceKeyGuard {
    public static final String HEADER = "X-WorkDSH-Service-Key";
    private final byte[] key;

    public ServiceKeyGuard(@Value("${workdsh.secrets.service-key:}") String key) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public void require(String supplied) {
        if (key.length < 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service key is not configured");
        if (supplied == null || supplied.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service key required");
        if (!MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid service key");
    }
}
