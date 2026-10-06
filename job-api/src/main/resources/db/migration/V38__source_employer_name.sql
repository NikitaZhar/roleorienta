-- Название работодателя у системы найма для источника без компании из реестра (доски обратного пути, §27, §34;
-- аудит §65): Workday — hiringOrganization детали публикации, Greenhouse — название доски, SmartRecruiters —
-- og:site_name кадровой страницы. API сведений о вакансии берёт его, когда компании-работодателя у источника нет.
ALTER TABLE source ADD COLUMN employer_name VARCHAR(300);
