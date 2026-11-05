-- Источник компании по ссылке на вакансию у работодателя (стенограмма §266).
--   profesia_offer.employer_url — конечный адрес переадресации кнопки отклика объявления, добранного с profesia.sk
--     (§219; как job_posting.employer_url у объявлений общего списка портала, §209, V80); NULL — кнопки нет, отклик
--     на profesia.sk или переадресация не получена. Доска системы найма из этого адреса подключается источником
--     компании объявления (profesia_offer.company_id).
ALTER TABLE profesia_offer ADD COLUMN employer_url VARCHAR(2000);
