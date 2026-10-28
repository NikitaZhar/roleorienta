import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { ApiError, setRequestLanguage } from './api';

/** Язык интерфейса пользователя (бизнес-описание §6; стенограмма §162): английский — по умолчанию. */
export type Language = 'en' | 'ru';

/** Ключ выбранного языка в хранилище браузера: экран входа помнит выбор до регистрации и входа. */
const LANGUAGE_KEY = 'roleorienta.language';

/**
 * Тексты экранов пользователя по-английски. {@code {name}} — место подстановки значения. Экран администратора не
 * переводится (решение владельца 2026-10-21). Названия позиций словаря не переводятся — как в словаре; названия групп
 * ISCO-08 приходят с сервера на языке интерфейса (§204).
 */
const EN = {
  'app.tagline': 'Take a breath. New jobs will come to you.',
  'app.unavailable': 'Service unavailable: {detail}',
  'app.loading': 'Loading…',
  'nav.sections': 'Sections',
  'nav.search': 'Search',
  'nav.applications': 'My applications',
  'nav.notInterested': 'Not interested',
  'nav.companies': 'Companies',
  'menu.account': 'Account menu',
  'menu.settings': 'Settings',
  'menu.signOut': 'Sign out',
  'menu.signOutFailed': 'Sign out failed: {detail}',
  'language.switch': 'Language',
  'login.email': 'Email',
  'login.password': 'Password',
  'login.signIn': 'Sign in',
  'login.create': 'Create account',
  'error.fields': 'Check the fields: {fields}',
  'error.signIn': 'Wrong email or password',
  'error.emailTaken': 'This email is already registered',
  'error.sessionExpired': 'You are signed out — reload the page and sign in again',
  'error.tooMany': 'Too many requests today, try again tomorrow',
  'error.notFound': 'Not found',
  'settings.title': 'Settings',
  'settings.saved': 'Saved',
  'settings.digest': 'Email summary of new vacancies',
  'settings.daily': 'Every day',
  'settings.weekdays': 'Weekdays',
  'settings.weekly': 'Once a week (Monday)',
  'settings.never': 'Never',
  'settings.digestNote': 'The email arrives at 8:00 your time to {email}. No new vacancies — no email.',
  'back.applications': '← Back to applications',
  'back.notInterested': '← Back to Not interested',
  'conditions.empty': 'No search criteria yet. Choose a country and a position.',
  'conditions.country': 'Country',
  'conditions.countries': 'Countries',
  'conditions.position': 'Position',
  'conditions.positions': 'Positions',
  'conditions.format': 'Format',
  'conditions.formatNote': 'Vacancies without a stated format are shown too.',
  'conditions.workFormat': 'Work format',
  'conditions.edit': 'Edit',
  'conditions.lookFor': 'Vacancies to look for',
  'conditions.typeLetters': 'Type at least 2 letters',
  'conditions.remove': 'Remove {name}',
  'conditions.search': 'Search',
  'conditions.cancel': 'Cancel',
  'conditions.chooseBoth': 'Choose at least one country and a position',
  'conditions.changedElsewhere': 'Search was changed in another window; reloaded, check and search again',
  'conditions.retired': '{name} (retired)',
  'conditions.wholeGroup': 'all jobs in the group',
  'conditions.browse': 'Full catalog',
  'conditions.browseClose': 'Hide full catalog',
  'conditions.catalogSearch': 'Search the catalog',
  'conditions.catalogNone': 'Nothing in the catalog — try another word.',
  'conditions.catalogLegend': 'A group — all jobs in the group; indented — an exact position.',
  'conditions.catalogFull': 'Up to 10 positions: remove one to choose another.',
  'format.any': 'Any',
  'format.office': 'Office',
  'format.remote': 'Remote',
  'list.new': 'Not opened',
  'list.posted': 'Posted {date}',
  'list.found': 'Found {date}',
  'list.marked': 'Marked {date}',
  'list.count': '{total} vacancies',
  'list.countOne': '1 vacancy',
  'list.newCount': '{fresh} not opened',
  'list.sinceLastVisit': 'Arrived since your last visit',
  'list.dismiss': 'Not interested',
  'list.applied': 'Applied',
  'list.byPosition': 'Show by position',
  'list.sinceLastVisitFilter': 'New since last visit',
  'list.movedToApplications': 'Moved to My applications',
  'list.dismissed': 'Moved to Not interested',
  'list.undo': 'Undo',
  'list.updatedHourly': 'Updated hourly',
  'list.collecting': 'Collecting matching vacancies… usually under a minute.',
  'list.nothingYetTitle': 'No matching vacancies yet',
  'list.nothingYetText': 'New ones are checked every hour.',
  'list.return': 'Return',
  'list.retry': 'Retry',
  'list.emptyResponses': 'Press ✓ in search results when you apply — the vacancy will appear here',
  'list.returned': 'Returned to search results',
  'list.emptyNotInterested': 'Vacancies you mark as not interested will appear here',
  'pager.pages': 'Pages',
  'pager.previous': 'Previous page',
  'pager.next': 'Next page',
  'pager.perPage': 'Per page:',
  'pager.perPageLabel': 'Rows per page',
  'vacancy.employer': 'Employer',
  'vacancy.agency': 'Agency',
  'vacancy.status': 'Status',
  'vacancy.returnToResults': 'Not applied — back to search results',
  'vacancy.country': 'Country',
  'vacancy.format': 'Format',
  'vacancy.languages': 'Languages',
  'vacancy.opened': 'Opened',
  'vacancy.openPosting': 'Open the posting',
  'vacancy.history': 'History',
  'vacancy.closedByEmployer': 'closed by employer',
  'vacancy.found': 'found {date}',
  'vacancy.anyCountry': 'any country',
  'vacancy.eu': 'EU',
  'vacancy.onlyOffice': 'Only office',
  'vacancy.onlyRemote': 'Only remote',
  'vacancy.hybrid': 'Hybrid',
  'vacancy.from': '{format} — from {territory}',
  'vacancy.required': 'required',
  'vacancy.preferred': 'preferred',
  'vacancy.closed': 'closed',
  'status.NEW': 'New',
  'status.VIEWED': 'Viewed',
  'status.NOT_INTERESTED': 'Not interested',
  'status.APPLIED': 'Applied',
  'status.INTERVIEW': 'Interview',
  'status.REJECTED': 'Employer declined',
  'list.finished': 'Completed',
  'list.noReply': 'No reply for {days} days',
  'history.RECEIVED': 'Received',
  'history.VIEWED': 'Viewed',
  'history.NOT_INTERESTED': 'Not interested',
  'history.APPLIED': 'Applied',
  'history.INTERVIEW': 'Interview',
  'history.REJECTED': 'Employer declined',
  'history.RETURNED': 'Returned',
  'history.CLOSED': 'Closed by employer',
  'companies.title': 'Companies',
  'companies.query': 'Company name or IČO',
  'companies.search': 'Search',
  'companies.noneFound': 'No companies found.',
  'companies.firstOnly': 'Showing the first {count} companies — refine the name.',
  'companies.noAddresses': 'You have not added any address yet.',
  'companies.address': 'Address',
  'companies.state': 'State',
  'companies.added': 'Added',
  'companies.company': 'Company',
  'companies.group': 'Vacancies',
  'companies.site': 'Site',
  'companies.careerPage': 'Vacancies page',
  'companies.actions': 'Actions',
  'companies.addressOf': 'Vacancies page address of {name}',
  'companies.add': 'Add',
  'companies.cancel': 'Cancel',
  'companies.addedNote': 'Added — see “Added by me”.',
  'companies.addAddress': 'Add address',
  'companies.tabFind': 'Find',
  'companies.tabAddresses': 'Added by me',
  'companies.noteFind': 'Check whether a company’s vacancies are collected.',
  'companies.noteAdded': 'Vacancies page addresses you added. Checking takes a few minutes.',
  'request.PENDING': 'checking…',
  'request.CONNECTED': 'connected',
  'request.NOT_CONNECTED': 'not connected',
  'reason.SITE_NOT_FOUND': 'site not found',
  'reason.PAGE_NOT_FOUND': 'vacancies page not found',
  'reason.FORMAT_UNSUPPORTED': 'vacancies page not readable',
  'reason.SOURCE_UNAVAILABLE': 'site did not answer',
  'reason.USE_FORBIDDEN': 'use not allowed',
  'reason.UNREACHABLE': 'page did not open',
  'reason.NO_CAREER_PAGE': 'not a vacancies page',
  'group.YES': 'Collected from the company site',
  'group.PORTAL': 'Collected from the state portal',
  'group.MAYBE': 'Not collected',
  'group.NO': 'Not collected',
  'group.NOT_EMPLOYER': 'Not an employer',
} as const;

