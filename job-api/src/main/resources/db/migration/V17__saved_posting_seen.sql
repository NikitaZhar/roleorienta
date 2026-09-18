-- V17 — «отметить просмотренной» (§7): признак просмотра публикации пользователем.
-- Шаг expand (ADR-2): добавляется столбец seen_at и снимается NOT NULL со state.
-- Причина: просмотр ортогонален «сохранить/скрыть» — строка-маркер может существовать
-- только из-за просмотра (state = NULL, seen_at задан). SavedState по-прежнему SAVED|HIDDEN;
-- NULL означает «явного сохранения/скрытия нет».

ALTER TABLE saved_posting ADD COLUMN seen_at TIMESTAMPTZ;      -- момент первого просмотра или NULL
ALTER TABLE saved_posting ALTER COLUMN state DROP NOT NULL;    -- допускаем маркер «только просмотрено»
