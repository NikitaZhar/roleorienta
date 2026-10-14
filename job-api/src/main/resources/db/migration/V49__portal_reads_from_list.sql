-- Вакансии государственного портала записываются в источники из общего списка (задание PORTAL_RECORD, стенограмма
-- §107); чтение источника портала по работодателю (READ_SOURCE) и шаг «проверка работодателей» (STATE_PORTAL, ключи
-- state-portal:check:…) удалены. Их незавершённые задания, оставшиеся от прежней версии, закрываются неудачей с
-- причиной: иначе чтение по работодателю упало бы на адаптере, а задание проверки ушло бы в шаг «сайт».
UPDATE task SET state = 'FAILED', next_attempt_at = NULL, lease_until = NULL, updated_at = now(),
                last_error = 'Replaced by the state portal general list (§107)'
WHERE state IN ('QUEUED', 'RUNNING', 'WAITING')
  AND ((type = 'READ_SOURCE'
        AND CAST(payload ->> 'sourceId' AS BIGINT) IN (SELECT id FROM source WHERE provider = 'sluzbyzamestnanosti'))
       OR (type = 'STATE_PORTAL' AND task_key LIKE 'state-portal:check:%'));
