#!/usr/bin/env bash
# Останавливает локальную инфраструктуру. Данные PostgreSQL сохраняются в томе.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
echo "Инфраструктура остановлена (данные сохранены в томах)."
