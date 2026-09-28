---
Документ: Стенограмма проекта roleorienta
Дата: 2026-09-28
Статус: robots.txt (§13).
Прежний статус: бюджет на хост (§12); аудит (§11); текст вакансий Workday (§10); Workday, список страницами (§9); Personio и безопасный XML (§8); закрытие вакансий (§7); чтение Greenhouse (§6); HTTP-клиент (§5); outbox только для заданий (§4); задания с повтором (§3); outbox → RabbitMQ (§2); каркас (§1).
Срезов с последнего аудита: 2
Следующий шаг: docs/next-step.md
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

## §3 — Задания с повтором через БД (подэтап 1.1)

**Зачем.** Технический документ §6, §9, §16.3, §16.12: временный отказ повторяется с растущим
интервалом, прогресс переживает перезапуск, ошибка кода не теряется.

**Что сделано.**
- `V2__task.sql` — таблица `task`: тип, ключ идемпотентности (UNIQUE), параметры, состояние
  QUEUED → RUNNING → DONE / WAITING / FAILED, попытки, срок повтора, аренда. `outbox_event.task_id`.
- `TaskService.enqueue` — задание и событие outbox одной транзакцией; повтор с тем же ключом
  ничего не делает (`ON CONFLICT DO NOTHING`).
- `OutboxPublisher` кладёт id задания в заголовок `taskId`; `TaskListener` (`@RabbitListener`)
  передаёт его `TaskExecutor`.
- `TaskExecutor` — захват `UPDATE … WHERE state = 'QUEUED'` (повторная доставка задание не
  захватит — идемпотентность), обработчик вне транзакции, запись результата. Результат —
  `TaskOutcome` (sealed: `Done`, `Retry`, `Failed`; `switch` проверяется компилятором на полноту).
  Исключение обработчика → FAILED и сообщение в DLQ (`default-requeue-rejected: false`).
- `RetryPolicy` — 5 мин × 2 до 6 ч + до 10% случайной надбавки, не меньше срока источника
  (`Retry-After`); 8 попыток — FAILED. Значения — `app.task.*`.
- `TaskRequeueTick` — раз в 10 с под leader-lock снова ставит в очередь WAITING с наступившим
  сроком и RUNNING с истёкшей арендой (процесс упал).
- `PostgresLeaderLock` — `pg_try_advisory_xact_lock`: не ждёт, освобождается с транзакцией;
  реестр ключей — в JavaDoc класса. https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS
- `TaskHandler` — контракт обработчика; бины собираются через `ObjectProvider` (обработчиков
  может не быть). Обработчики типов появятся с функциями (подэтап 1.2).
- В профиле `test` потребитель не стартует сам (`auto-startup: false`); тесты, которым он нужен,
  запускают его через `RabbitListenerEndpointRegistry`.

**Тесты.** `TaskFlowTests`: постановка → outbox → брокер → потребитель → DONE, повторная
постановка не создаёт задание; повторное выполнение не вызывает обработчик; временный отказ →
WAITING → перепостановка по сроку; последняя попытка → FAILED; истёкшая аренда → QUEUED;
исключение → FAILED и DLQ; неизвестный тип → FAILED. `RetryPolicyTests`: удвоение, потолок,
надбавка, `Retry-After`.

**README** — строка статуса.

**Не вошло.** Выделенный HTTP-клиент, безопасный XML, ArchUnit, каркас приёмочных тестов —
следующие срезы подэтапа 1.1.

## §4 — Чистка: outbox только для заданий (подэтап 1.1)

**Зачем.** Убрать код без назначения (контракт §3.10): outbox ставит в очередь только задания,
потребитель читает id задания из заголовка.

**Что сделано.**
- `V3__outbox_task_only.sql` — удалены `event_type` и `payload`, `task_id` обязателен.
- `OutboxEvent` — `(id, taskId)`; сообщение без тела: `messageId` — id события, заголовок
  `taskId`; заголовок `eventType` убран.
