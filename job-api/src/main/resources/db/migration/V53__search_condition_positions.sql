-- Несколько позиций в условиях поиска (решение владельца 2026-10-16, стенограмма §126): позиции версии условий —
-- отдельной таблицей; вакансия подходит, если сопоставлена хотя бы одной из них. Прежняя единственная позиция
-- (search_condition.position_id) переносится, столбец удаляется. Позиции — часть версии: удаляются вместе с ней.
CREATE TABLE search_condition_position (
    search_condition_id BIGINT NOT NULL REFERENCES search_condition (id) ON DELETE CASCADE,
    position_id         BIGINT NOT NULL REFERENCES position (id),
    PRIMARY KEY (search_condition_id, position_id)
);

INSERT INTO search_condition_position (search_condition_id, position_id)
SELECT id, position_id FROM search_condition;

ALTER TABLE search_condition DROP COLUMN position_id;
