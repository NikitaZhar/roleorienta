-- Старые вакансии не показываются в результатах (решение владельца 2026-10-28, стенограмма §217).
--
-- Вакансия, которую работодатель разместил больше 4 месяцев назад, почти всегда забыта или «вечная» (набор резюме
-- впрок): отклик туда — ожидание впустую. Замер 2026-10-28: в Словакии из 28 933 открытых вакансий старше 6 месяцев —
-- 2 962, 97 % из них — государственный портал.
--
-- Правило — в представлении search_condition_match, едином правиле «вакансия подходит к условиям» для выдачи
-- (DeliveryRepository.PORTION), сводки на почту (DigestRepository.FRESH_FROM) и результатов (VacancyRepository):
-- старая вакансия не выдаётся, не попадает в письмо и уходит из уже накопленных результатов. «Мои отклики» и
-- «Не интересно» представление не читают — отклик на старую вакансию остаётся на месте. Из БД ничего не удаляется.
--
-- Возраст — только по дате размещения работодателем (vacancy.posted_at). Без неё вакансия показывается: дата
-- обнаружения говорит о начале нашего сбора, а не о возрасте вакансии. Порог меняется новой миграцией с
-- CREATE OR REPLACE VIEW (те же колонки в том же порядке): https://www.postgresql.org/docs/16/sql-createview.html
CREATE OR REPLACE VIEW search_condition_match AS
SELECT hit.search_condition_id, hit.code, hit.vacancy_id
FROM (SELECT s.search_condition_id, p.code, m.vacancy_id
      FROM search_condition_position s
      JOIN position p ON p.id = s.position_id
      JOIN vacancy_position_match m ON m.position_id = s.position_id
      UNION ALL
      SELECT o.search_condition_id, o.code, jp.vacancy_id
      FROM search_condition_occupation o
      JOIN job_posting jp ON jp.occupation_code = o.code
      UNION ALL
      SELECT o.search_condition_id, o.code, m.vacancy_id
      FROM search_condition_occupation o
      JOIN position p ON p.occupation_code = o.code
      JOIN vacancy_position_match m ON m.position_id = p.id
      UNION ALL
      SELECT o.search_condition_id, o.code, jp.vacancy_id
      FROM search_condition_occupation o
      JOIN job_posting jp ON jp.title_occupation_code = o.code) hit
JOIN vacancy v ON v.id = hit.vacancy_id
WHERE v.posted_at IS NULL OR v.posted_at > now() - interval '4 months';
