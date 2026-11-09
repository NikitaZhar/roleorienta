-- Ключи опознания вакансии (docs/vacancy-identity-plan.md §4, срез 1; стенограмма §278). Поведение записи и склейки
-- не меняется: ключи только считаются и хранятся у публикации; их начнут читать запись и пересмотр (срезы 2, 3).
--
-- Функции IMMUTABLE — результат зависит только от аргументов: так PostgreSQL разрешает хранимые вычисляемые колонки
-- (https://www.postgresql.org/docs/16/xfunc-volatility.html). Функции не падают ни на каких данных: только
-- lower / translate / replace / регулярные выражения, без преобразования в число — ошибка функции сорвала бы запись
-- публикации.

-- 1. Текст для сравнения: нижний регистр, кириллица — латиницей (русская и украинская), диакритика словацкая, чешская,
--    венгерская, польская, немецкая — без неё.
CREATE FUNCTION vacancy_text(value TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT translate(
        replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(
            lower(coalesce(value, '')),
            'щ', 'shch'), 'ж', 'zh'), 'х', 'kh'), 'ц', 'ts'), 'ч', 'ch'), 'ш', 'sh'), 'ю', 'yu'), 'я', 'ya'),
            'є', 'ye'), 'ї', 'yi'), 'ё', 'e'), 'ь', ''), 'ъ', ''),
        'абвгґдезиійклмнопрстуфыэ' || 'áäčďéěíĺľňóôŕřšťúůýž' || 'àâãåçèêëìîïñòõöøùûüÿąćęłńśźżőű',
        'abvggdeziiyklmnoprstufye' || 'aacdeeillnoorrstuuyz' || 'aaaaceeeiiinoooouuuyacelnszzou')
$$;

-- 2. Ключ названия — как same_title (V92: без зарплаты и пометок пола), но текст в скобках сохраняется (в скобках бывает
--    место или вид работы: Kaufland «(Popradská)» / «(Nad jazerom)»), кроме скобок с правовой формой
--    («(TOBI TEAN s.r.o.)»); кириллица — латиницей. same_title остаётся для показателей и потерь.
CREATE FUNCTION vacancy_title_key(title TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT btrim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
           vacancy_text(title),
           '\([^)]*\m(s\s*\.?\s*r\s*\.?\s*o|a\s*\.?\s*s|spol|k\s*\.?\s*s|v\s*\.?\s*o\s*\.?\s*s|gmbh|ltd|inc)\M[^)]*\)',
           ' ', 'g'),
           '(\mod\s+)?\d[\d .,]*(\s*-\s*\d[\d .,]*)?(,\s*-)?\s*(€|\meur\M)(\s*/\s*[a-z]+\.?)?(\s*(brutto|netto)\M)?', ' ', 'g'),
           '(€|\meur\M)\s*\d[\d .,]*', ' ', 'g'),
           '\m(muz|zena|m|z|f|w|d)(\s*/\s*(muz|zena|m|z|f|w|d))+\M', ' ', 'g'),
           '[^a-z0-9]+', ' ', 'g'))
$$;

-- 3. Места публикации — отсортированный массив без повторов: 'c:<город>' и 'r:<край>'; общее место (страна, удалённая
--    работа, пусто) мест не даёт — пустой массив. Текст в скобках убирается; части — по «;»; у части вида
--    «Obec - Okres» (портал) — до « - »; сегменты — по «,»; сегмент, который кончается номером дома («Hlavná 19»,
--    «Trnavská cesta 4928/2»), — улица, не место; прочие числа (почтовый индекс) убираются; «okres X» → X;
--    «Bratislava-…», «Bratislava I–V» → bratislava, так же Košice; «… kraj» — край.
CREATE FUNCTION vacancy_places(location TEXT) RETURNS TEXT[] LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    places  TEXT[] := '{}';
    part    TEXT;
    segment TEXT;
    place   TEXT;
