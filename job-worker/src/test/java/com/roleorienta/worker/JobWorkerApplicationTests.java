package com.roleorienta.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

/**
 * Контекст job-worker поднимается на реальных PostgreSQL и RabbitMQ.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JobWorkerApplicationTests {

    /** Потоков тиков (§277): зависший тик не останавливает остальные. */
    private static final int TICK_THREADS = 8;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Контекст приложения загружается без ошибок.
     */
    @Test
    void contextLoads() {
    }

    /**
     * Тики идут в нескольких потоках, а ожидание блокировки в соединениях ограничено (§277: ядро стояло 06.11–09.11,
     * все тики ждали один).
     */
    @Test
    void ticksRunInSeveralThreadsAndLockWaitIsBounded() {
        // настроенный размер пула; getPoolSize() — число уже запущенных потоков
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(TICK_THREADS);
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2min");
    }
}
