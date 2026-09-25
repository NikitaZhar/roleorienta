#!/usr/bin/env bash
# Поднимает локальную инфраструктуру для разработки:
# PostgreSQL (хост-порт 5433), RabbitMQ, MinIO, заглушку источника (WireMock).
# Требует запущенного Docker Desktop.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Запуск инфраструктуры (postgres, rabbitmq, minio, mailpit, source-stub)..."
docker compose up -d postgres rabbitmq minio mailpit source-stub

echo -n "Ожидание готовности PostgreSQL"
cid="$(docker compose ps -q postgres)"
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null)" = "healthy" ]; do
  echo -n "."
  sleep 1
done
echo " готово."

echo
docker compose ps
echo
echo "Инфраструктура поднята. Дальше: ./scripts/run-api.sh"
