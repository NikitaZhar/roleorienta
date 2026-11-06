-- Аудит §268, замечание 1 (стенограмма §270): ключ ссылки на вакансию сохраняет параметры. В V98 параметры
-- отбрасывались, а у Taleo (…/jobdetail.ftl?job=123) и части кадровых страниц (/kariera?id=5) номер вакансии стоит
-- именно в параметре: все вакансии такой доски получали один ключ, и объявление склеивалось с произвольной из них.
-- Теперь отбрасываются только якорь (#…), регистр, схема, www., языковая часть пути после хоста (/sk-SK/) и / в конце.
CREATE OR REPLACE FUNCTION employer_link_key(url TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT regexp_replace(
               regexp_replace(
                   regexp_replace(
                       regexp_replace(lower(url), '^https?://(www\.)?', ''),
                   '#.*$', ''),
               '^([^/]+)/[a-z]{2}-[a-z]{2}/', '\1/'),
           '/+$', '')
$$;