/** Ключ текста экрана. */
export type MessageKey = keyof typeof EN;

/** Тексты по-русски: TypeScript требует перевод каждого ключа. */
const RU: Record<MessageKey, string> = {
  'app.tagline': 'Выдохните. Вакансии придут к вам сами.',
  'app.unavailable': 'Сервис недоступен: {detail}',
  'app.loading': 'Загрузка…',
  'nav.sections': 'Разделы',
  'nav.search': 'Поиск',
  'nav.applications': 'Мои отклики',
  'nav.notInterested': 'Не интересно',
  'nav.companies': 'Компании',
  'menu.account': 'Меню учётной записи',
  'menu.settings': 'Настройки',
  'menu.signOut': 'Выйти',
  'menu.signOutFailed': 'Не удалось выйти: {detail}',
  'language.switch': 'Язык',
  'login.email': 'Почта',
  'login.password': 'Пароль',
  'login.signIn': 'Войти',
  'login.create': 'Создать учётную запись',
  'error.fields': 'Проверьте поля: {fields}',
  'error.signIn': 'Неверная почта или пароль',
  'error.emailTaken': 'Эта почта уже зарегистрирована',
  'error.sessionExpired': 'Вход завершён — обновите страницу и войдите снова',
  'error.tooMany': 'Слишком много запросов за сутки, попробуйте завтра',
  'error.notFound': 'Не найдено',
  'settings.title': 'Настройки',
  'settings.saved': 'Сохранено',
  'settings.digest': 'Сводка новых вакансий на почту',
  'settings.daily': 'Каждый день',
  'settings.weekdays': 'По будням',
  'settings.weekly': 'Раз в неделю (понедельник)',
  'settings.never': 'Не присылать',
  'settings.digestNote': 'Письмо приходит в 8:00 по вашему времени на {email}. Новых вакансий нет — письма нет.',
  'back.applications': '← К откликам',
  'back.notInterested': '← К «Не интересно»',
  'conditions.empty': 'Критерии поиска не заданы. Выберите страну и позицию.',
  'conditions.country': 'Страна',
  'conditions.countries': 'Страны',
  'conditions.position': 'Позиция',
  'conditions.positions': 'Позиции',
  'conditions.format': 'Формат',
  'conditions.formatNote': 'Вакансии без указанного формата тоже показываются.',
  'conditions.workFormat': 'Формат работы',
  'conditions.edit': 'Изменить',
  'conditions.lookFor': 'Какие вакансии искать',
  'conditions.typeLetters': 'Введите хотя бы 2 буквы',
  'conditions.remove': 'Убрать {name}',
  'conditions.search': 'Искать',
  'conditions.cancel': 'Отмена',
  'conditions.chooseBoth': 'Выберите хотя бы одну страну и позицию',
  'conditions.changedElsewhere': 'Условия изменены в другом окне; они перечитаны — проверьте и запустите поиск снова',
  'conditions.retired': '{name} (выключена)',
  'conditions.wholeGroup': 'все вакансии группы',
  'conditions.browse': 'Полный каталог',
  'conditions.browseClose': 'Скрыть каталог',
  'conditions.catalogSearch': 'Поиск в каталоге',
  'conditions.catalogNone': 'В каталоге ничего нет — попробуйте другое слово.',
  'conditions.catalogLegend': 'Группа — все вакансии группы; с отступом — точная позиция.',
  'conditions.catalogFull': 'Не больше 10 позиций: уберите одну, чтобы выбрать другую.',
  'format.any': 'Любой',
  'format.office': 'Офис',
  'format.remote': 'Удалённо',
  'list.new': 'Не открыта',
  'list.posted': 'Опубликована {date}',
  'list.found': 'Найдена {date}',
  'list.marked': 'Отмечена {date}',
  'list.count': 'Вакансий: {total}',
  'list.countOne': 'Вакансий: 1',
  'list.newCount': 'не открыто: {fresh}',
  'list.sinceLastVisit': 'Пришла после вашего прошлого захода',
  'list.dismiss': 'Не интересно',
  'list.applied': 'Отклик отправлен',
  'list.byPosition': 'Показать по позиции',
  'list.sinceLastVisitFilter': 'Новые с прошлого захода',
  'list.movedToApplications': 'Перенесена в «Мои отклики»',
  'list.dismissed': 'Перенесена в «Не интересно»',
  'list.undo': 'Отменить',
  'list.updatedHourly': 'обновляется каждый час',
  'list.collecting': 'Собираем подходящие вакансии… обычно меньше минуты.',
  'list.nothingYetTitle': 'Подходящих вакансий пока нет',
  'list.nothingYetText': 'Новые проверяются каждый час.',
  'list.return': 'Вернуть',
  'list.retry': 'Повторить',
  'list.emptyResponses': 'Нажмите ✓ в результатах поиска, когда откликнулись, — вакансия появится здесь',
  'list.returned': 'Возвращена в результаты поиска',
  'list.emptyNotInterested': 'Здесь будут вакансии, отмеченные «Не интересно»',
  'pager.pages': 'Страницы',
  'pager.previous': 'Предыдущая страница',
  'pager.next': 'Следующая страница',
  'pager.perPage': 'На странице:',
  'pager.perPageLabel': 'Строк на странице',
  'vacancy.employer': 'Работодатель',
  'vacancy.agency': 'Агентство',
  'vacancy.status': 'Статус',
  'vacancy.returnToResults': 'Не откликался — вернуть в результаты',
  'vacancy.country': 'Страна',
  'vacancy.format': 'Формат',
  'vacancy.languages': 'Языки',
  'vacancy.opened': 'Открыта',
  'vacancy.openPosting': 'Открыть объявление',
  'vacancy.history': 'История',
  'vacancy.closedByEmployer': 'закрыта работодателем',
  'vacancy.found': 'найдена {date}',
  'vacancy.anyCountry': 'любая страна',
  'vacancy.eu': 'ЕС',
  'vacancy.onlyOffice': 'Только офис',
  'vacancy.onlyRemote': 'Только удалённо',
  'vacancy.hybrid': 'Смешанный',
  'vacancy.from': '{format}: {territory}',
  'vacancy.required': 'обязателен',
  'vacancy.preferred': 'желателен',
  'vacancy.closed': 'закрыта',
  'status.NEW': 'Новая',
  'status.VIEWED': 'Просмотрена',
  'status.NOT_INTERESTED': 'Не интересно',
  'status.APPLIED': 'Отклик отправлен',
  'status.INTERVIEW': 'Собеседование',
  'status.REJECTED': 'Работодатель отказал',
  'list.finished': 'Завершённые',
  'list.noReply': 'Нет ответа {days} дн.',
  'history.RECEIVED': 'Получена',
  'history.VIEWED': 'Просмотрена',
  'history.NOT_INTERESTED': 'Не интересно',
  'history.APPLIED': 'Отклик отправлен',
  'history.INTERVIEW': 'Собеседование',
  'history.REJECTED': 'Работодатель отказал',
  'history.RETURNED': 'Возвращена',
  'history.CLOSED': 'Закрыта работодателем',
  'companies.title': 'Компании',
  'companies.query': 'Название компании или IČO',
  'companies.search': 'Искать',
  'companies.noneFound': 'Компании не найдены.',
  'companies.firstOnly': 'Показаны первые {count} компаний — уточните название.',
  'companies.noAddresses': 'Вы ещё не добавили ни одного адреса.',
  'companies.address': 'Адрес',
  'companies.state': 'Состояние',
  'companies.added': 'Добавлен',
  'companies.company': 'Компания',
  'companies.group': 'Вакансии',
  'companies.site': 'Сайт',
  'companies.careerPage': 'Страница вакансий',
  'companies.actions': 'Действия',
  'companies.addressOf': 'Адрес страницы вакансий: {name}',
  'companies.add': 'Добавить',
  'companies.cancel': 'Отмена',
  'companies.addedNote': 'Добавлен — см. «Добавленные мной».',
  'companies.addAddress': 'Добавить адрес',
  'companies.tabFind': 'Найти',
  'companies.tabAddresses': 'Добавленные мной',
  'companies.noteFind': 'Проверьте, собираются ли вакансии нужной компании.',
  'companies.noteAdded': 'Адреса страниц вакансий, которые вы добавили. Проверка занимает несколько минут.',
  'request.PENDING': 'проверяется…',
  'request.CONNECTED': 'подключена',
  'request.NOT_CONNECTED': 'не подключена',
  'reason.SITE_NOT_FOUND': 'сайт не найден',
  'reason.PAGE_NOT_FOUND': 'страница вакансий не найдена',
  'reason.FORMAT_UNSUPPORTED': 'страница вакансий не читается',
  'reason.SOURCE_UNAVAILABLE': 'сайт не ответил',
  'reason.USE_FORBIDDEN': 'использование запрещено',
  'reason.UNREACHABLE': 'страница не открылась',
  'reason.NO_CAREER_PAGE': 'это не страница вакансий',
  'group.YES': 'Собираются с сайта компании',
  'group.PORTAL': 'Собираются с госпортала',
  'group.MAYBE': 'Не собираются',
  'group.NO': 'Не собираются',
  'group.NOT_EMPLOYER': 'Не работодатель',
};