- job-api: убрана настройка `spring.mvc.problemdetails` — контроллеров нет, вернётся с API
  (подэтап 1.5).
- Доступ к данным: доменные сущности — Spring Data JPA (технический документ §3); механика
  очереди, захвата и выдачи — `JdbcTemplate` с явным SQL (`SKIP LOCKED`, условный `UPDATE …
  RETURNING`, `ON CONFLICT`), в одной транзакции с JPA.

**Тесты.** `OutboxPublisherTests` — событие ссылается на задание; проверяются `messageId` и
заголовок `taskId`.

**README** — не менялся.

## §5 — Выделенный HTTP-клиент (подэтап 1.1)

**Зачем.** Технический документ §10: все внешние запросы — через один клиент с защитой от SSRF,
таймаутами и потолками; отказы источника разобраны для повторов (бизнес-описание §4.6).

**Что сделано.**
- Зависимость Apache HttpClient 5 (версия — Spring Boot). Встроенный в JDK клиент сам заново
  определяет адрес хоста и не даёт проверить фактический адрес соединения; в Apache HttpClient
  определение адреса подменяется (`SystemDefaultDnsResolver`). Строка §3 технического документа
  исправлена. https://hc.apache.org/httpcomponents-client-5.4.x/
- `AddressPolicy` — запрещённые адреса: loopback, частные сети, link-local (облачный metadata),
  multicast, 0.0.0.0/8, 100.64/10, fc00::/7, IPv4 в записи IPv6.
- `ExternalHttpClient` — только http/https; каждый адрес, в который разрешилось имя, проверяется
  перед соединением, в том числе на редиректах (защита от DNS-rebinding); таймауты, не больше
  `max-redirects` переходов, циклы запрещены, тело — не больше `max-body-bytes`; встроенные
  повторы библиотеки выключены. `BlockedAddressException` наследует `UnknownHostException` —
  так отказ проходит через клиент как ошибка ввода-вывода.
- `HttpResult` (sealed): `Success`; `TemporaryFailure` — таймаут, обрыв, 5xx, 429 с `Retry-After`
  (секунды или дата); `PermanentFailure` — `ACCESS_DENIED` (401/403), `NOT_FOUND` (404/410),
  `BLOCKED`, `TOO_LARGE`, `CLIENT_ERROR`. Обработчики заданий переводят его в `TaskOutcome`.
- Настройки `app.http.*`; `allow-private-addresses: true` — только для локальных заглушек.

**Тесты.** `ExternalHttpClientTests` на встроенном в JDK HTTP-сервере (без новой тестовой
зависимости): успех с кодировкой, POST JSON, 503, 429 с `Retry-After` секундами и датой,
403/404/400, большой ответ, цикл редиректов, таймаут, блокировка 127.0.0.1, localhost,
169.254.169.254 и схемы ftp. `AddressPolicyTests` — внутренние и публичные адреса.

**README** — стек и строка статуса.

**Не вошло.** Бюджет запросов на хост, robots.txt, безопасный XML — вместе с первым чтением
источника и адаптером Personio (подэтап 1.2).

## §6 — Чтение источника Greenhouse (подэтап 1.2)

**Зачем.** Первый рабочий путь ядра «источник → вакансии» (технический документ §5, §6).

**Что сделано.**
- `V4__source_posting_vacancy.sql` — `source` (провайдер + доска), `vacancy` (состояние, позиция,
  ссылка, даты), `job_posting` (уникальна в паре «источник + внешний id»).
- JPA-сущности `Source`, `Vacancy`, `JobPosting` и репозитории Spring Data
  (`JpaRepository`: готовые операции, запрос по имени метода
  `findBySourceIdAndExternalId`). https://docs.spring.io/spring-data/jpa/reference/
