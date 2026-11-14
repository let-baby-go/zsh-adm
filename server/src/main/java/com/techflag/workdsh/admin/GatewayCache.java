package com.techflag.workdsh.admin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps the model gateway's per-call lookups out of the database: internal key hash → organization, and each
 * organization's provider configuration (supplier keys stay encrypted here and are decrypted per call).
 *
 * Saving in this instance invalidates the organization once the save commits, so changes and key rotation take
 * effect on the next call. Entries also expire after workdsh.gateway.cache-ttl, which bounds how long another
 * instance can serve a configuration that was changed elsewhere. A TTL of 0 disables the cache.
 */
@Component
public class GatewayCache {
    private static final int MAX_ENTRIES = 10_000;

    private record Entry<T>(T value, long expiresAt) {
        boolean fresh(long now) { return now < expiresAt; }
    }

    private final long ttlNanos;
    private final Map<String, Entry<String>> organizations = new ConcurrentHashMap<>();
    private final Map<String, Entry<List<ModelProviders.Provider>>> providers = new ConcurrentHashMap<>();

    public GatewayCache(@Value("${workdsh.gateway.cache-ttl:10s}") Duration ttl) {
        this.ttlNanos = ttl.toNanos();
    }

    /** Organization owning the internal key hash; unknown keys are not cached, so a new key works immediately. */
    String organization(String accessHash, Supplier<String> load) {
        return cached(organizations, accessHash, load);
    }

    List<ModelProviders.Provider> providers(String organizationId, Supplier<List<ModelProviders.Provider>> load) {
        return cached(providers, organizationId, load);
    }

    private <T> T cached(Map<String, Entry<T>> map, String key, Supplier<T> load) {
        if (ttlNanos <= 0) return load.get();
        long now = System.nanoTime();
        var entry = map.get(key);
        if (entry != null && entry.fresh(now)) return entry.value();
        T value = load.get();
        if (value != null) {
            if (map.size() >= MAX_ENTRIES) map.clear();
            map.put(key, new Entry<>(value, now + ttlNanos));
        }
        return value;
    }

    /**
     * Forgets everything cached for the organization now and again after the surrounding transaction commits:
     * a call racing the save could otherwise reload and keep the pre-commit configuration until the TTL.
     */
    void invalidate(String organizationId) {
        evict(organizationId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(organizationId); }
            });
        }
    }

    private void evict(String organizationId) {
        providers.remove(organizationId);
        organizations.values().removeIf(entry -> organizationId.equals(entry.value()));
    }
}
