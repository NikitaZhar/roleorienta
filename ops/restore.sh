#!/bin/sh
# Восстановление PostgreSQL из резервной копии (стенограмма §141). Запуск из корня проекта, docker compose запущен.
#
#   ops/restore.sh backups/roleorienta-20261019T150000Z.dump
#       проверка: копия восстанавливается в отдельную базу roleorienta_restore (рабочая база не трогается),
#       печатаются версия схемы и число записей основных таблиц — сравнить с рабочей базой.
#   ops/restore.sh backups/roleorienta-20261019T150000Z.dump --replace
#       восстановление рабочей базы: job-api и job-worker должны быть остановлены; рабочая база удаляется и
#       создаётся из копии.
set -eu

DUMP="${1:?usage: ops/restore.sh <dump> [--replace]}"
DB="${DB_NAME:-roleorienta}"
OWNER="${DB_USERNAME:-roleorienta}"
TARGET="${DB}_restore"
if [ "${2:-}" = "--replace" ]; then
    TARGET="$DB"
fi
[ -f "$DUMP" ] || { echo "No such file: $DUMP" >&2; exit 1; }

psql() { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$OWNER" "$@"; }

echo "Restoring $DUMP into database $TARGET"
psql -d postgres -c "DROP DATABASE IF EXISTS \"$TARGET\" WITH (FORCE)"
psql -d postgres -c "CREATE DATABASE \"$TARGET\" OWNER \"$OWNER\""
docker compose exec -T postgres pg_restore --no-owner --exit-on-error -U "$OWNER" -d "$TARGET" < "$DUMP"

for D in "$TARGET" "$DB"; do
    echo "== $D"
    psql -d "$D" -At -c "SELECT 'schema version ' || max(version::int) FROM flyway_schema_history WHERE success" \
        -c "SELECT 'company ' || count(*) FROM company" \
        -c "SELECT 'company_site ' || count(*) FROM company_site" \
        -c "SELECT 'source ' || count(*) FROM source" \
        -c "SELECT 'vacancy ' || count(*) FROM vacancy" \
        -c "SELECT 'user_account ' || count(*) FROM user_account"
    [ "$TARGET" = "$DB" ] && break
done
