-- Обход (технический документ §4 CrawlRun): одна запись на каждое чтение источника.
--   state          — COMPLETE: список прочитан полностью; PARTIAL: неполно (partial_reason);
--                    FAILED: источник не отдал список (failure_kind, failure_reason);
--   partial_reason — PAGE_LIMIT (упор в потолок страниц), PAGE_FAILED (отказ страницы после
--                    первой), LIST_ENDED_EARLY (список кончился раньше объявленного числа);
--   failure_kind   — TEMPORARY или вид постоянного отказа (ACCESS_DENIED, NOT_FOUND, BLOCKED …);
--   task_id        — задание чтения; при удалении задания история обходов сохраняется (SET NULL).
CREATE TABLE crawl_run (
    id             BIGSERIAL     PRIMARY KEY,
    source_id      BIGINT        NOT NULL REFERENCES source (id),
    task_id        BIGINT        REFERENCES task (id) ON DELETE SET NULL,
    started_at     TIMESTAMPTZ   NOT NULL,
    finished_at    TIMESTAMPTZ   NOT NULL,
    state          VARCHAR(20)   NOT NULL CHECK (state IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    partial_reason VARCHAR(30),
    failure_kind   VARCHAR(30),
    failure_reason VARCHAR(1000),
    postings_count INTEGER       NOT NULL,
    CHECK ((state = 'PARTIAL') = (partial_reason IS NOT NULL)),
    CHECK ((state = 'FAILED') = (failure_kind IS NOT NULL))
);

-- Обходы источника по времени: последний обход, история для показателей.
CREATE INDEX crawl_run_source_idx ON crawl_run (source_id, started_at);