const MESSAGES: Record<Language, Record<MessageKey, string>> = { en: EN, ru: RU };

/** Текст по ключу с подстановкой значений {@code {name}}. */
export type Translate = (key: MessageKey, values?: Record<string, string | number>) => string;

/** Язык, перевод и смена языка. */
export interface I18n {
  language: Language;
  t: Translate;
  /** Код языка для Intl и дат (en, ru). */
  locale: string;
  setLanguage: (language: Language) => void;
}

function translator(language: Language): Translate {
  return (key, values) => {
    let text: string = MESSAGES[language][key];
    if (values !== undefined) {
      Object.entries(values).forEach(([name, value]) => {
        text = text.replaceAll('{' + name + '}', String(value));
      });
    }
    return text;
  };
}

/** Без поставщика (экран администратора) — английский, смены языка нет. */
const I18nContext = createContext<I18n>({ language: 'en', t: translator('en'), locale: 'en', setLanguage: () => {} });

/** Язык, сохранённый в браузере; нет или хранилище недоступно — английский. */
function storedLanguage(): Language {
  try {
    return localStorage.getItem(LANGUAGE_KEY) === 'ru' ? 'ru' : 'en';
  } catch {
    return 'en';
  }
}

function storeLanguage(language: Language) {
  try {
    localStorage.setItem(LANGUAGE_KEY, language);
  } catch {
    // Хранилище недоступно (приватный режим): выбор действует до перезагрузки.
  }
}

