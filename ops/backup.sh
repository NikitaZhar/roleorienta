#!/bin/sh
# Резервная копия PostgreSQL (стенограмма §141): запускается сервисом backup из docker-compose.yml.
# Сразу при старте и затем раз в BACKUP_INTERVAL секунд — pg_dump в формате custom в /backups
# (на хосте ./backups); хранятся последние BACKUP_KEEP копий. Неудача копии — запись в лог, следующая попытка
# по расписанию; недописанная копия не остаётся (пишется во временный файл).
# Подключение — переменные PGHOST, PGUSER, PGPASSWORD, PGDATABASE.

INTERVAL="${BACKUP_INTERVAL:-86400}"
KEEP="${BACKUP_KEEP:-14}"
DIR=/backups

while true; do
    NAME="$DIR/${PGDATABASE}-$(date -u +%Y%m%dT%H%M%SZ).dump"
    if pg_dump --format=custom --file="$NAME.tmp"; then
        mv "$NAME.tmp" "$NAME"
        echo "$(date -u +%FT%TZ) backup written: $NAME ($(du -h "$NAME" | cut -f1))"
        ls -1t "$DIR/${PGDATABASE}"-*.dump | tail -n +"$((KEEP + 1))" | while read -r OLD; do
            rm -f "$OLD"
            echo "$(date -u +%FT%TZ) old backup deleted: $OLD"
        done
    else
        rm -f "$NAME.tmp"
        echo "$(date -u +%FT%TZ) backup FAILED, next attempt in ${INTERVAL}s" >&2
    fi
    sleep "$INTERVAL"
done