BEGIN
    FOREACH part IN ARRAY string_to_array(regexp_replace(vacancy_text(location), '\([^)]*\)', ' ', 'g'), ';') LOOP
        FOREACH segment IN ARRAY string_to_array(split_part(part, ' - ', 1), ',') LOOP
            place := btrim(segment);
            CONTINUE WHEN place ~ '\d\S*$';
            place := btrim(regexp_replace(regexp_replace(place, '\S*\d\S*', ' ', 'g'), '[^a-z]+', ' ', 'g'));
            place := regexp_replace(place, '^(okres|slovakia|slovensko)\s+', '');
            CONTINUE WHEN length(place) < 3
                OR place IN ('slovensko', 'slovakia', 'slovenska republika', 'slovak republic', 'cesko', 'czechia',
                             'ceska republika', 'czech republic', 'madarsko', 'hungary', 'polsko', 'poland',
                             'rakusko', 'austria', 'nemecko', 'germany', 'europe', 'world wide', 'worldwide',
                             'remote', 'remote work', 'home office', 'praca z domu', 'prace z domu', 'hybrid');
            IF place ~ '^(bratislava|kosice)( |$)' THEN
                place := 'c:' || split_part(place, ' ', 1);
            ELSIF place ~ '\mkraj\M' THEN
                place := 'r:' || place;
            ELSE
                place := 'c:' || place;
            END IF;
            places := array_append(places, place);
        END LOOP;
    END LOOP;
    RETURN ARRAY(SELECT DISTINCT unnest(places) ORDER BY 1);
END
$$;

-- 4. Номер объявления profesia.sk — из внешнего id «profesia:N» или адреса «…profesia.sk/…/ON» (портал, добор,
--    кадровая страница на *.profesia.sk); нет — NULL. Строка, не число: функция не падает на длинных цифрах.
CREATE FUNCTION profesia_number(external_id TEXT, url TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT substring(coalesce(external_id, '') || ' ' || coalesce(url, '')
                     FROM '(?:profesia:|profesia\.sk/(?:[^ ]*/)?O)([0-9]{5,})')
$$;

-- 5. Адрес без сессионных параметров: без схемы, «www.», якоря, параметров search_id, utm_*, source, source_id,
--    register и «/» в конце; нижний регистр.
CREATE FUNCTION clean_address(url TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
           regexp_replace(lower(regexp_replace(coalesce(url, ''), '#.*$', '')), '^https?://(www\.)?', ''),
           '([?&])(search_id|utm_[a-z_]*|source|source_id|register)=[^&]*', '\1', 'g'),
           '&{2,}', '&', 'g'), '\?&', '?'), '[?&]+$', ''), '/+$', '')
$$;

-- 6. Ключи у публикации — хранимые вычисляемые колонки: PostgreSQL считает их при вставке и изменении строки (и один раз
--    для всех строк при добавлении — одной перезаписью таблицы); job-worker их не пишет
--    (https://www.postgresql.org/docs/16/ddl-generated-columns.html).
ALTER TABLE job_posting
    ADD COLUMN title_key    TEXT   GENERATED ALWAYS AS (vacancy_title_key(title)) STORED,
    ADD COLUMN place_keys   TEXT[] GENERATED ALWAYS AS (vacancy_places(location)) STORED,
    ADD COLUMN profesia_num TEXT   GENERATED ALWAYS AS (profesia_number(external_id, url)) STORED,
    ADD COLUMN address_key  TEXT   GENERATED ALWAYS AS (clean_address(url)) STORED;

-- Индексы для поиска кандидатов (срез 2): номер и название — обычные; адрес — hash (адрес бывает длиннее предела
-- строки обычного индекса); места — GIN: пересечение массивов (&&) без перебора строк
-- (https://www.postgresql.org/docs/16/gin-intro.html).
CREATE INDEX job_posting_profesia_num_idx ON job_posting (profesia_num) WHERE profesia_num IS NOT NULL;
CREATE INDEX job_posting_address_key_idx ON job_posting USING hash (address_key);
CREATE INDEX job_posting_title_key_idx ON job_posting (title_key);
CREATE INDEX job_posting_place_keys_idx ON job_posting USING gin (place_keys);
