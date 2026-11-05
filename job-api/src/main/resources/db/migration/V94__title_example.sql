-- Распознавание позиции по похожему названию (технический документ §15, MVP п. 5б, шаг 1; стенограмма §263).
--   title_example — пример с известным кодом и его вектор: название вакансии портала с кодом профессии (§203) или
--     название профессии ESCO (V75, sk и en). Название вакансии без позиции получает код ближайших по смыслу примеров.
--     source     — PORTAL или ESCO.
--     example    — ключ: у портала — same_title названия (V92), у ESCO — само название.
--     code       — группа ISCO-08 примера.
--     text       — текст, вектор которого записан (у портала — одно из названий с этим ключом).
--     model      — модель векторов (Ollama, bge-m3); векторы разных моделей не сравниваются.
--     vector     — вектор: числа float32 по 4 байта, младший байт первым (у bge-m3 — 1 024 числа, 4 КБ).
--   Ответы языковой модели §262 удаляются: она так и не ответила по существу (за ночь — один ответ «не понять», замер
--   §263 — 14 % совпадений с кодом портала); названия распознаются заново новым способом.
CREATE TABLE title_example (
    source     VARCHAR(10)  NOT NULL CHECK (source IN ('PORTAL', 'ESCO')),
    example    TEXT         NOT NULL,
    code       VARCHAR(4)   NOT NULL REFERENCES occupation_group (code),
    text       TEXT         NOT NULL,
    model      VARCHAR(80)  NOT NULL,
    vector     BYTEA        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, example, code)
);

DELETE FROM title_classification;
