-- Исправления по аудиту §270 (стенограмма §271).
--
-- 1. Страница объявления profesia.sk, прочитанная не до конца (замечания 4 и 6): страница без объявления (капча,
--    баннер) или переадресация кнопки отклика не получена — задание PROFESIA_POSITIONS читает её снова не раньше чем
--    через 7 дней. NULL — не откладывалась.
ALTER TABLE job_posting ADD COLUMN profesia_page_deferred_at TIMESTAMPTZ;

-- 2. EURES (замечание 3): подставной IČO 10000000 — один на всех зарубежных работодателей, не IČO. У объявлений он
--    снимается: источник портала — по названию работодателя (доска «name:…», §259), как у прочих без IČO. Прежний
--    общий источник 10000000 (все объявления EURES под названием одного работодателя) закрывается, как закрывает
--    администратор (§206): его открытые публикации и вакансии без других открытых публикаций закрываются; вакансии
--    появятся снова в источниках по названию после ближайшего итога обхода портала (не позже суток).
UPDATE portal_offer SET registration_number = NULL WHERE registration_number = '10000000';

UPDATE vacancy v SET state = 'CLOSED', closed_at = now()
WHERE v.state <> 'CLOSED'
  AND EXISTS (SELECT 1 FROM job_posting p JOIN source s ON s.id = p.source_id
              WHERE p.vacancy_id = v.id AND s.provider = 'sluzbyzamestnanosti' AND s.board = '10000000'
                AND p.closed_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM job_posting p JOIN source s ON s.id = p.source_id
                  WHERE p.vacancy_id = v.id AND NOT (s.provider = 'sluzbyzamestnanosti' AND s.board = '10000000')
                    AND p.closed_at IS NULL);

UPDATE job_posting SET closed_at = now(), confirmed = FALSE
WHERE closed_at IS NULL
  AND source_id IN (SELECT id FROM source WHERE provider = 'sluzbyzamestnanosti' AND board = '10000000');

UPDATE source SET closed_at = now()
WHERE provider = 'sluzbyzamestnanosti' AND board = '10000000' AND closed_at IS NULL;

-- 3. Главная публикация вакансии (замечание 5): название и ссылку вакансии задаёт первая публикация не с profesia.sk,
--    нет такой — первая. Вакансии, где первым было объявление profesia.sk, а есть публикация не с profesia.sk (склейка
--    §268, присоединение при записи), получают её название и ссылку сразу, а не при её следующем полном чтении;
--    сопоставление пересчитывается.
UPDATE vacancy v SET title = m.title, primary_url = m.url, match_version = NULL
FROM (SELECT DISTINCT ON (p.vacancy_id) p.vacancy_id, p.id, p.title, p.url
      FROM job_posting p WHERE p.external_id NOT LIKE 'profesia:%'
      ORDER BY p.vacancy_id, p.id) m
WHERE m.vacancy_id = v.id
  AND EXISTS (SELECT 1 FROM job_posting q WHERE q.vacancy_id = v.id AND q.external_id LIKE 'profesia:%' AND q.id < m.id)
  AND (v.title IS DISTINCT FROM m.title OR v.primary_url IS DISTINCT FROM m.url);
