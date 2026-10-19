#!/bin/sh
# Восстановление PostgreSQL из резервной копии (стенограмма §141, §143). Запуск из корня проекта, docker compose
# запущен. Имя базы и пользователь — из окружения или .env (DB_NAME, DB_USERNAME), как у docker compose.
#
#   ops/restore.sh backups/roleorienta-20261019T150000Z.dump
#       проверка: копия восстанавливается в отдельную базу roleorienta_restore (рабочая база не трогается),
#       печатаются версия схемы и число записей основных таблиц копии и рабочей базы.
#   ops/restore.sh backups/roleorienta-20261019T150000Z.dump --replace
#       замена рабочей базы: копия восстанавливается в roleorienta_restore; только после успешного восстановления
#       рабочая база переименовывается в roleorienta_old_<время>, а восстановленная — в рабочую. Если к рабочей базе
#       есть подключения (job-api, job-worker), скрипт останавливается до замены.
set -eu

if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

DUMP="${1:?usage: ops/restore.sh <dump> [--replace]}"
DB="${DB_NAME:-roleorienta}"
OWNER="${DB_USERNAME:-roleorienta}"
RESTORED="${DB}_restore"
[ -f "$DUMP" ] || { echo "No such file: $DUMP" >&2; exit 1; }

psql() { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$OWNER" "$@"; }

counts() {
    echo "== $1"
    psql -d "$1" -At -c "SELECT 'schema version ' || max(version::int) FROM flyway_schema_history WHERE success" \
        -c "SELECT 'company ' || count(*) FROM company" \
        -c "SELECT 'company_site ' || count(*) FROM company_site" \
        -c "SELECT 'source ' || count(*) FROM source" \
        -c "SELECT 'vacancy ' || count(*) FROM vacancy" \
        -c "SELECT 'user_account ' || count(*) FROM user_account"
}

echo "Restoring $DUMP into database $RESTORED"
psql -d postgres -c "DROP DATABASE IF EXISTS \"$RESTORED\" WITH (FORCE)"
psql -d postgres -c "CREATE DATABASE \"$RESTORED\" OWNER \"$OWNER\""
docker compose exec -T postgres pg_restore --no-owner --exit-on-error -U "$OWNER" -d "$RESTORED" < "$DUMP"
counts "$RESTORED"
counts "$DB"

if [ "${2:-}" = "--replace" ]; then
    CONNECTIONS=$(psql -d postgres -At -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '$DB'")
    if [ "$CONNECTIONS" != "0" ]; then
        echo "Database $DB has $CONNECTIONS connections: stop job-api and job-worker, then run again." >&2
        echo "Nothing replaced; the restored copy is in $RESTORED." >&2
        exit 1
    fi
    OLD="${DB}_old_$(date -u +%Y%m%dT%H%M%SZ)"
    psql -d postgres -c "ALTER DATABASE \"$DB\" RENAME TO \"$OLD\"" \
        -c "ALTER DATABASE \"$RESTORED\" RENAME TO \"$DB\""
    echo "Working database replaced by the copy; the previous one is kept as $OLD (drop it when no longer needed)."
fi
