package com.techflag.workdsh.admin;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** S3BodyStore as configured by BodyStoreConfig, against a minimal path-style S3 endpoint. */
class S3BodyStoreTest {
    @Test
    void putGetDeleteWithPlainBodiesAndPrefix() throws Exception {
        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        Map<String, String> headers = new ConcurrentHashMap<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(path, body);
                    headers.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                    headers.put("content-encoding", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Encoding")));
                    headers.put("content-sha256", String.valueOf(exchange.getRequestHeaders().getFirst("x-amz-content-sha256")));
                    exchange.getResponseHeaders().set("ETag", "\"etag\"");
                    exchange.sendResponseHeaders(200, -1);
                }
                case "GET" -> {
                    byte[] found = objects.get(path);
                    if (found == null) { exchange.sendResponseHeaders(404, -1); break; }
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, found.length);
                    try (var out = exchange.getResponseBody()) { out.write(found); }
                }
                case "DELETE" -> { objects.remove(path); exchange.sendResponseHeaders(204, -1); }
                default -> exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            var store = new BodyStoreConfig().bodyStore("s3", "http://127.0.0.1:" + server.getAddress().getPort(), "us-east-1",
                    "bodies", "/workdsh/", "test-access", "test-secret", true);
            assertTrue(store.external());
            String key = BodyObjects.key("org-1", "member-1", "client-session", 3);
            store.put(key, "可见正文 visible text");
            String path = "/bodies/workdsh/" + key;
            assertArrayEquals("可见正文 visible text".getBytes(java.nio.charset.StandardCharsets.UTF_8), objects.get(path), "plain body, path-style, prefixed");
            assertEquals("null", headers.get("content-encoding"), "no aws-chunked encoding for S3-compatible services");
            assertFalse(headers.get("content-sha256").startsWith("STREAMING"), "no streaming signature");
            assertTrue(headers.get("authorization").startsWith("AWS4-HMAC-SHA256 Credential=test-access/"));
            assertEquals("可见正文 visible text", store.get(key));
            store.delete(List.of(key));
            assertFalse(objects.containsKey(path));
            assertFalse(path.contains("client-session"), "caller-chosen session IDs never appear in object keys");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void misconfigurationFailsAtStartup() {
        var config = new BodyStoreConfig();
        assertThrows(IllegalStateException.class, () -> config.bodyStore("s3", "", "us-east-1", "", "", "", "", false));
        assertThrows(IllegalStateException.class, () -> config.bodyStore("s3", "", "us-east-1", "bucket", "", "only-access", "", false));
        assertThrows(IllegalStateException.class, () -> config.bodyStore("filesystem", "", "", "", "", "", "", false));
        assertFalse(config.bodyStore("database", "", "", "", "", "", "", false).external());
    }
}
