-- Уведомления администратору о потерях вакансий (технический документ §15, MVP п. 5а шаг 1; стенограмма §235).
--   same_title(title)            — ключ названия для сравнения вакансий (правило §220): без регистра, словацкой и
--                                  чешской диакритики, пометок в скобках («(m/f/d)», «(muž/žena)») и знаков; одно
--                                  определение для job-worker (дубли profesia.sk, полнота сбора) и job-api (карточка
--                                  потерь). IMMUTABLE — результат зависит только от аргумента
--                                  (https://www.postgresql.org/docs/16/xfunc-volatility.html).
--   company.loss_postponed_until — «Later»: до этого момента компания администратору не предлагается.
CREATE FUNCTION same_title(title TEXT) RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT btrim(regexp_replace(regexp_replace(lower(translate(title,
           'áäčďéěíĺľňóôŕřšťúůýžÁÄČĎÉĚÍĹĽŇÓÔŔŘŠŤÚŮÝŽ', 'aacdeeillnoorrstuuyzaacdeeillnoorrstuuyz')),
           '\([^)]*\)', ' ', 'g'), '[^a-z0-9]+', ' ', 'g'))
$$;

ALTER TABLE company ADD COLUMN loss_postponed_until TIMESTAMPTZ;
