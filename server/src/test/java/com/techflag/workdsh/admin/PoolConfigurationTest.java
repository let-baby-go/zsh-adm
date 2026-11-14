package com.techflag.workdsh.admin;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** The deployment knobs for database connections and stream threads reach the running pools. */
class PoolConfigurationTest {
    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:pool-config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "workdsh.bootstrap.admin-email=pool@example.test",
            "workdsh.bootstrap.admin-password=PoolPassword123!",
            "WORKDSH_DB_POOL_SIZE=7",
            "WORKDSH_DB_CONNECTION_TIMEOUT_MS=5000",
            "WORKDSH_GATEWAY_MAX_CONCURRENT_STREAMS=64"})
    class ServerDatabase {
        @Autowired DataSource dataSource;
        @Autowired @Qualifier("applicationTaskExecutor") ThreadPoolTaskExecutor streams;

        @Test
        void environmentSizesThePools() {
            var pool = (HikariDataSource) dataSource;
            assertEquals(7, pool.getMaximumPoolSize());
            assertEquals(5000, pool.getConnectionTimeout());
            assertEquals(64, streams.getMaxPoolSize());
            assertEquals(0, streams.getQueueCapacity(), "streams are never queued behind busy threads");
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
