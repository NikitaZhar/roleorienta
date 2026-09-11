# roleorienta

Приложение для поиска, мониторинга и анализа вакансий. Собирает вакансии не
только с агрегаторов, но и напрямую с карьерных страниц компаний, ведёт историю
изменений, строит технологический профиль работодателя и сопровождает кандидата
от находки вакансии до результатов собеседования.

Документация продукта и правил разработки — в [`docs/`](docs/): [бизнес-ТЗ](docs/business-requirements.md), [технический документ](docs/technical-design.md), [рабочий контракт](docs/working-contract.md).

> Статус репозитория: **каркас Этапа 1** (пилот). Бизнес-логика, модель данных,
> адаптеры источников и UI ещё не реализованы — добавляются последующими
> задачами в пределах согласованной области.

## Архитектура (Этап 1)

Monorepo, два развёртываемых приложения Spring Boot поверх общей PostgreSQL и
RabbitMQ:

| Компонент     | Роль |
|---------------|------|
| `job-api`     | REST API: поиск, подписки, компании, отклики, заметки, отчёты об ошибках. Владеет схемой БД и применяет миграции Flyway. |
| `job-worker`  | Фоновый обработчик: планирование (под leader-lock), публикатор transactional outbox, обработчики заданий, обнаружение, сбор, ревизии, дайджест. |

Доставка фоновых заданий — через **RabbitMQ** с самого начала (ADR-1): события
пишутся в таблицу `outbox_event` в одной транзакции с изменением данных, а
публикатор в `job-worker` отправляет их в брокер с publisher confirms
(ADR-12). Общая БД используется обоими приложениями; изменения схемы
согласуются по схеме expand → migrate → contract (ADR-2). На этапе каркаса схему
ведёт `job-api` (Flyway включён), `job-worker` миграции не применяет.

## Стек

| Область   | Выбор |
|-----------|-------|
| Язык      | Java 21 (LTS) |
| Каркас    | Spring Boot 4.1.1 |
| Данные    | PostgreSQL, Spring Data JPA, Flyway |
| Очередь   | RabbitMQ, Spring AMQP |
| Сборка    | Maven (многомодульный), образы — `spring-boot:build-image` (buildpacks) |
| Тесты     | JUnit 5, Testcontainers (реальные PostgreSQL и RabbitMQ) |
| CI        | GitHub Actions (компиляция + тесты) |

Требования Spring Boot 4.1: Java 17–26, Maven 3.6.3+, Spring Framework 7.0.9+.

## Структура

```
roleorienta/
├── pom.xml                  # корневой aggregator + версии из spring-boot-starter-parent
├── job-api/                 # REST API (Spring Boot)
│   └── src/main/resources/db/migration/  # миграции Flyway (владелец схемы)
│        ├── V1__baseline.sql
│        └── V2__outbox.sql  # таблица transactional outbox
├── job-worker/              # фоновый обработчик (Spring Boot + AMQP)
├── docker-compose.yml       # профиль local-pilot
├── infra/source-stub/       # маппинги заглушки источника (WireMock)
├── .github/workflows/ci.yml # базовый CI
└── .env.example             # локальные значения для docker compose
```

## Сборка и тесты

Требуется JDK 21 и Maven 3.6.3+ (или обёртка при её добавлении).

```bash
# компиляция + тесты (тесты поднимают PostgreSQL и RabbitMQ через Testcontainers — нужен Docker)
mvn verify

# только компиляция и упаковка без тестов
mvn -DskipTests package
```

## Запуск локально (профиль local-pilot)

Профиль поднимает API, worker, PostgreSQL, RabbitMQ, объектное хранилище (MinIO)
и заглушку источника (WireMock).

```bash
# 1) собрать контейнерные образы приложений
mvn spring-boot:build-image

# 2) (необязательно) свои локальные значения
cp .env.example .env

# 3) поднять окружение
docker compose up
```

Порты по умолчанию:

| Сервис            | Адрес |
|-------------------|-------|
| job-api           | http://localhost:8080 (health: `/actuator/health`) |
| job-worker        | http://localhost:8081 (health: `/actuator/health`) |
| PostgreSQL        | localhost:5432 |
| RabbitMQ          | localhost:5672 (консоль: http://localhost:15672) |
| MinIO             | http://localhost:9000 (консоль: http://localhost:9001) |
| source-stub       | http://localhost:8089 |

Конфигурация и креды передаются через переменные окружения (в образ не
зашиты). Значения в `.env.example` — только для локальной разработки.

## Что НЕ входит в текущий каркас

Модель данных (§4 техдока), адаптеры источников и обнаружение (§5–6), REST-контракты
(§7), безопасность/сессии (§9), OpenAPI, SPA-фронтенд, KEDA/Kubernetes/наблюдаемость
(Этап 3+). Публикатор outbox и обработчики заданий (SKIP LOCKED, publisher
confirms, DLQ, идемпотентность) — ближайшая задача реализации поверх этого
каркаса. Всё добавляется отдельными задачами.
