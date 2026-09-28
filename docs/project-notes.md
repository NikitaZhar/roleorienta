---
Документ: Стенограмма проекта roleorienta
Дата: 2026-09-28
Статус: outbox → RabbitMQ с подтверждением и проверкой маршрутизации, топология с DLQ (§2).
Прежний статус: каркас нового репозитория (§1).
Срезов с последнего аудита: 2
---

# Стенограмма проекта

Живой документ (рабочий контракт §0.1, §0.4): что и зачем сделано и простое объяснение каждой
неочевидной конструкции со ссылкой на официальную документацию. Записи `§NN` только
дописываются.

## §1 — Каркас нового репозитория (подэтап 1.1)

**Зачем.** Технический документ §15, подэтап 1.1: основа, на которой строится магистраль доставки
заданий. Бизнес-логики нет.

**Что сделано.**
- `pom.xml` — корневой модуль сборки (packaging `pom`): общие версии и правила для модулей
  `job-api` и `job-worker`. Родитель `spring-boot-starter-parent` 4.1.1 задаёт согласованные
  версии зависимостей Spring и плагинов. https://docs.spring.io/spring-boot/maven-plugin/using.html
- `testcontainers-bom` — список согласованных версий библиотек Testcontainers; импортируется в
  `dependencyManagement`, поэтому в модулях версии не пишутся. Spring Boot 4 этими версиями не
  управляет. https://java.testcontainers.org/
- `maven-checkstyle-plugin` на фазе `validate` с `config/checkstyle/checkstyle.xml` — правила
  контракта (≤5 параметров и полей записей, имена ≥2 символов, JavaDoc у публичных типов)
  проверяются каждой сборкой. https://checkstyle.org/checks.html
- `job-api`: `spring-boot-starter-web` (REST), `actuator` (health), `data-jpa`,
  `spring-boot-starter-flyway` + `flyway-database-postgresql` (миграции схемы; в Spring Boot 4
  автоконфигурация Flyway — отдельный стартер), драйвер PostgreSQL. В `application.yml`:
  `ddl-auto: validate` — Hibernate только сверяет сущности со схемой, создаёт её Flyway;
  `open-in-view: false` — соединение с БД не удерживается на всё время HTTP-запроса;
  `problemdetails.enabled` — ошибки в формате RFC 9457.
- `job-worker`: web (только для Actuator), `actuator`, `data-jpa`, `spring-boot-starter-amqp`
  (RabbitMQ), драйвер PostgreSQL; Flyway не подключён — схемой владеет job-api. Настройки
  RabbitMQ: `publisher-confirm-type: correlated` — брокер подтверждает приём каждого сообщения;
  `publisher-returns: true` + `template.mandatory: true` — сообщение без подходящей очереди
  брокер возвращает, а не теряет. https://docs.spring.io/spring-amqp/reference/amqp/template.html
- Тесты `contextLoads` в обоих модулях на реальных контейнерах. `@ServiceConnection` —
  Spring Boot сам берёт адрес и учётные данные из контейнера Testcontainers и настраивает
  подключение. https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- `docker-compose.yml` — PostgreSQL 16 (:5433), RabbitMQ 4 с консолью (:5672, :15672), MinIO
  (:9000, :9001); `healthcheck` — Docker сам проверяет готовность сервиса.
- `.github/workflows/ci.yml` — GitHub Actions: JDK 21 и `mvn -B -ntp verify` на каждый push в
  `main` и pull request.
- Документы: README и эта стенограмма.

**Тесты.** `JobApiApplicationTests`, `JobWorkerApplicationTests` — контекст поднимается.

**README** — написан для нового проекта (контракт §0.2).

**Не вошло.** Outbox, leader-lock, задания с повторами, HTTP-клиент, ArchUnit, приёмочные тесты —
следующие срезы подэтапа 1.1.

## §2 — Outbox и публикация в RabbitMQ (подэтап 1.1)

**Зачем.** Магистраль доставки заданий (технический документ §6, §9, §16.2): событие пишется в
БД в одной транзакции с изменением данных и гарантированно доходит до RabbitMQ.

**Что сделано.**
- `V1__outbox.sql` (job-api) — таблица `outbox_event`; частичный индекс по неопубликованным.
- `RabbitTopology` — обменник `roleorienta.jobs`, рабочая очередь, dead-letter обменник и DLQ,
  всё durable. Бины `DirectExchange`/`Queue`/`Binding` Spring AMQP сам объявляет в брокере.
  https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html
- `OutboxRepository` — SQL к outbox. `FOR UPDATE SKIP LOCKED` блокирует выбранные строки до конца
  транзакции и пропускает занятые другой репликой — каждое событие отправляет один публикатор.
- `OutboxPublisher` — пачка в одной транзакции: захват → отправка с `mandatory` → ожидание
  подтверждений (`waitForConfirmsOrDie`) → отметка `published_at`. Вернувшиеся как
  немаршрутизируемые (`basic.return`, собираются `ReturnsCallback`) не отмечаются, счётчик попыток
  +1. Нет подтверждений — откат, повтор на следующем тике; возможный повторный приход сообщения
  гасит идемпотентный потребитель по `messageId`.
  https://docs.spring.io/spring-amqp/reference/amqp/template.html#template-confirms
- `OutboxPublisherTick` — `@Scheduled` тик (пауза `app.outbox.poll-interval-ms`), выключается
  `app.outbox.tick-enabled=false`; `@ConditionalOnProperty` не создаёт бин при выключении.
- `OutboxProperties` — запись настроек `app.outbox.*` (`@ConfigurationProperties`,
  `@DefaultValue`); регистрируется `@ConfigurationPropertiesScan` в `JobWorkerApplication`;
  там же `@EnableScheduling` — включает `@Scheduled`.
- Тесты worker создают схему теми же миграциями job-api: Flyway подключён к worker только в
  тестах, `spring.flyway.locations=filesystem:../job-api/...` в профиле `test`.

**Тесты.** `OutboxPublisherTests`: событие доставлено в очередь с id в `messageId` и отмечено;
без привязки очереди событие не отмечено, попытка засчитана, после восстановления привязки
доставлено.

**README** — строка статуса.

**Не вошло.** Потребитель заданий, таблица заданий с повторами, leader-lock — следующий срез.
