-- Склейка объявления площадки с вакансией системы найма работодателя по ссылке из кнопки отклика (стенограмма §268).
--   employer_link_key(url) — ключ сравнения ссылки на вакансию: без регистра, схемы, www., параметров и якоря,
--   языковой части пути сразу после хоста (Workday: /sk-SK/) и / в конце. Кнопка отклика объявления profesia.sk
--   Accenture ведёт на https://accenture.wd103.myworkdayjobs.com/sk-SK/AccentureCareers/job/Bratislava/X_R1, а
--   публикация Workday той же вакансии — https://accenture.wd103.myworkdayjobs.com/accenturecareers/job/Bratislava/X_R1:
--   ключ у обеих — accenture.wd103.myworkdayjobs.com/accenturecareers/job/bratislava/x_r1.
CREATE FUNCTION employer_link_key(url TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT regexp_replace(
               regexp_replace(
                   regexp_replace(
                       regexp_replace(lower(url), '^https?://(www\.)?', ''),
                   '[?#].*$', ''),
               '^([^/]+)/[a-z]{2}-[a-z]{2}/', '\1/'),
           '/+$', '')
$$;