/**
 * Поставщик языка экранов пользователя: начальный — сохранённый в браузере (выбор на экране входа), после входа —
 * из учётной записи (его ставит {@code App}). Смена запоминается в браузере и меняет атрибут {@code lang} страницы.
 * Язык уходит и в запросы к API (названия групп ISCO-08, §204) — сразу, а не эффектом: эффекты дочерних экранов
 * выполняются раньше эффектов поставщика, и первые запросы ушли бы без языка.
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(() => {
    const initial = storedLanguage();
    setRequestLanguage(initial);
    return initial;
  });

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  const value = useMemo<I18n>(() => ({
    language,
    t: translator(language),
    locale: language,
    setLanguage: (next: Language) => {
      storeLanguage(next);
      setRequestLanguage(next);
      setLanguageState(next);
    },
  }), [language]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/** @returns язык, перевод и смена языка */
export function useI18n(): I18n {
  return useContext(I18nContext);
}

/**
 * Текст ошибки ответа сервера на языке экрана (решение владельца 2026-10-21: экран называет ошибку по коду ответа,
 * тексты сервера не переводятся): неверные поля — какие; 401 — вход завершён (сессия истекла или выход в другом
 * окне); 429 — лимит за сутки; 404 — не найдено; остальное, в том числе 409, — текст ответа как есть: у 409 разный
 * смысл (адрес уже проверяется, одновременное изменение), «почта занята» и «неверная почта или пароль» называет
 * экран входа сам (аудит §165).
 */
export function errorText(failure: unknown, t: Translate): string {
  if (failure instanceof ApiError) {
    if (failure.fields.length > 0) {
      return t('error.fields', { fields: failure.fields.map((field) => field.pointer.substring(1)).join(', ') });
    }
    if (failure.status === 401) {
      return t('error.sessionExpired');
    }
    if (failure.status === 429) {
      return t('error.tooMany');
    }
    if (failure.status === 404) {
      return t('error.notFound');
    }
  }
  return (failure as Error).message;
}

/** Переключатель языка EN | RU: на экране входа и в панели (решение владельца 2026-10-21). */
export function LanguageSwitch({ onChange }: { onChange?: (language: Language) => void }) {
  const { language, setLanguage, t } = useI18n();
  return (
    <div className="language-switch" role="group" aria-label={t('language.switch')}>
      {(['en', 'ru'] as const).map((choice) => (
        <button key={choice} type="button" className={choice === language ? 'current' : ''}
          aria-pressed={choice === language} onClick={() => {
            setLanguage(choice);
            onChange?.(choice);
          }}>
          {choice.toUpperCase()}
        </button>
      ))}
    </div>
  );
}
