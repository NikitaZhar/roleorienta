-- Распознавание позиции по похожему названию — порог для примеров ESCO (стенограмма §264).
--   Первые 5 000 ответов (§263) получены с одним порогом 0,8 для всех примеров: общие английские названия
--   («Senior Associate» — 365 публикаций, «Senior Manager», «Trainee», «Supervisor Operations») получили чужие
--   группы по похожим названиям ESCO. Ответы снимаются и будут получены заново с порогом 0,9 для примеров ESCO.
--   1. У открытых публикаций без кода портала, чья распознанная группа — ответ этого способа, группа снимается
--      (у них не было группы от правил: ответ ставится только публикациям без распознанной группы, §262).
--      Если правило (выученная голова, синоним ESCO) даёт группу, пересчёт раз в сутки поставит её снова (§215).
--   2. Ответы удаляются — названия распознаются заново в следующем задании TITLE_CLASSIFY.
UPDATE job_posting p SET title_occupation_code = NULL
FROM title_classification c
WHERE c.title_key = same_title(p.title) AND p.title_occupation_code = c.code
  AND p.occupation_code IS NULL AND p.closed_at IS NULL;

DELETE FROM title_classification;
