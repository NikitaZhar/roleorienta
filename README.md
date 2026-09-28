# Roleorienta

Приложение ищет открытые вакансии по условиям пользователя (страны, позиция, формат работы):
само находит компании выбранных стран, их кадровые страницы, собирает и обновляет опубликованные
там вакансии и выдаёт пользователю подходящие порциями.

Документы: [бизнес-описание](docs/roleorienta-Business%20description.md),
[технический документ](docs/technical-design.md), [рабочий контракт](docs/working-contract.md),
[стенограмма проекта](docs/project-notes.md).

> **Статус:** подэтап 1.1 — каркас, доставка заданий (outbox → RabbitMQ) с повтором через БД, защищённый HTTP-клиент; бизнес-логики нет.
> План — технический документ §15.

## Архитектура

Два приложения Spring Boot на общей PostgreSQL:

| Модуль | Роль |
|---|---|
| `job-api` | REST API и пользовательские сценарии; владеет схемой БД (Flyway) |
| `job-worker` | Фоновая работа: планировщик, outbox → RabbitMQ, общий сбор, проходы выдачи |

## Стек

Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, RabbitMQ 4, MinIO (S3), Apache HttpClient 5, Maven,
JUnit 5 + Testcontainers, Checkstyle, GitHub Actions.

## Запуск локально

Нужны JDK 21, Maven, Docker.

```bash
docker compose up -d                       # PostgreSQL :5433, RabbitMQ :5672, MinIO :9000
mvn -B -ntp verify                         # Checkstyle, компиляция, тесты (Testcontainers)
mvn -pl job-api spring-boot:run            # http://localhost:8080/actuator/health
mvn -pl job-worker spring-boot:run         # http://localhost:8081/actuator/health
```

Настройки подключения — переменные окружения (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`RABBITMQ_HOST`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `SERVER_PORT`); значения по умолчанию
совпадают с `docker-compose.yml`.
