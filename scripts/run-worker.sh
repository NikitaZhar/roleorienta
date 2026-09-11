#!/usr/bin/env bash
# Запускает job-worker локально (вне Docker).
# Требует: поднятой инфраструктуры (./scripts/dev-up.sh) и JDK 21.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Установка модуля core в локальный репозиторий..."
mvn -q -pl core install -DskipTests
echo "Запуск job-worker → http://localhost:8081 (health: /actuator/health). Ctrl+C — стоп."
mvn -pl job-worker spring-boot:run
