# roleorienta

Приложение для поиска, мониторинга и анализа вакансий. Собирает вакансии не
только с агрегаторов, но и напрямую с карьерных страниц компаний, ведёт историю
изменений, строит технологический профиль работодателя и сопровождает кандидата
от находки вакансии до результатов собеседования.

Документация продукта и правил разработки — в [`docs/`](docs/): [бизнес-ТЗ](docs/business-requirements.md), [технический документ](docs/technical-design.md), [рабочий контракт](docs/working-contract.md).

> Статус репозитория: **Этап 1 (пилот), в работе.** Построены: магистраль
> доставки (RabbitMQ + transactional outbox, publisher confirms, DLQ, leader-lock,
> идемпотентность), обнаружение работодателей (гейт уверенности, seed-гарвест;
> автоматический вход по индексу Common Crawl для Workday и гейт целевого рынка —
> построены, вход по умолчанию выключен), темп запросов к источникам (интервал на
> домен, `Retry-After`), сбор и
> нормализация (адаптеры Greenhouse; Workday — список/деталь на заглушке), извлечение требований, подписки, уведомления,
> отклики/заметки/собеседования, отчёты об ошибках. В работе (ядро ценности):
> сбор подключённых досок с фильтром по рынку и бюджетом деталей, затем включение
> автоматического входа; проверка принадлежности доски работодателю, покрытие
> (`CoverageAssessment`), снимки (`SourceSnapshot`), дайджест + email, SPA.
> Охват — по вендор-адаптерам ATS, **включая энтерпрайз** (Workday, SAP
> SuccessFactors, SmartRecruiters), а не только SMB (см. `docs/technical-design.md`,
> ADR-17/ADR-18).

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

## Локальная разработка (рекомендуемый способ)

Инфраструктура (база, брокер и прочее) поднимается в Docker, а приложения
запускаются из IDE или терминала. PostgreSQL контейнера опубликован на порт
**5433**, чтобы не конфликтовать с локально установленной PostgreSQL на 5432.

Требуется: запущенный Docker Desktop и JDK 21.

```bash
./scripts/dev-up.sh       # поднять инфраструктуру (postgres:5433, rabbitmq, minio, source-stub)
./scripts/run-api.sh      # запустить job-api  -> http://localhost:8080/actuator/health
# ./scripts/run-worker.sh # при необходимости — job-worker -> http://localhost:8081
# ... работа; Ctrl+C останавливает приложение ...
./scripts/dev-down.sh     # в конце — остановить инфраструктуру (данные в томах сохраняются)
```

Запуск из Eclipse/STS: сначала `./scripts/dev-up.sh`, затем правый клик на
`JobApiApplication` → Run As → Spring Boot App. Приложение по умолчанию
подключается к `localhost:5433`.

## Запуск всего в Docker (профиль local-pilot)

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
| PostgreSQL        | localhost:5433 (в контейнере 5432) |
| RabbitMQ          | localhost:5672 (консоль: http://localhost:15672) |
| MinIO             | http://localhost:9000 (консоль: http://localhost:9001) |
| source-stub       | http://localhost:8089 |

Конфигурация и креды передаются через переменные окружения (в образ не
зашиты). Значения в `.env.example` — только для локальной разработки.

## Что пока НЕ реализовано (следующие задачи)

Ядро ценности Этапа 1: автоматический вход обнаружения по вендорным шаблонам
(Common Crawl / Certificate Transparency), проверка принадлежности ленты
работодателю в гейте уверенности, дедуп работодателей между входами;
дополнительные вендор-адаптеры (Workday, SAP SuccessFactors, SmartRecruiters);
`CoverageAssessment` и признак «скрытой» в ленте; `SourceSnapshot` (S3); дайджест
изменений + email; SPA-фронтенд с trust-панелью.

Инфраструктурные дефекты к устранению (по разбору 2026-09-22): бюджет/rate-limit
источников, потолок тела ответа, потолок попыток outbox, ротация сессии при
логине, rate-limit login/register — см. заметку о корректировке плана в `docs/`.

Этап 3+: KEDA/Kubernetes, полная наблюдаемость (Prometheus/Grafana/Tempo),
GitOps. Всё добавляется отдельными задачами.
