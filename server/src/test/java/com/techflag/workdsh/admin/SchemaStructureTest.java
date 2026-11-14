package com.techflag.workdsh.admin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural parity of every delivered schema: tables, columns (normalized type, length, nullability),
 * primary keys, unique constraints, foreign keys and secondary indexes. A column, index or constraint added
 * to one DDL but not the others fails here.
 *
 * MySQL DDL is executed in H2 MySQL compatibility mode; this checks the delivered file's structure, not a
 * real MySQL server.
 */
class SchemaStructureTest {
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path MIGRATIONS = Path.of("../deploy/postgres/migrations");

    @Test
    void everyDeliveredSchemaHasTheSameStructure() throws Exception {
        String reference = shape(h2("PostgreSQL", "reference"), List.of(RESOURCES.resolve("schema.sql")));
        Map<String, String> delivered = new TreeMap<>();
        delivered.put("postgres-migrations", shape(h2("PostgreSQL", "migrations"), migrations()));
        delivered.put("mysql", shape(h2("MySQL", "mysql"), List.of(RESOURCES.resolve("schema-mysql.sql"))));
        delivered.put("sqlite", shape(DriverManager.getConnection("jdbc:sqlite::memory:"), List.of(RESOURCES.resolve("schema-sqlite.sql"))));
        List<String> problems = new ArrayList<>();
        for (var entry : delivered.entrySet()) {
            String difference = difference(reference, entry.getValue());
            if (!difference.isEmpty()) problems.add("Schema " + entry.getKey() + " differs from schema.sql:\n" + difference);
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Lines prefixed with their table: "-" only in schema.sql, "+" only in the delivered schema. */
    private static String difference(String expected, String actual) {
        var left = qualified(expected);
        var right = qualified(actual);
        StringBuilder out = new StringBuilder();
        for (String line : left) if (!right.contains(line)) out.append("  - ").append(line).append('\n');
        for (String line : right) if (!left.contains(line)) out.append("  + ").append(line).append('\n');
        return out.toString();
    }

    private static TreeSet<String> qualified(String shape) {
        var lines = new TreeSet<String>();
        String table = "";
        for (String line : shape.split("\n")) {
            if (line.startsWith("table ")) { table = line.substring(6); lines.add(line); }
            else lines.add(table + ":" + line.trim());
        }
        return lines;
    }

    @Test
    void migrationVersionsAreDocumented() throws Exception {
        List<String> versions = new ArrayList<>();
        for (Path path : migrations()) versions.add(path.getFileName().toString().replaceAll("__.*", ""));
        String readme = Files.readString(MIGRATIONS.resolve("../README.md"));
        // V004/V005 belonged to the retired server member runtime; their numbers must stay unused and explained.
        assertEquals(List.of("V001", "V002", "V003", "V006", "V007"), versions.subList(0, 5));
        assertTrue(readme.contains("V004") && readme.contains("V005"), "README must explain the V004/V005 gap");
    }

    private static List<Path> migrations() throws Exception {
        try (var paths = Files.list(MIGRATIONS)) {
            return paths.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static Connection h2(String mode, String name) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:structure-" + name + ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE", "sa", "");
    }

    private static String shape(Connection connection, List<Path> scripts) throws SQLException {
        try (connection) {
            for (Path script : scripts) ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
            DatabaseMetaData meta = connection.getMetaData();
            StringBuilder out = new StringBuilder();
            for (String table : tables(meta)) {
                out.append("table ").append(table).append('\n');
                List<String> primary = new ArrayList<>();
                try (var rs = meta.getPrimaryKeys(null, schema(meta), table)) {
                    var ordered = new TreeMap<Integer, String>();
                    while (rs.next()) ordered.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    primary.addAll(ordered.values());
                }
                try (var rs = meta.getColumns(null, schema(meta), table, null)) {
                    var columns = new TreeSet<String>();
                    while (rs.next()) {
                        String column = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                        // SQLite reports a non-integer primary key column as nullable; every DDL treats it as required.
                        boolean required = rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls || primary.contains(column);
                        columns.add("  column " + column + " " + type(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"))
                                + (required ? " not null" : ""));
                    }
                    columns.forEach(c -> out.append(c).append('\n'));
                }
                out.append("  primary ").append(primary).append('\n');
                try (var rs = meta.getImportedKeys(null, schema(meta), table)) {
                    var keys = new TreeSet<String>();
                    while (rs.next()) {
                        keys.add("  foreign " + rs.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT) + " -> "
                                + rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT) + "."
                                + rs.getString("PKCOLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                    keys.forEach(k -> out.append(k).append('\n'));
                }
                indexes(meta, table, primary).forEach(i -> out.append(i).append('\n'));
            }
            return out.toString();
        }
    }

    private static String schema(DatabaseMetaData meta) throws SQLException {
        return meta.getURL().startsWith("jdbc:h2:") ? "public" : null;
    }

    private static TreeSet<String> tables(DatabaseMetaData meta) throws SQLException {
        var tables = new TreeSet<String>();
        try (var rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                // H2 exposes its PostgreSQL catalog emulation as ordinary tables; only application tables count.
                if (schema != null && !schema.equalsIgnoreCase("public")) continue;
                tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    /** Unique constraints and secondary indexes by column list; names differ across engines and are ignored. */
    private static TreeSet<String> indexes(DatabaseMetaData meta, String table, List<String> primary) throws SQLException {
        Map<String, TreeMap<Integer, String>> columns = new TreeMap<>();
        Map<String, Boolean> unique = new TreeMap<>();
        try (var rs = meta.getIndexInfo(null, schema(meta), table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                columns.computeIfAbsent(name, k -> new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                unique.put(name, !rs.getBoolean("NON_UNIQUE"));
            }
        }
        var result = new TreeSet<String>();
        for (var entry : columns.entrySet()) {
            List<String> list = new ArrayList<>(entry.getValue().values());
            if (list.equals(primary)) continue;
            boolean isUnique = unique.get(entry.getKey());
            // Engines differ on whether a foreign key creates an implicit non-unique index; only explicit ones count.
            if (!isUnique && isImplicitForeignKeyIndex(entry.getKey())) continue;
            result.add((isUnique ? "  unique " : "  index ") + list);
        }
        return result;
    }

    private static boolean isImplicitForeignKeyIndex(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("fk") || lower.contains("_index_") || lower.startsWith("constraint_");
    }

    private static String type(String name, int size) {
        String t = name.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "").trim();
        if (t.contains("large object") || t.contains("text") || t.contains("clob")) return "text";
        // H2 maps unbounded text to CHARACTER VARYING with the engine's maximum length.
        if (t.contains("char")) return size >= 1_000_000_000 ? "text" : "varchar(" + size + ")";
        if (t.equals("bigint") || t.equals("int8")) return "bigint";
        if (t.equals("integer") || t.equals("int") || t.equals("int4")) return "integer";
        if (t.startsWith("bool")) return "boolean";
        if (t.startsWith("timestamp") || t.equals("datetime")) return "timestamp";
        return t;
    }
}