- `SourceAdapter` — контракт адаптера провайдера; `SourceReadResult` — `Read` (публикации) или
  `Unavailable` (отказ `HttpResult`).
- `GreenhouseAdapter` — `GET /v1/boards/{board}/jobs?content=true` через `ExternalHttpClient`,
  разбор JSON (Jackson 2, версия — Spring Boot); не-JSON в ответе — временный отказ.
- `PostingRecorder` — новая публикация создаёт вакансию; известная обновляет сведения и
  подтверждает наличие; всё чтение — одна транзакция.
- `ReadSourceHandler` — задание `READ_SOURCE`: адаптер по провайдеру; временный отказ → повтор
  (с `Retry-After`), постоянный → неудача; сохранённые сведения при отказе не меняются.
- `ReadSourcePlanner` + `ReadSourceTick` (leader-lock 1002) — раз в сутки ставят чтение каждого
  источника, ключ `read:<источник>:<дата>`.
- `ClockConfig` — часы бином, чтобы тесты подменяли время.

**Тесты.** `GreenhouseAdapterTests` — разбор, 503, не-JSON. `ReadSourceFlowTests` — первое
чтение создаёт вакансии, повторная постановка в тот же день ничего не добавляет, второе чтение
обновляет ту же вакансию, 503 → задание ждёт повтора, данные прежние.

**README** — не менялся.

**Не вошло.** Полнота обхода, счётчик отсутствия, состояния и закрытие — следующий срез.

## §7 — Закрытие вакансий и состояния (подэтап 1.2)

**Что сделано.**
- `V5__vacancy_closure.sql` — у публикации `confirmed`, `missing_complete_reads`, `closed_at`;
  у вакансии `closed_at`.
- `SourceReadResult.Read(postings, complete)` — адаптер сообщает, прочитан ли список полностью;
  Greenhouse отдаёт доску одним ответом — всегда `true`.
- `PostingRecorder`:
  - полное чтение — новые публикации создают вакансии, известные обновляются и подтверждаются,
    отсутствующие получают +1 к счётчику и закрываются на 3-м полном чтении подряд
    (`app.vacancy.close-after-missing-reads`);
  - неполное чтение — добавляются только новые публикации, сохранённые не меняются;
  - отказ источника — публикации теряют подтверждение, сведения и счётчики прежние.
- `Vacancy.refreshState` (бизнес-описание §4.3): все публикации закрыты — `CLOSED`; хотя бы одна
  подтверждена — `ACTIVE`; иначе — `NEEDS_RECHECK`. Появившаяся снова публикация открывает ту же
  вакансию.
- Публикации затронутых вакансий читаются одним запросом `findByVacancyIdIn` (без N+1).

**Тесты.** `ReadSourceFlowTests` — через задание и БД: закрытие на 3-м отсутствии, отказ →
повторная проверка, повторное появление → та же вакансия актуальна.

**README** — не менялся.

**Не вошло.** Скрытие неподтверждённой 30 дней вакансии — условие запроса выдачи (подэтап 1.5).

## §8 — Адаптер Personio и безопасный XML (подэтап 1.2)

**Что сделано.**
- `SafeXml` — единственный разборщик внешнего XML (технический документ §10): StAX из JDK
  (`newDefaultFactory`), DTD и внешние сущности выключены, потолок вложенности 32. Размер
  ограничен потолком тела ответа HTTP-клиента.
  https://owasp.org/www-community/vulnerabilities/XML_External_Entity_(XXE)_Processing
- `PersonioAdapter` — лента `https://<доска>/xml`, весь список одним ответом (`complete = true`).
  Доска — хост витрины `<компания>.jobs.personio.de` или `.jobs.personio.com`; другой хост не
  читается (отказ `BLOCKED`); поля `id`, `name`, `office`, тексты `jobDescription/value`; ссылка —
  `/job/<id>`. Корень не `workzag-jobs` или испорченный XML — временный отказ.
