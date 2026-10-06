# Следующий шаг

Перезаписывается в конце каждого среза. Раздел «Долг» переносится без изменений, пока пункт не
закрыт срезом.

**Последний срез:** §57 — адаптер SAP SuccessFactors; кадровая ссылка и на кадровый хост другого домена.

**Состояние на 2026-10-06:** §56 в `main`, CI зелёный. §57 — в рабочем дереве, ждёт сборки и коммита владельца.

**ПЕРВЫЙ ШАГ СЛЕДУЮЩЕЙ СЕССИИ — отложенные проверки на стенде (до любого нового среза).** 2026-10-06 в 14:2x
с 9 471 найденного сайта снята отметка проверки (`FORMAT_UNSUPPORTED`, `NO_CAREER_PAGE`), чтобы перепроверить их
правилами §57; перепроверка начнётся только с суточного тика 2026-10-07 (цепочка дня 2026-10-06 уже прошла) и
займёт ~12–16 часов (50 сайтов за задание). Выполнить по очереди, разобрать вывод, записать итог в стенограмму
(дополнение к §57) и только затем продолжать план (§58).

1. Перепроверка идёт и не обрывается (исправление ключа продолжения, §57):
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT count(*) FILTER (WHERE checked_at IS NULL) AS waiting, count(*) FILTER (WHERE checked_at > current_date - 1) AS checked_since_yesterday FROM company_site WHERE status = 'FOUND'"
```
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT state, count(*) FROM task WHERE type = 'CAREER_SCAN' AND created_at > current_date - 1 GROUP BY 1"
```
Ждём: `waiting` близко к 0, `FAILED` нет.

2. Итог перепроверки по причинам (сравнить с замером §55: `NO_CAREER_PAGE` 72 %, `FORMAT_UNSUPPORTED` 20 %):
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT check_result, count(*) FROM company_site WHERE checked_at > current_date - 1 GROUP BY 1 ORDER BY 2 DESC"
```

3. Источники SuccessFactors и их вакансии (§57; ждём ZF, Kaufland, Lidl, VÚB, Schaeffler, Gestamp, Vaillant — те, чьи
сайты есть у ядра):
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT s.board, count(p.id) FROM source s LEFT JOIN job_posting p ON p.source_id = s.id WHERE s.provider = 'successfactors' GROUP BY 1"
```

4. Swiss Re (проверка владельца): `swissre.com` отвечает программе 403 → пробный адрес `careers.swissre.com`
(SuccessFactors, подпись `rmkcdn` есть). Ждём `SOURCE_FOUND` и источник `successfactors | careers.swissre.com`:
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT c.registration_number, s.check_result, s.career_url, s.checked_at FROM company c JOIN company_site s ON s.company_id = c.id WHERE c.name ILIKE '%swiss re%' ORDER BY 1"
```
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT c.name, src.provider, src.board, count(p.id) AS postings FROM company c JOIN company_source cs ON cs.company_id = c.id JOIN source src ON src.id = cs.source_id LEFT JOIN job_posting p ON p.source_id = src.id WHERE c.name ILIKE '%swiss re%' GROUP BY 1, 2, 3"
```
Если `careers.swissre.com` не подключился — разобрать (заголовок страницы без кадрового слова? поиск по месту?).

5. Сайты с портала по версии 3 (§50) и по названию (§51) за сутки:
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "SELECT source, proof, status, count(*), count(start_url) FROM company_site WHERE found_at > now() - interval '1 day' GROUP BY 1, 2, 3 ORDER BY 1, 2, 3"
```

**Решение владельца (2026-10-06): закрыть чтение кадровых страниц максимально до SPA.** Порядок:

| Срез | Формат | Кого закрывает (замеры §48, §55) |
|---|---|---|
| §57 | Сделано: SAP SuccessFactors (Career Site Builder) | Lidl, Kaufland, VÚB, ZF (2), Schaeffler, Vaillant, Gestamp |
| §58 | Nalgoo | BILLA, Foxconn, SSE, Markíza |
| §59 | Phenom | Allianz, DHL |
| §60 | Oracle Recruiting, topjobs.sk, Teamio | Slovnaft, Slovenská sporiteľňa, McDonald's, dm |
| §61 | Своя страница со ссылками на вакансии | Tesco, IKEA, Porsche, Tatra banka, Stellantis, Tate & Lyle |
| §62 | Поиск кадровой страницы в ядре | пропуски (7 из 200) и ложные `FORMAT_UNSUPPORTED` (14 из 200) |

Не закрывается: своя страница без ссылок на вакансии (вакансии текстом на странице, у каждого сайта своя
разметка) — итог `FORMAT_UNSUPPORTED`. Образцы ответов — `docs/samples/*.html` (сняты 2026-10-06). Срезов с аудита
после §56 — 2; аудит — после 10-го среза (§64).

Затем — SPA (подэтап 1.5): вход, условия, список, сведения, отмеченные — через API §32, §56.

**Порядок срезов — поиск сайта (по шагам алгоритма версии 3; технический документ §5.1):**

| Срез | Шаг алгоритма | Что |
|---|---|---|
| §50 | 2 | Сделано: портал по версии 3, общий компонент проверки сайта `SiteVerifier` |
| §51 | 3, 4 | Сделано: адрес по названию, своя очередь, `NO_SUCH_HOST` |
| — | 5 | Common Crawl — есть в коде (§25), не меняется |
| §52 | 1–5 | Сделано: основной сайт — найденный сайт самого раннего шага |

profesia.sk напрямую не нужна: её объявления приходят через портал (§45).

Затем: 1.5 — список, сведения, отметки (API; сценарии 13, 9) и SPA; 1.8 — одна вакансия из двух
источников одной компании (сценарий 5); 1.9 — ручное добавление (сценарий 12); 1.10 — показатели ядра.

## Долг

1. **Хранилище снимков — заменить архивный образ MinIO** (§18). `bitnamilegacy/minio` не
   обновляется и может исчезнуть из реестра; тогда не поднимутся CI и `docker compose`. Замена —
   поддерживаемое S3-совместимое хранилище (кандидаты: SeaweedFS, Garage, RustFS) в
   `docker-compose.yml` и `TestcontainersConfiguration`; поправить «локально MinIO» в техническом
   документе и README. Код (AWS SDK) не меняется. Крайний срок — до подэтапа развёртывания
   (`ops-check`, технический документ §12) или сразу, если образ пропадёт.
2. **Срок хранения снимков и очистка сирот** (технический документ §16.10; §23). Снимки хранятся
   бессрочно, объекты без ссылки в БД не удаляются. Срока в §17 нет — задаёт владелец. Крайний
   срок — до эксплуатационной проверки (1.10).

3. **Вынесено из 1.5 (решение владельца, §31):** экран показателей — в 1.10 (нужна
   `CoreMetricDaily`); ручное добавление компании — 1.9; причина пустоты «обработано X из Y» — 1.10
   (показатели, §42 стенограммы); `ETag`/`If-None-Match` на списке и сведениях и описание API в
   OpenAPI (springdoc) — после ядра (технический документ §8). Крайний срок — до критерия
   готовности этапа 1 (технический документ §15).

**Правила работы:** срез — одна задача, без полировки и расширения; сборку и коммит делает
владелец; счётчик до аудита — в шапке `docs/project-notes.md`.
