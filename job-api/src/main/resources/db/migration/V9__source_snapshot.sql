-- Снимок исходного ответа источника (технический документ §4 SourceSnapshot, §16.10): ссылка обхода
-- на объект в S3-хранилище. Ключ — <источник>/<sha256 тела>: неизменяемый, одинаковый ответ
-- хранится одним объектом, хеш проверяет содержимое. Порядок записи — сначала объект, затем эта
-- строка (§9); объект без строки — сирота, очищается отдельно.
CREATE TABLE source_snapshot (
    id           BIGSERIAL    PRIMARY KEY,
    crawl_run_id BIGINT       NOT NULL REFERENCES crawl_run (id),
    object_key   VARCHAR(200) NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    stored_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX source_snapshot_run_idx ON source_snapshot (crawl_run_id);
