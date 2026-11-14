package com.techflag.workdsh.admin;

import java.util.Collection;

/**
 * Where accepted Desktop visible text is kept. The database row always keeps sequence, record ID, role and the
 * text's SHA-256, so immutability checks never read text back. With an external store the row carries
 * storage_key and a null body_text.
 */
public interface BodyStore {
    /** True when text lives outside the database. */
    boolean external();

    void put(String key, String text);

    String get(String key);

    /** Deletes the given objects; missing objects are ignored. */
    void delete(Collection<String> keys);

    /** Default: text stays in member_desktop_body_records.body_text. */
    final class Database implements BodyStore {
        public boolean external() { return false; }
        public void put(String key, String text) { throw new UnsupportedOperationException("Database body store keeps text in rows"); }
        public String get(String key) { throw new UnsupportedOperationException("Database body store keeps text in rows"); }
        public void delete(Collection<String> keys) { }
    }
}
