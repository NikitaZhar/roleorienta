# Текущее состояние roleorienta

Короткий документ «как устроено сейчас» (регламент сессии §7). Читается в начале каждой
сессии. История решений — в [project-notes](project-notes.md), правила — в
[рабочем контракте](working-contract.md) и [регламенте сессии](session-protocol.md).

**Обновлено:** 2026-09-24, после §79.
**Срезов с последнего аудита: 3** (последний аудит — §76).

## Назначение и границы

Пилот (Этап 1): приложение само находит работодателей с карьерными лентами на ATS (сейчас
Workday через индекс Common Crawl), собирает вакансии целевого рынка (Словакия, Австрия),
нормализует их и отдаёт ленту через REST. Проверяемая гипотеза — автообнаружение
работодателей и «скрытых» вакансий (план `roleorienta-plan-korrektirovka.md`, приоритет A).
SPA, покрытие площадками, снимки, дайджест/email — ещё не построены.

## Стек и запуск

Java 21, Spring Boot 4.1.1, PostgreSQL + Flyway (миграции V1–V27, ведёт `job-api`), RabbitMQ
(transactional outbox, publisher confirms, DLQ), Maven multi-module, Testcontainers в тестах,
CI — GitHub Actions `mvn -B -ntp verify`. Локально: `./scripts/dev-up.sh` (Postgres, RabbitMQ
в Docker), `./scripts/run-api.sh` (:8080), `./scripts/run-worker.sh` (:8081).

## Модули

- **core** — доменные сущности и перечисления (`JobPosting`, `Source`, `Provider`, `Company`,
  `EmployerCandidate` + `EmployerCandidateState`, `CrawlRun/CrawlTask`, `PostingRevision`,
  `SalaryPeriod/Basis`, `SeniorityLevel`, `WorkModality` …).
- **job-api** — REST: `posting` (лента, карточка), `auth`, `saved`, `subscription`,
  `notification`, `application` (отклики/собеседования), `report`, `discovery` (админ-очередь
  кандидатов). Владеет схемой БД.
- **job-worker** — фоновая работа: `scheduling` (планировщик источников под leader-lock),
  `outbox`/`messaging`/`idempotency` (доставка заданий), `discovery` (обнаружение),
  `collect` (сбор и обогащение), `normalize`, `extract`, `adapters` (Greenhouse, Workday),
  `http` (единственный HTTP-клиент), `digest` (сопоставление подписок), `lock`, `xml`.

## Путь данных

1. **Обнаружение.** `CcHarvestScheduler` (включён, §72) читает страницу индекса Common Crawl
   `*.myworkdayjobs.com` → доски в `harvested_board`; новая страница — только когда очередь
   `NEW` меньше `max-fan-out` (§73). `BoardFanOut` ставит `DISCOVER_EMPLOYER` через outbox.
2. **Гейт.** `DiscoverEmployerJobHandler`: читает первую страницу ленты; рынок — по фасету
   стран, иначе фасету локаций, иначе локациям вакансий первой страницы (§56–§59, §73).
   Перед авто-подключением — принадлежность доски (A2, §79): `SourceAdapter.boardProfile`
   (Workday: тенант + `og:description` страницы доски) сверяет `BoardOwnershipProperties`;
   нет описания, признак агентства, тенант не назван — `PENDING`.
   Итог кандидата: `CONFIRMED` (HIGH → `EmployerSourceRegistrar` заводит `Source` ACTIVE),
   `OUT_OF_MARKET`, `UNREACHABLE` (403/404/410/422, §74), `PENDING` (ручная проверка).
3. **Сбор.** Планировщик (`DiscoverPageEnqueuer`) → `DISCOVER_PAGE` (`DiscoverPageJobHandler`: лента только рынка —
   фильтр Workday `appliedFacets`, все публикации сохраняются) → `FETCH_POSTING` только для
   ниши (`NicheFilterProperties`) и в пределах дневного бюджета на источник (`PostingIntake`, §63).
4. **Обогащение.** `PostingEnricher`: `LocationNormalizer` (город/страна/формат, доп. локации),
   `SalaryNormalizer.resolve` (структурная зарплата, иначе `SalaryTextExtractor` по тексту, §66),
   `ExperienceExtractor` (уровень — только из заголовка, годы — из описания, §70), языки и
   навыки (`PostingRequirementWriter`), история изменений (`PostingRevisionRecorder`).
5. **Лента.** `GET /api/v1/postings`: фильтры `PostingFilter` (формат, уровень, страна с доп.
   локациями, `minSalary` с «от X», `postedFrom`), порядок `FeedPaging.sort` = `ID` | `POSTED`
   (keyset «дата + id» — `PostedKeyset`, §70); карточка `GET /api/v1/postings/{id}`. JSON сгруппирован
   (§77): строка — `{head, facts, viewer}`, карточка — `{head, facts, description, requirements}`;
   `facts` = `{location, salary, experience, timeline}`.

## Соглашения кода (кратко; полностью — контракт §3)

- ≤5 параметров у методов/конструкторов и ≤5 полей у записей, **включая DTO** — группировать во
  вложенные записи (`PostingFilter`, `FeedPaging`, `PostingDtos.Facts`, `*Properties`) или выносить
  компонент. Проверяет Checkstyle (`config/checkstyle/checkstyle.xml`, фаза validate, §78).
- Настройки — `@ConfigurationProperties`-записи с `@DefaultValue`, регистрируются в
  `JobWorkerApplication`; значения и комментарии — в `application.yml`.
- Репозитории — только запросы; логика (курсоры, преобразования) — в сервисах и записях-значениях.
- Исходящие запросы воркера — только `SourceHttpClient` (SSRF-защита, темп на домен,
  `Retry-After`, потолок тела 5 МБ); XML — только `SafeXml`.
- Неизвестное не угадывается: `UNKNOWN`/`null` вместо догадки (зарплата, уровень, формат).
- JPQL: nullable-параметр даты — через вычисленный в Java признак (§68).
- Тесты: модульные на заглушках + интеграционные на Testcontainers; `JobWorkerApplicationTests`
  выключает обход Common Crawl.

## Стенды (`target/*.sh`, вне git)

`stand-cc.sh` — обнаружение Common Crawl (15 мин, отчёт `stand-cc.txt`); `stand-salary.sh` —
перезапрос деталей 5 досок SK/AT без ограничения ниши (`stand-salary.txt`); `stand-feed.sh` —
лента через REST (`stand-feed.txt`). Скрипты сами запускают и останавливают воркер; нужны
`dev-up.sh`, для REST — `run-api.sh`.

## Статус плана (приоритеты — `roleorienta-plan-korrektirovka.md`)

- Сделано: A1 (вход Common Crawl включён, §72–§74), A2 для Workday (§79), B1 (темп, §57), B2 (потолок ответа, §71);
  качество данных Workday (зарплата, локации, уровень, дата — §65–§70); лента с фильтрами и
  сортировкой по дате.
- Checkstyle в сборке (§78); ArchUnit — позже.
- Далее: A3 (имя компании и дедуп).
- Открыто: B3–B5 (безопасность входа, потолок попыток outbox), A5–A7 (покрытие, снимки,
  дайджест).
