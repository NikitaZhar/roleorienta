#!/usr/bin/env bash
# Запускает job-worker локально (вне Docker).
# Требует: поднятой инфраструктуры (./scripts/dev-up.sh) и JDK 21.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Установка модуля core в локальный репозиторий..."
mvn -q -pl core install -DskipTests
# Локальная разработка идёт против заглушки-источника на loopback (или приватной
# сети compose), поэтому для защищённого HTTP-клиента сбора (§32, SSRF) разрешаем
# приватные адреса ТОЛЬКО здесь. В production флаг не задаётся (по умолчанию строго).
export APP_COLLECT_HTTP_ALLOW_PRIVATE_ADDRESSES="${APP_COLLECT_HTTP_ALLOW_PRIVATE_ADDRESSES:-true}"
echo "Запуск job-worker → http://localhost:8081 (health: /actuator/health). Ctrl+C — стоп."
mvn -pl job-worker spring-boot:run