- `app.adapter.personio.base-url-template` — адрес витрины, `{board}` — хост витрины.

**Тесты.** `SafeXmlTests` — XXE и глубокая вложенность отвергаются. `PersonioAdapterTests` —
разбор ленты, витрина `.com`, чужой хост, HTML вместо ленты, 503.

**README** — не менялся.

## §9 — Адаптер Workday: список страницами (подэтап 1.2)

**Что сделано.**
- `WorkdayAdapter` — `POST https://<тенант>.<dc>.myworkdayjobs.com/wday/cxs/<тенант>/<сайт>/jobs`
  через `ExternalHttpClient.postJson`, страницы `offset/limit` по 20. Доска —
  `<тенант>.<dc>.myworkdayjobs.com/<сайт>`; другой хост — отказ `BLOCKED`.
- Внешний id и ссылка — `externalPath`; место — `locationsText`; `total` берётся с первой
  страницы (на следующих Workday его не отдаёт).
- Полнота: отказ на первой странице — источник недоступен; отказ на следующей, пустая страница
  раньше `total` или упор в потолок `app.adapter.workday.max-pages` — неполное чтение (§7:
  добавляются только новые публикации, ничего не закрывается).

**Тесты.** `WorkdayAdapterTests` — две страницы с `total` только на первой, отказ на второй
странице, отказ на первой, чужой хост.

**README** — не менялся.

**Не вошло.** Текст вакансии (деталь по `externalPath`) — следующий срез; фасеты стран — вместе с
проходом по стране.

## §10 — Текст вакансий Workday (подэтап 1.2)

**Что сделано.**
- `SourceAdapter.content(board, externalId)` — текст публикации отдельным запросом; по умолчанию
  `null` (Greenhouse и Personio отдают текст в списке).
- `WorkdayAdapter.content` — `GET <api>/<externalPath>`, поле `jobPostingInfo.jobDescription`;
  отказ — `null`.
- `ReadSourceHandler` — запрашивает текст только у публикаций без сохранённого текста и не больше
  `app.collect.max-content-requests-per-read` (200) за чтение; остальное — следующими чтениями.
  Задание остаётся ограниченным: Workday отдаёт текст за ~0,5 с (замер: 50 за 26 с), 200 запросов —
  около 2 минут, далеко от аренды задания (10 минут).
- `JobPosting.update` не стирает сохранённый текст, если чтение текста не принесло.
- `JobPostingRepository.findExternalIdsWithContent` — JPQL в `@Query`.
- Уборка: удалены неиспользуемые методы `JobPosting`/`Vacancy` и дублирующий `VacancyStateTests`.

- Журнал: итог чтения (`Source N read: X postings, complete=…`), итог запроса текстов
  (`requested/received`); неудачный запрос текста Workday — WARN с причиной.

**Тесты.** `WorkdayAdapterTests.readsContent`; `ReadSourceHandlerTests` (Mockito) — текст
запрошен только у публикации без сохранённого текста и в пределах потолка.

**README** — не менялся.

## §11 — Аудит §1–§10

**Область.** Весь код и документы: аудитов раньше не было.

**Исправлено.**
- README: статус отставал (подэтап 1.1, «бизнес-логики нет»), нарушение §0.2 контракта.
- `PersonioAdapter`: позиция без названия ломала бы всё чтение (`vacancy.title NOT NULL`) —
  теперь пропускается, как позиция без id.

**Проверено, нарушений нет.** Нет циклов между пакетами; SQL только в репозиториях; транзакции
короткие — внешние запросы вне транзакций; внешние вызовы с таймаутами, повторы ограничены
таблицей заданий; нет секретов в коде; нет N+1 в записи чтения; индексы под запросы (`task`,
`job_posting`); конструкторы ≤ 5 параметров.

**Отмечено, не менялось (минимальное вмешательство).**
- `WorkdayAdapter.read` — длинный метод (страницы, разбор, полнота); выносить при следующей
  правке адаптера.
