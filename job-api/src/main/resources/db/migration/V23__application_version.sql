-- V23 — версия оптимистичной блокировки отклика (A19): сильный ETag карточки.
-- expand: добавляем столбец со значением по умолчанию для существующих строк; JPA
-- (@Version) далее ведёт его сам, увеличивая при каждом изменении.

ALTER TABLE application ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
