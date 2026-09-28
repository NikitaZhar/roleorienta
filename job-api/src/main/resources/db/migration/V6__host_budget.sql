-- Бюджет запросов к хосту (технический документ §16.11): время, раньше которого следующий запрос
-- к хосту не отправляется. Общий для всех реплик worker.
CREATE TABLE host_budget (
    host            VARCHAR(255) PRIMARY KEY,
    next_allowed_at TIMESTAMPTZ  NOT NULL
);
