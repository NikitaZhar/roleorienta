package com.roleorienta.worker.discovery.cc;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.adapters.workday.WorkdayBoard;
import com.roleorienta.worker.discovery.cc.HarvestStore.BoardState;
import com.roleorienta.worker.discovery.cc.HarvestStore.Cursor;
import com.roleorienta.worker.discovery.cc.HarvestStore.PendingBoard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link HarvestStore} на реальном PostgreSQL (Testcontainers, §55): SQL, который в
 * unit-тесте не проверить — аренда через условный {@code UPDATE ... RETURNING} и
 * {@code make_interval}, дедуп досок через {@code ON CONFLICT} по ключу без учёта
 * регистра, атомарное продвижение курсора, выборка {@code NEW} с
 * {@code FOR UPDATE SKIP LOCKED} и смена состояния. Схема — фикстура
 * {@code /db/harvest-schema.sql} (копия V26).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Sql(scripts = "/db/harvest-schema.sql")
class HarvestStoreIntegrationTest {

    private static final String INPUT = "cc-workday";

    @Autowired
    private HarvestStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static WorkdayBoard board(String tenant, String site) {
        return new WorkdayBoard(tenant, site, "https://" + tenant + ".wd1.myworkdayjobs.com");
    }

    @Test
    void leaseIsExclusiveUntilReleased() {
        Optional<Cursor> first = store.tryLease(INPUT, 900);
        assertThat(first).contains(new Cursor(null, 0, 0, 0));

        assertThat(store.tryLease(INPUT, 900)).isEmpty();

        store.releaseLease(INPUT);
        assertThat(store.tryLease(INPUT, 900)).isPresent();
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        assertThat(store.tryLease(INPUT, 900)).isPresent();
        jdbc.update("UPDATE harvest_cursor SET lease_until = now() - interval '1 second'");

        assertThat(store.tryLease(INPUT, 900)).isPresent();
    }

    @Test
    void recordPageDedupsIgnoringCaseAndAdvancesCursor() {
        store.tryLease(INPUT, 900);

        int added = store.recordPage(INPUT, "workday", new Cursor("CC-1", 1, 5, 1),
                List.of(board("aig", "aig"), board("amgen", "Careers")));
        int again = store.recordPage(INPUT, "workday", new Cursor("CC-1", 1, 5, 2),
                List.of(board("aig", "AIG"), board("3m", "Search")));

        assertThat(added).isEqualTo(2);
        assertThat(again).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM harvested_board", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT slug FROM harvested_board WHERE dedup_key = 'aig/aig'", String.class))
                .as("первое написание сохраняется").isEqualTo("aig/aig");
        store.releaseLease(INPUT);
        assertThat(store.tryLease(INPUT, 900)).contains(new Cursor("CC-1", 1, 5, 2));
    }

    @Test
    void newBoardsAreHandedOutInOrderAndMarked() {
        store.tryLease(INPUT, 900);
        store.recordPage(INPUT, "workday", new Cursor("CC-1", 1, 5, 1),
                List.of(board("a", "One"), board("b", "Two"), board("c", "Three")));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<PendingBoard> firstBatch = tx.execute(status -> {
            List<PendingBoard> boards = store.lockNewBoards(2);
            store.mark(boards.get(0).id(), BoardState.ENQUEUED);
            store.mark(boards.get(1).id(), BoardState.SKIPPED);
            return boards;
        });

        assertThat(firstBatch).extracting(PendingBoard::slug).containsExactly("a/One", "b/Two");
        List<PendingBoard> rest = tx.execute(status -> store.lockNewBoards(10));
        assertThat(rest).extracting(PendingBoard::slug).containsExactly("c/Three");
        assertThat(jdbc.queryForList("SELECT state FROM harvested_board ORDER BY id", String.class))
                .containsExactly("ENQUEUED", "SKIPPED", "NEW");
    }
}
