package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps external body objects consistent with database transactions: objects written in a transaction that
 * rolls back are deleted again, and objects of erased rows are deleted only after the erase commits.
 */
@Component
public class BodyObjects {
    private static final Logger log = LoggerFactory.getLogger(BodyObjects.class);
    private final BodyStore store;

    public BodyObjects(BodyStore store) { this.store = store; }

    public boolean external() { return store.external(); }

    /** Object key of one accepted record. The client session ID is hashed: it is caller-chosen. */
    static String key(String organizationId, String memberId, String clientSessionId, long seq) {
        return "desktop-body/" + organizationId + "/" + memberId + "/" + sha256(clientSessionId) + "/" + seq;
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Writes now; within a transaction, a rollback deletes what was written. */
    public void put(String key, String text) {
        store.put(key, text);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) quietly(List.of(key));
                }
            });
        }
    }

    public String text(String inline, String key) { return inline != null ? inline : store.get(key); }

    /** Deletes after the surrounding transaction commits, or immediately outside a transaction. */
    public void deleteAfterCommit(List<String> keys) {
        var present = new ArrayList<String>();
        for (String key : keys) if (key != null) present.add(key);
        if (present.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { quietly(present); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { quietly(present); }
        });
    }

    /** A failed delete leaves an unreferenced object, never a reference to a missing one. */
    private void quietly(List<String> keys) {
        try { store.delete(keys); }
        catch (RuntimeException e) { log.warn("body objects not deleted count={} reason={}", keys.size(), e.getClass().getSimpleName()); }
    }
}
