-- Адрес кадровой страницы, введённый пользователем (бизнес-описание §5; технический документ §4 CompanyRequest;
-- стенограмма §137). Компания из групп «может быть» и «нет»; адрес проверяется заданием CHECK_CAREER_URL
-- (job-worker): читается — источник подключается к компании и общий для всех пользователей; не читается — причина.
--   state  — PENDING (в обработке), CONNECTED (подключена), NOT_CONNECTED (не подключена, причина в reason);
--   reason — UNREACHABLE (страница не открылась), USE_FORBIDDEN (запрет robots.txt или нет основания использования),
--            NO_CAREER_PAGE (не кадровая страница), FORMAT_UNSUPPORTED (кадровая, но читаемого на ней нет).
-- Удаление пользователя или компании удаляет их запросы.
CREATE TABLE company_request (
    id          BIGSERIAL     PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    company_id  BIGINT        NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    career_url  VARCHAR(2000) NOT NULL,
    state       VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                CHECK (state IN ('PENDING', 'CONNECTED', 'NOT_CONNECTED')),
    reason      VARCHAR(30)
                CHECK (reason IN ('UNREACHABLE', 'USE_FORBIDDEN', 'NO_CAREER_PAGE', 'FORMAT_UNSUPPORTED')),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    checked_at  TIMESTAMPTZ,
    CHECK ((state = 'NOT_CONNECTED') = (reason IS NOT NULL))
);

CREATE INDEX company_request_user_idx ON company_request (user_id, created_at);
CREATE INDEX company_request_pending_idx ON company_request (id) WHERE state = 'PENDING';
