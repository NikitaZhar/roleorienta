# Эталон для отработки ядра

План стабилизации ядра (`docs/next-step.md`; стенограмма §67, §69): поиск сайта компании, распознавание кадровой
страницы и чтение проверяются на постоянном списке компаний. Список не меняется между прогонами — числа прогонов
сравнимы.

## Состав

| Файл | Что | Откуда |
|---|---|---|
| `foreign-top500.csv` | 500 иностранных компаний Словакии, крупнейших по обороту за полные финансовые годы, завершившиеся в 2024 | исследование владельца 2026-10-07 (RÚZ, SlovakData; код собственности RÚZ 7 «Zahraničné» или 8 «Medzinárodné-súkromné»); не полный национальный рейтинг: банки и страховые не охвачены, у части компаний отчётность закрыта |
| `schema.sql` | схема `benchmark`: таблица списка и представление `benchmark.company` — список с компанией реестра ядра | — |
| `report.sql` | отчёт: сопоставление с реестром, итоги ядра сейчас, кадровая страница по лучшему из найденных сайтов (сравнима с группой «есть» замера топ-500; «использование запрещено» — отдельной строкой, аудит §78), `CONNECTED` по каналу — только портал или свой источник (§75) | — |

Схема `benchmark` — не часть приложения: Flyway и Hibernate её не трогают, в тестах её нет.

## Загрузка в стенд (из корня репозитория; повторный запуск загружает заново)

```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -v ON_ERROR_STOP=1 < benchmark/schema.sql
```
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "COPY benchmark.foreign_top500 FROM STDIN WITH (FORMAT csv, HEADER)" < benchmark/foreign-top500.csv
```

## Приоритет в ядре (§71)

Компании эталона отмечаются приоритетными: поиск сайта по названию берёт их без условия о размере, поиск по
названию, проверка сайтов и число сотрудников — раньше остальных.
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "UPDATE company c SET priority = TRUE FROM benchmark.company b WHERE b.company_id = c.id"
```

## Повторный прогон после правки ядра

Снимает отметки проверки у компаний эталона: поиск по названию (у компаний без найденного сайта) и проверка
сайтов пройдут заново со следующими заданиями цепочек.
```
docker compose exec -T postgres psql -U roleorienta -d roleorienta -c "UPDATE company SET site_name_checked_at = NULL WHERE priority" -c "UPDATE company_site s SET checked_at = NULL FROM company c WHERE c.id = s.company_id AND c.priority"
```

## Отчёт

```
docker compose exec -T postgres psql -U roleorienta -d roleorienta < benchmark/report.sql > target/benchmark-report.txt
```
