#!/usr/bin/env bash
# Загружает демо-данные локального пилота (провайдер Greenhouse + активный источник
# на заглушку) в PostgreSQL контейнера. Схема к этому моменту должна быть применена
# (job-api хотя бы раз стартовал и накатил миграции Flyway).
# Идемпотентен: повторный запуск не создаёт дублей.
set -euo pipefail
cd "$(dirname "$0")/.."

DB_NAME="${DB_NAME:-roleorienta}"
DB_USERNAME="${DB_USERNAME:-roleorienta}"

echo "Загрузка демо-данных в базу ${DB_NAME}..."
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "${DB_USERNAME}" -d "${DB_NAME}" < scripts/dev-seed.sql
echo "Готово. Активный источник Greenhouse (acme) создан."
