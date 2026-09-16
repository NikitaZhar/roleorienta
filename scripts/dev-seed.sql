-- Демо-данные для локального пилота (Этап 1): один провайдер Greenhouse и один
-- активный источник, указывающий на заглушку WireMock. Нужны, чтобы планировщику
-- было что планировать, а адаптеру — что читать (сквозной сценарий сбора).
--
-- Это НЕ миграция Flyway: миграции применяются во всех окружениях, а демо-источник
-- нужен только локально. Скрипт идемпотентен (ON CONFLICT DO NOTHING) — повторный
-- запуск не создаёт дублей.
--
-- Запуск: ./scripts/dev-seed.sh (обёртка над psql в контейнере postgres).
--
-- Адрес заглушки: http://localhost:8089 — для рекомендованного локального режима
-- (инфраструктура в Docker, приложения на хосте). Если запускать worker целиком в
-- Compose, источник должен указывать на http://source-stub:8080 (см. docker-compose.yml).

INSERT INTO provider (code, display_name, kind)
VALUES ('greenhouse', 'Greenhouse', 'ATS')
ON CONFLICT ON CONSTRAINT uq_provider_code DO NOTHING;

INSERT INTO source (provider_id, kind, external_ref, base_url, schedule, state, adapter_version)
SELECT p.id, 'COMPANY_BOARD', 'acme', 'http://localhost:8089', NULL, 'ACTIVE', 'greenhouse-1'
FROM provider p
WHERE p.code = 'greenhouse'
ON CONFLICT ON CONSTRAINT uq_source_provider_external_ref DO NOTHING;
