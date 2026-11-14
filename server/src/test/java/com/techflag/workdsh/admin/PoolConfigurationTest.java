package com.techflag.workdsh.admin;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import static org.junit.jupiter.api.Assertions.*;

/** The deployment knobs reach the running server: virtual threads, the model-call limit and the database pool. */
class PoolConfigurationTest {
    /** Reports which kind of thread served the request and wrote the streamed body. */
    @RestController
    static class ThreadProbe {
        @GetMapping("/probe/threads")
        StreamingResponseBody threads() {
            boolean request = Thread.currentThread().isVirtual();
            return out -> out.write(("request=" + request + " stream=" + Thread.currentThread().isVirtual()).getBytes());
        }
    }

    @TestConfiguration
    @Import(ThreadProbe.class)
    static class Probe {}

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {AdminApplication.class, Probe.class}, properties = {
            "spring.datasource.url=jdbc:h2:mem:pool-config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "workdsh.bootstrap.admin-email=pool@example.test",
            "workdsh.bootstrap.admin-password=PoolPassword123!",
            "WORKDSH_DB_POOL_SIZE=7",
            "WORKDSH_DB_CONNECTION_TIMEOUT_MS=5000",
            "WORKDSH_GATEWAY_MAX_CONCURRENT_REQUESTS=64"})
    class ServerDatabase {
        @LocalServerPort int port;
        @Autowired DataSource dataSource;
        @Autowired GatewayLimiter limiter;

        @Test
        void environmentConfiguresTheServer() throws Exception {
            var pool = (HikariDataSource) dataSource;
            assertEquals(7, pool.getMaximumPoolSize());
            assertEquals(5000, pool.getConnectionTimeout());
            assertEquals(64, limiter.limit());
            var reply = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/probe/threads")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("request=true stream=true", reply.body(), "requests and streamed bodies run on virtual threads");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:sqlite:file:pool-config-sqlite?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=pool-sqlite@example.test",
            "workdsh.bootstrap.admin-password=PoolPassword123!",
            "WORKDSH_DB_POOL_SIZE=7"})
    @ActiveProfiles("enterprise-sqlite")
    class Sqlite {
        @Autowired DataSource dataSource;

        @Test
        void sqliteKeepsItsSingleWriterConnection() {
            assertEquals(1, ((HikariDataSource) dataSource).getMaximumPoolSize());
        }
    }
}
