package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Organization-shared internal access key: stored as a lookup hash plus an encrypted copy for administrators. */
@Service
public class ModelGatewayKeys {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final MemberSecrets secrets;
    private final AuditLog audit;
    private final GatewayCache cache;

    public ModelGatewayKeys(JdbcTemplate db, AuthService auth, MemberSecrets secrets, AuditLog audit, GatewayCache cache) {
        this.db = db; this.auth = auth; this.secrets = secrets; this.audit = audit; this.cache = cache;
    }

    static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void requireAdmin(AuthService.Actor actor) { auth.requireReady(actor); auth.requireAdmin(actor); }

    public boolean configured(AuthService.Actor actor) {
        requireAdmin(actor);
        return !db.queryForList("select organization_id from organization_model_gateway where organization_id=?", String.class, actor.organizationId()).isEmpty();
    }

    /** A blank key keeps the existing one; the first save must provide a key. */
    @Transactional
    public void update(AuthService.Actor actor, String accessKey) {
        requireAdmin(actor);
        String org = actor.organizationId();
        if (accessKey != null && !accessKey.isBlank()) {
            if (accessKey.length() < 32 || accessKey.length() > 512 || accessKey.chars().anyMatch(Character::isWhitespace)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Internal access key must contain 32–512 non-whitespace characters");
            }
            if (db.update("update organization_model_gateway set access_hash=? where organization_id=?", hash(accessKey), org) == 0) {
                db.update("insert into organization_model_gateway(organization_id,access_hash) values(?,?)", org, hash(accessKey));
            }
            String encrypted = secrets.cryptOrganizationModel(true, org, accessKey);
            if (db.update("update organization_model_gateway_keys set encrypted_key=? where organization_id=?", encrypted, org) == 0) {
                db.update("insert into organization_model_gateway_keys(organization_id,encrypted_key) values(?,?)", org, encrypted);
            }
            // The previous key must stop working on the next call.
            cache.invalidate(org);
        } else if (!configured(actor)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Internal access key required");
        }
        audit.record(actor, "organization.model.gateway.updated", org);
    }

    public String reveal(AuthService.Actor actor) {
        requireAdmin(actor);
        var keys = db.queryForList("select encrypted_key from organization_model_gateway_keys where organization_id=?", String.class, actor.organizationId());
        if (keys.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Internal access key not configured");
        String value = secrets.cryptOrganizationModel(false, actor.organizationId(), keys.get(0));
        audit.record(actor, "organization.model.gateway.key.viewed", actor.organizationId());
        return value;
    }

    /** Resolves a caller's internal key (Bearer header or x-api-key) to its organization. */
    public String organizationFor(String authorization, String apiKey) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : apiKey;
        if (token == null || token.length() < 32 || token.length() > 512) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }
        String accessHash = hash(token);
        String organization = cache.organization(accessHash, () -> db.queryForList(
                "select organization_id from organization_model_gateway where access_hash=?", String.class, accessHash)
                .stream().findFirst().orElse(null));
        if (organization == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        return organization;
    }
}
