package com.roleorienta.worker.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.roleorienta.worker.TestcontainersConfiguration;
import com.roleorienta.worker.task.TaskExecutor;
import com.roleorienta.worker.task.TaskService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Приём юрлиц из выгрузок RPO на заглушке хранилища ({@link MockitoBean} подменяет
 * {@link RpoExports}) и настоящей PostgreSQL: полная выгрузка файлами и порциями, отбор записей,
 * ежедневные изменения, продолжение с места курсора. Порция — 2 записи, задание — 3 записи: чтение
 * идёт цепочкой заданий.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"app.intake.batch-size=2", "app.intake.records-per-task=3"})
class RegistryIntakeTests {

    private static final LocalDate EXPORT = LocalDate.of(2026, 1, 3);
    private static final String FILE_1 = "batch-init/init_2026-01-03_001.json.gz";
    private static final String FILE_2 = "batch-init/init_2026-01-03_002.json.gz";
    private static final int MAX_TASK_ROUNDS = 20;

    /** Файл 1 — JSON-массив: s.r.o., предприниматель-физлицо, город. */
    private static final String FILE_1_JSON = "["
            + entity("11111111", "Alfa s.r.o.", "112", "Spoločnosť s ručením obmedzeným", null) + ","
            + entity("22222222", "Ján Novák", "101", "Podnikateľ-fyzická osoba-nezapísaný v obchodnom registri", null)
            + "," + entity("33333333", "Mesto Trnava", "801", "Obec (obecný úrad), mesto (mestský úrad)", null)
            + "]";

    /** Файл 2 — записи подряд: прекращённое неизвестное юрлицо, запись без IČO, a.s. */
    private static final String FILE_2_JSON = entity("44444444", "Stará s.r.o.", "112",
            "Spoločnosť s ručením obmedzeným", "2020-01-01") + "\n"
            + "{\"id\":5,\"fullNames\":[{\"value\":\"Bez IČO\"}]}\n"
            + entity("55555555", "Beta a.s.", "121", "Akciová spoločnosť", null);

    /** Ежедневная выгрузка за дату полной: переименование и прекращение. */
    private static final String DAILY_JSON = "["
            + entity("11111111", "Alfa Group s.r.o.", "112", "Spoločnosť s ručením obmedzeným", null) + ","
            + entity("55555555", "Beta a.s.", "121", "Akciová spoločnosť", "2026-01-03") + "]";

    @MockitoBean
    private RpoExports exports;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Чистые таблицы; хранилище с полной выгрузкой из двух файлов и одной ежедневной.
     *
     * @throws IOException не бросается заглушкой
     */
    @BeforeEach
    void setUp() throws IOException {
        for (String table : new String[] {"company", "intake_cursor", "outbox_event", "task"}) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
        when(exports.latestInit()).thenReturn(Optional.of(EXPORT));
        when(exports.initFiles(EXPORT)).thenReturn(List.of(FILE_1, FILE_2));
        when(exports.exists(RpoExports.dailyKey(EXPORT))).thenReturn(true);
        when(exports.open(FILE_1)).thenAnswer(call -> stream(FILE_1_JSON));
        when(exports.open(FILE_2)).thenAnswer(call -> stream(FILE_2_JSON));
        when(exports.open(RpoExports.dailyKey(EXPORT))).thenAnswer(call -> stream(DAILY_JSON));
    }

    /**
     * Полная выгрузка: берутся действующие юрлица, включая город; физлицо, запись без IČO и
     * неизвестное прекращённое — нет. Затем ежедневная: переименование и прекращение. Следующей
     * ежедневной нет, она вне срока хранения, а новее полной выгрузки нет — приём ждёт.
     */
    @Test
    void importsFullExportThenDailyChanges() {
        runIntake();

        assertThat(jdbcTemplate.queryForList("""
                SELECT registration_number || ':' || name || ':' || coalesce(terminated_on::text, '-')
                FROM company ORDER BY registration_number
                """, String.class)).containsExactly("11111111:Alfa Group s.r.o.:-", "33333333:Mesto Trnava:-",
                "55555555:Beta a.s.:2026-01-03");
        assertThat(jdbcTemplate.queryForObject("SELECT daily_date FROM intake_cursor", LocalDate.class))
                .isEqualTo(EXPORT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task WHERE state = 'DONE'", Integer.class))
                .isGreaterThan(1);
    }

    /**
     * После остановки чтение продолжается с места курсора: две записи первого файла уже обработаны
     * и не читаются снова.
     */
    @Test
    void resumesFromCursorPlace() {
        jdbcTemplate.update("""
                INSERT INTO intake_cursor (country, registry, export_date, file_index, record_offset)
                VALUES ('SK', 'RPO', ?, 0, 2)
                """, EXPORT);

        runIntake();

        assertThat(jdbcTemplate.queryForList("SELECT registration_number FROM company ORDER BY 1", String.class))
                .containsExactly("11111111", "33333333", "55555555");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM company WHERE registration_number = '11111111'", String.class))
                .as("known only from the daily export").isEqualTo("Alfa Group s.r.o.");
    }

    private void runIntake() {
        taskService.enqueue(RegistryIntakeHandler.TYPE, RegistryIntakeHandler.taskKey("test"),
                RegistryIntakeHandler.payload());
        for (int round = 0; round < MAX_TASK_ROUNDS; round++) {
            List<Long> queued = jdbcTemplate.queryForList("SELECT id FROM task WHERE state = 'QUEUED' ORDER BY id",
                    Long.class);
            if (queued.isEmpty()) {
                return;
            }
            queued.forEach(executor::execute);
        }
        throw new AssertionError("Intake did not finish in " + MAX_TASK_ROUNDS + " rounds");
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String entity(String number, String name, String formCode, String formName, String termination) {
        return "{\"id\":" + number + ",\"identifiers\":[{\"value\":\"" + number + "\",\"validFrom\":\"2000-01-01\"}],"
                + "\"fullNames\":[{\"value\":\"Old name\",\"validTo\":\"2010-01-01\"},{\"value\":\"" + name + "\"}],"
                + "\"legalForms\":[{\"value\":{\"value\":\"" + formName + "\",\"code\":\"" + formCode + "\"}}],"
                + "\"addresses\":[{\"municipality\":{\"value\":\"Bratislava\"}}]"
                + (termination == null ? "" : ",\"termination\":\"" + termination + "\"") + "}";
    }
}
