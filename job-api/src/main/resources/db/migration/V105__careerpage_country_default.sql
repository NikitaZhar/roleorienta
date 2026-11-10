-- Место «страна компании» у карточек без текста на кадровых страницах вне зоны страны (срез 4, стенограмма §299;
-- решение владельца 2026-11-10, вариант 1'). Прежде карточка без текста получала страну источника по-английски
-- («Slovakia», CareerPageAdapter.location) на любом кадровом сайте — вакансии глобальных сайтов (globallogic.com,
-- careers.ing.com, karriere.tedi.com …) числились словацкими. Теперь — только у кадровой страницы в зоне .sk; прежние
-- значения снимаются здесь: при чтении пустое место прежнее не заменяет (JobPosting.update). Страны вакансий
-- пересчитывает сопоставление (версия правил мест p4): город Словакии в названии — по-прежнему Словакия.
UPDATE job_posting p SET location = NULL
FROM source s
WHERE s.id = p.source_id AND s.provider = 'careerpage' AND p.location = 'Slovakia'
  AND lower(split_part(split_part(CASE WHEN s.board LIKE '%://%' THEN split_part(s.board, '://', 2) ELSE s.board END,
                                  '/', 1), ':', 1)) NOT LIKE '%.sk';