- `ExternalHttpClient` берёт `Clock.systemUTC()`, а не бин `Clock`.
- `outbox_event.attempts` растёт, но нигде не читается.

**Расхождения с техническим документом (подэтап 1.2).**
1. robots.txt и бюджет на хост (§10) не сделаны, а сбор уже ходит на настоящие источники
   (Workday — до ~220 запросов за чтение). Это первое по важности.
2. `SourceSnapshot` (снимки в S3), `CrawlRun`, `VacancyRevision` — нет.
3. Адаптер schema.org `JobPosting` — нет.
4. Скрытие вакансии без подтверждения 30 дней — условие выдачи (1.5).
5. Правило ArchUnit «XML только через `SafeXml`» — ArchUnit не подключён.

## §12 — Бюджет на хост и User-Agent (подэтап 1.2)

**Что сделано.**
- `V6__host_budget.sql` — `host_budget(host, next_allowed_at)`: время следующего разрешённого
  запроса к хосту, общее для всех реплик (технический документ §16.11).
- `HostBudget` / `PostgresHostBudget` — резервирование места одним оператором
  `INSERT … ON CONFLICT DO UPDATE … RETURNING`: строка хоста блокируется на время оператора, две
  реплики не получат одно место; время — часы БД. Место дальше потолка ожидания не резервируется.
  https://www.postgresql.org/docs/16/sql-insert.html#SQL-ON-CONFLICT
- `ExternalHttpClient`: перед запросом резервирует место и ждёт его; очередь длиннее
  `host-max-wait` — временный отказ без запроса; `Retry-After` сдвигает очередь хоста
  (`backOff`); единый User-Agent. Часы — бин `Clock` (закрыто замечание аудита §11), один
  конструктор.
- `app.http.politeness.*` — User-Agent с контактом, промежуток 1 с, потолок ожидания 30 с.

**Тесты.** `PostgresHostBudgetTests` — очередь с промежутком и потолок, `Retry-After`, 8
одновременных резервирований получают разные места. `ExternalHttpClientTests` — User-Agent, отказ
без запроса при исчерпанном бюджете, `Retry-After` → `backOff`. Адаптерам клиент для заглушек даёт
`TestHttpClients`.

**README** — не менялся.

**Не вошло.** Редирект на другой хост бюджет не резервирует; robots.txt — §13.

## §13 — robots.txt (подэтап 1.2)

**Что сделано.**
- `RobotsRules` — разбор и сопоставление по RFC 9309: группа робота (токен из User-Agent, без
  учёта регистра) важнее `*`; самое длинное совпадение, при равенстве — `allow`; `*` и `$`.
  Сопоставление без регулярных выражений — чужой файл не вызовет экспоненциальный перебор.
  https://www.rfc-editor.org/rfc/rfc9309
- `ExternalHttpClient` сверяет каждый запрос с robots.txt его происхождения; robots.txt читается
  тем же клиентом (SSRF-защита, бюджет хоста) и кэшируется на сутки в памяти реплики. 2xx —
  правила; 4xx — разрешено всё; 5xx/сеть — запрос откладывается (временный отказ), в кэш не
  кладётся. Запрет — `USE_FORBIDDEN` без запроса; задание чтения — неудача.
- Проверено вручную: robots.txt Workday, Greenhouse разрешают наши пути; у Personio robots.txt нет
  (404).

**Тесты.** `RobotsRulesTests` — своя группа против `*`, самое длинное совпадение и ничья, `*` и
`$`, группировка строк `user-agent`. `ExternalHttpClientTests` — запрет без запроса и один
запрос robots.txt на происхождение; robots.txt 503 — запрос откладывается.

**README** — не менялся.

**Не вошло.** Правило `SourcePermission` (основание использования источника) — отдельно от
robots.txt, по техническому документу §10.
