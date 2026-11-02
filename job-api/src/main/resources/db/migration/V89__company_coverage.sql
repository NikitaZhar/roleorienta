-- Полнота сбора вакансий, п. 5а шаг 1 (технический документ §15; стенограмма §234): найдены ли вакансии нанимающей
-- компании на её собственной кадровой странице.
--   company.no_own_career_page — отметка администратора: своей кадровой страницы у компании нет, её вакансии берутся
--                                с государственного портала и profesia.sk (принцип владельца, §233); в «потери» не
--                                попадает.
--   company_coverage            — пересчитывается вместе с показателями ядра (COMPUTE_METRICS); строка — нанимающая
--                                компания (есть открытая вакансия портала или profesia.sk в её стране):
--     portal_titles — разных названий вакансий портала и profesia.sk;
--     found_on_own  — из них есть и на собственной странице компании (то же название, правило §220);
--     own_vacancies — открытых вакансий собственной страницы (свои источники компании, не портал и не profesia.sk);
--     channel       — состояние собственного канала: READ — читается, READ_EMPTY — читается, вакансий нет, GROUP_ONLY —
--                     только доска группы, PAGE_FOUND — страница найдена, источника нет, FORMAT_UNSUPPORTED,
--                     NO_CAREER_PAGE, UNREACHABLE, USE_FORBIDDEN — итог проверки сайта, NOT_CHECKED — сайт не
--                     проверен, NO_SITE — сайт не найден, NO_OWN_PAGE — отметка администратора.
--   Данные производные: строки удаляются вместе с компанией (ON DELETE CASCADE).
ALTER TABLE company ADD COLUMN no_own_career_page BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE company_coverage (
    company_id    BIGINT      PRIMARY KEY REFERENCES company (id) ON DELETE CASCADE,
    portal_titles INTEGER     NOT NULL CHECK (portal_titles > 0),
    found_on_own  INTEGER     NOT NULL CHECK (found_on_own >= 0 AND found_on_own <= portal_titles),
    own_vacancies INTEGER     NOT NULL CHECK (own_vacancies >= 0),
    channel       VARCHAR(20) NOT NULL CHECK (channel IN ('READ', 'READ_EMPTY', 'GROUP_ONLY', 'PAGE_FOUND',
                                                          'FORMAT_UNSUPPORTED', 'NO_CAREER_PAGE', 'UNREACHABLE',
                                                          'USE_FORBIDDEN', 'NOT_CHECKED', 'NO_SITE', 'NO_OWN_PAGE')),
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
