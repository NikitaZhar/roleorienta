package com.roleorienta.worker.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Бюджет хоста в PostgreSQL: очередь с промежутком, потолок ожидания, {@code Retry-After},
 * конкурирующие резервирования. Промежуток 10 с — ожидания различимы без реального сна.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PostgresHostBudgetTests {

    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final int CONCURRENT = 8;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Первый запрос — сразу, второй — через промежуток, третий дальше потолка (15 с) — отказ.
     */
    @Test
    void queuesRequestsAndRefusesBeyondMaxWait() {
        HostBudget budget = budget(Duration.ofSeconds(15));

        assertThat(seconds(budget.reserve("queue.example"))).isZero();
        assertThat(seconds(budget.reserve("queue.example"))).isEqualTo(10);
        assertThat(budget.reserve("queue.example")).isEmpty();
        assertThat(seconds(budget.reserve("other.example"))).isZero();
    }

    /**
     * {@code Retry-After} на 60 с отодвигает очередь хоста за потолок ожидания.
     */
    @Test
    void backOffPushesQueue() {
        HostBudget budget = budget(Duration.ofSeconds(30));

        budget.backOff("busy.example", Duration.ofSeconds(60));

        assertThat(budget.reserve("busy.example")).isEmpty();
    }

    /**
     * Реплики резервируют одновременно — каждая получает своё место, мест не повторяется.
     *
     * @throws Exception ошибка потока
     */
    @Test
    void concurrentReservationsGetDistinctSlots() throws Exception {
        HostBudget budget = budget(Duration.ofMinutes(10));
        List<Callable<Long>> reservations = new ArrayList<>();
        for (int index = 0; index < CONCURRENT; index++) {
            reservations.add(() -> seconds(budget.reserve("race.example")));
        }
        List<Long> waits = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        try {
            for (Future<Long> wait : pool.invokeAll(reservations)) {
                waits.add(wait.get());
            }
        } finally {
            pool.shutdown();
        }

        assertThat(waits).containsExactlyInAnyOrder(0L, 10L, 20L, 30L, 40L, 50L, 60L, 70L);
    }

    private HostBudget budget(Duration maxWait) {
        return new PostgresHostBudget(jdbcTemplate, new PolitenessProperties("test", INTERVAL, maxWait));
    }

    private static long seconds(Optional<Duration> wait) {
        return Math.round(wait.orElseThrow().toMillis() / 1000.0);
    }
}
