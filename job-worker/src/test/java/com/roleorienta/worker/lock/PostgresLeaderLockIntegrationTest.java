package com.roleorienta.worker.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Интеграционный тест leader-lock на реальном PostgreSQL (Testcontainers).
 * Проверяет, что незанятый лок захватывается и задача выполняется, а при
 * удержании лока другой транзакцией повторный захват того же ключа не проходит.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PostgresLeaderLockIntegrationTest {

    @Autowired
    private PostgresLeaderLock leaderLock;
    @Autowired
    private DataSource dataSource;

    @Test
    void runsTaskWhenLockIsFree() {
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean acquired = leaderLock.runIfLeader(4242L, () -> ran.set(true));

        assertThat(acquired).isTrue();
        assertThat(ran).isTrue();
    }

    @Test
    void secondAcquirerIsRejectedWhileFirstHoldsLock() throws Exception {
        long key = 9999L;
        try (Connection first = dataSource.getConnection()) {
            first.setAutoCommit(false);
            assertThat(tryAdvisoryXactLock(first, key)).isTrue();

            try (Connection second = dataSource.getConnection()) {
                second.setAutoCommit(false);
                assertThat(tryAdvisoryXactLock(second, key)).isFalse();
                second.rollback();
            }
            // Откат первой транзакции освобождает транзакционный advisory-лок.
            first.rollback();
        }
    }

    /**
     * Выполняет {@code pg_try_advisory_xact_lock} на заданном соединении.
     *
     * @param connection открытое соединение с активной транзакцией
     * @param key        ключ лока
     * @return {@code true}, если лок захвачен этой транзакцией
     */
    private boolean tryAdvisoryXactLock(Connection connection, long key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
