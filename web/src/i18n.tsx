import { createContext, type ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { ApiError } from './api';

/** Язык интерфейса пользователя (бизнес-описание §6; стенограмма §162): английский — по умолчанию. */
export type Language = 'en' | 'ru';

/** Ключ выбранного языка в хранилище браузера: экран входа помнит выбор до регистрации и входа. */
const LANGUAGE_KEY = 'roleorienta.language';

/**
 * Тексты экранов пользователя по-английски. {@code {name}} — место подстановки значения. Экран администратора не
 * переводится (решение владельца 2026-10-21). Названия позиций не переводятся — как в словаре.
 */
const EN = {
  'app.tagline': 'Jobs from companies’ own career pages',
  'app.unavailable': 'Service unavailable: {detail}',
  'app.loading': 'Loading…',
  'nav.sections': 'Sections',
  'nav.search': 'Search',
  'nav.applications': 'My applications',
  'nav.notInterested': 'Not interested',
  'nav.companies': 'Companies',
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
  'error.tooMany': 'Too many requests today, try again tomorrow',
  'error.notFound': 'Not found',
  'settings.title': 'Settings',
  'settings.language': 'Language',
  'settings.save': 'Save',
  'settings.saved': 'Saved',
  'back.results': '← Back to results',
  'back.applications': '← Back to applications',
  'back.notInterested': '← Back to Not interested',
  'back.default': '← Back',
  'conditions.empty': 'No search criteria yet. Choose a country and a vacancy.',
  'conditions.country': 'Country',
  'conditions.countries': 'Countries',
  'conditions.position': 'Position',
  'conditions.positions': 'Positions',
  'conditions.format': 'Format',
  'conditions.workFormat': 'Work format',
  'conditions.edit': 'Edit',
  'conditions.lookFor': 'Vacancies to look for',
  'conditions.typeLetters': 'Type at least 2 letters',
  'conditions.remove': 'Remove {name}',
  'conditions.search': 'Search',
  'conditions.cancel': 'Cancel',
  'conditions.chooseBoth': 'Choose at least one country and a vacancy',
  'conditions.changedElsewhere': 'Search was changed in another window; reloaded, check and search again',
  'conditions.retired': '{name} (retired)',
  'format.any': 'Any',
  'format.office': 'Office',
  'format.remote': 'Remote',
  'suggest.link': 'Suggest a position',
  'suggest.field': 'Position and example vacancy titles',
  'suggest.placeholder': 'Rust developer — e.g. Senior Rust Engineer, Rust backend developer',
  'suggest.send': 'Send',
  'suggest.yours': 'Your suggestions',
  'suggest.onReview': 'on review',
  'suggest.added': 'added — {name}',
  'suggest.rejected': 'rejected',
  'suggest.rejectedBecause': 'rejected — {reason}',
  'list.found': 'Found {total} · new {fresh}',
  'list.company': 'Company',
  'list.position': 'Position',
  'list.country': 'Country',
  'list.opened': 'Opened',
  'list.status': 'Status',
  'list.marked': 'Marked',
  'list.new': 'New',
  'list.return': 'Return',
  'list.retry': 'Retry',
  'list.noEmployer': 'Employer not specified',
  'list.noResults': 'No results yet',
  'list.searching': 'Searching…',
  'list.nothingYet': 'No matching vacancies yet. New vacancies are added to the list every hour.',
  'list.emptyResponses': 'Vacancies you work with will appear here',
  'list.emptyNotInterested': 'Vacancies you mark as not interested will appear here',
  'pager.pages': 'Pages',
  'pager.previous': 'Previous page',
  'pager.next': 'Next page',
  'pager.perPage': 'Per page:',
  'pager.perPageLabel': 'Vacancies per page',
  'vacancy.employer': 'Employer',
  'vacancy.agency': 'Agency',
  'vacancy.status': 'Status',
  'vacancy.country': 'Country',
  'vacancy.format': 'Format',
  'vacancy.languages': 'Languages',
  'vacancy.opened': 'Opened',
  'vacancy.openPosting': 'Open the posting',
  'vacancy.history': 'History',
  'vacancy.notSpecified': 'not specified',
  'vacancy.closedByEmployer': 'closed by employer',
  'vacancy.found': 'found {date}',
  'vacancy.countryNotSpecified': 'Country not specified',
  'vacancy.anyCountry': 'any country',
  'vacancy.formatNotSpecified': 'Not specified',
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
  'status.APPLIED': 'CV sent',
  'status.INTERVIEW': 'Interview',
  'status.REJECTED': 'Rejected',
  'history.RECEIVED': 'Received',
  'history.VIEWED': 'Viewed',
  'history.NOT_INTERESTED': 'Not interested',
  'history.APPLIED': 'CV sent',
  'history.INTERVIEW': 'Interview',
  'history.REJECTED': 'Rejected',
  'history.RETURNED': 'Returned',
  'history.CLOSED': 'Closed by employer',
  'companies.title': 'Companies',
  'companies.query': 'Company name or IČO',
  'companies.search': 'Search',
  'companies.found': 'Found',
  'companies.noneFound': 'No companies found.',
  'companies.myAddresses': 'My career page addresses',
  'companies.noAddresses': 'You have not added any address yet.',
  'companies.address': 'Address',
  'companies.state': 'State',
  'companies.added': 'Added',
  'companies.refreshing': 'Addresses being checked are refreshed automatically.',
  'companies.doubtful': 'Doubtful companies',
  'companies.doubtfulNote': 'Companies with no vacancies collected: neither a readable career page nor vacancies on '
    + 'the state portal. Add a career page address — it is checked within minutes and used for all users.',
  'companies.noDoubtful': 'No doubtful companies.',
  'companies.more': 'More',
  'companies.hidden': 'Hidden companies',
  'companies.hiddenNote': 'Hidden only for you: not shown in search and doubtful companies. Other users still see '
    + 'them.',
  'companies.noHidden': 'You have not hidden any company.',
  'companies.showAgain': 'Show again',
  'companies.company': 'Company',
  'companies.group': 'Group',
  'companies.site': 'Site',
  'companies.careerPage': 'Career page',
  'companies.actions': 'Actions',
  'companies.addressOf': 'Career page address of {name}',
  'companies.add': 'Add',
  'companies.cancel': 'Cancel',
  'companies.addedNote': 'Added — see “My career page addresses”.',
  'companies.addAddress': 'Add address',
  'companies.hideOnlyForMe': 'Hide only for me',
  'companies.hide': 'Hide',
  'request.PENDING': 'checking…',
  'request.CONNECTED': 'connected',
  'request.NOT_CONNECTED': 'not connected',
  'reason.SITE_NOT_FOUND': 'site not found',
  'reason.PAGE_NOT_FOUND': 'career page not found',
  'reason.FORMAT_UNSUPPORTED': 'career page not readable',
  'reason.SOURCE_UNAVAILABLE': 'site did not answer',
  'reason.USE_FORBIDDEN': 'use not allowed',
  'reason.UNREACHABLE': 'page did not open',
  'reason.NO_CAREER_PAGE': 'not a career page',
  'group.YES': 'Career page read',
  'group.PORTAL': 'State portal vacancies only',
  'group.MAYBE': 'Site found, no readable career page',
  'group.NO': 'No site found',
  'group.NOT_EMPLOYER': 'Not an employer',
} as const;

/** Ключ текста экрана. */
export type MessageKey = keyof typeof EN;

/** Тексты по-русски: TypeScript требует перевод каждого ключа. */
const RU: Record<MessageKey, string> = {
  'app.tagline': 'Вакансии с кадровых страниц самих компаний',
  'app.unavailable': 'Сервис недоступен: {detail}',
  'app.loading': 'Загрузка…',
  'nav.sections': 'Разделы',
  'nav.search': 'Поиск',
  'nav.applications': 'Мои отклики',
  'nav.notInterested': 'Не интересно',
  'nav.companies': 'Компании',
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
  'error.tooMany': 'Слишком много запросов за сутки, попробуйте завтра',
  'error.notFound': 'Не найдено',
  'settings.title': 'Настройки',
  'settings.language': 'Язык',
  'settings.save': 'Сохранить',
  'settings.saved': 'Сохранено',
  'back.results': '← К результатам',
  'back.applications': '← К откликам',
  'back.notInterested': '← К «Не интересно»',
  'back.default': '← Назад',
  'conditions.empty': 'Критерии поиска не заданы. Выберите страну и вакансию.',
  'conditions.country': 'Страна',
  'conditions.countries': 'Страны',
  'conditions.position': 'Позиция',
  'conditions.positions': 'Позиции',
  'conditions.format': 'Формат',
  'conditions.workFormat': 'Формат работы',
  'conditions.edit': 'Изменить',
  'conditions.lookFor': 'Какие вакансии искать',
  'conditions.typeLetters': 'Введите хотя бы 2 буквы',
  'conditions.remove': 'Убрать {name}',
  'conditions.search': 'Искать',
  'conditions.cancel': 'Отмена',
  'conditions.chooseBoth': 'Выберите хотя бы одну страну и вакансию',
  'conditions.changedElsewhere': 'Условия изменены в другом окне; они перечитаны — проверьте и запустите поиск снова',
  'conditions.retired': '{name} (выключена)',
  'format.any': 'Любой',
  'format.office': 'Офис',
  'format.remote': 'Удалённо',
  'suggest.link': 'Предложить позицию',
  'suggest.field': 'Позиция и примеры названий вакансий',
  'suggest.placeholder': 'Rust developer — например, Senior Rust Engineer, Rust backend developer',
  'suggest.send': 'Отправить',
  'suggest.yours': 'Ваши предложения',
  'suggest.onReview': 'на рассмотрении',
  'suggest.added': 'добавлена — {name}',
  'suggest.rejected': 'отклонена',
  'suggest.rejectedBecause': 'отклонена — {reason}',
  'list.found': 'Найдено {total} · новых {fresh}',
  'list.company': 'Компания',
  'list.position': 'Позиция',
  'list.country': 'Страна',
  'list.opened': 'Открыта',
  'list.status': 'Статус',
  'list.marked': 'Отмечена',
  'list.new': 'Новая',
  'list.return': 'Вернуть',
  'list.retry': 'Повторить',
  'list.noEmployer': 'Работодатель не указан',
  'list.noResults': 'Результатов пока нет',
  'list.searching': 'Идёт поиск…',
  'list.nothingYet': 'Подходящих вакансий пока нет. Новые вакансии добавляются в список каждый час.',
  'list.emptyResponses': 'Здесь будут вакансии, с которыми вы работаете',
  'list.emptyNotInterested': 'Здесь будут вакансии, отмеченные «Не интересно»',
  'pager.pages': 'Страницы',
  'pager.previous': 'Предыдущая страница',
  'pager.next': 'Следующая страница',
  'pager.perPage': 'На странице:',
  'pager.perPageLabel': 'Вакансий на странице',
  'vacancy.employer': 'Работодатель',
  'vacancy.agency': 'Агентство',
  'vacancy.status': 'Статус',
  'vacancy.country': 'Страна',
  'vacancy.format': 'Формат',
  'vacancy.languages': 'Языки',
  'vacancy.opened': 'Открыта',
  'vacancy.openPosting': 'Открыть объявление',
  'vacancy.history': 'История',
  'vacancy.notSpecified': 'не указано',
  'vacancy.closedByEmployer': 'закрыта работодателем',
  'vacancy.found': 'найдена {date}',
  'vacancy.countryNotSpecified': 'Страна не указана',
  'vacancy.anyCountry': 'любая страна',
  'vacancy.formatNotSpecified': 'Не указан',
  'vacancy.onlyOffice': 'Только офис',
  'vacancy.onlyRemote': 'Только удалённо',
  'vacancy.hybrid': 'Смешанный',
  'vacancy.from': '{format} — из {territory}',
  'vacancy.required': 'обязателен',
  'vacancy.preferred': 'желателен',
  'vacancy.closed': 'закрыта',
  'status.NEW': 'Новая',
  'status.VIEWED': 'Просмотрена',
  'status.NOT_INTERESTED': 'Не интересно',
  'status.APPLIED': 'Резюме отправлено',
  'status.INTERVIEW': 'Собеседование',
  'status.REJECTED': 'Отказ',
  'history.RECEIVED': 'Получена',
  'history.VIEWED': 'Просмотрена',
  'history.NOT_INTERESTED': 'Не интересно',
  'history.APPLIED': 'Резюме отправлено',
  'history.INTERVIEW': 'Собеседование',
  'history.REJECTED': 'Отказ',
  'history.RETURNED': 'Возвращена',
  'history.CLOSED': 'Закрыта работодателем',
  'companies.title': 'Компании',
  'companies.query': 'Название компании или IČO',
  'companies.search': 'Искать',
  'companies.found': 'Найдено',
  'companies.noneFound': 'Компании не найдены.',
  'companies.myAddresses': 'Мои адреса кадровых страниц',
  'companies.noAddresses': 'Вы ещё не добавили ни одного адреса.',
  'companies.address': 'Адрес',
  'companies.state': 'Состояние',
  'companies.added': 'Добавлен',
  'companies.refreshing': 'Состояние адресов в проверке обновляется само.',
  'companies.doubtful': 'Сомнительные компании',
  'companies.doubtfulNote': 'Компании, у которых вакансии не собираются: нет ни читаемой кадровой страницы, ни '
    + 'вакансий на государственном портале. Добавьте адрес кадровой страницы — он проверяется в течение минут и '
    + 'используется для всех пользователей.',
  'companies.noDoubtful': 'Сомнительных компаний нет.',
  'companies.more': 'Ещё',
  'companies.hidden': 'Скрытые компании',
  'companies.hiddenNote': 'Скрыты только для вас: не показываются в поиске и среди сомнительных. Другие пользователи '
    + 'их видят.',
  'companies.noHidden': 'Вы не скрыли ни одной компании.',
  'companies.showAgain': 'Показывать снова',
  'companies.company': 'Компания',
  'companies.group': 'Группа',
  'companies.site': 'Сайт',
  'companies.careerPage': 'Кадровая страница',
  'companies.actions': 'Действия',
  'companies.addressOf': 'Адрес кадровой страницы: {name}',
  'companies.add': 'Добавить',
  'companies.cancel': 'Отмена',
  'companies.addedNote': 'Добавлен — см. «Мои адреса кадровых страниц».',
  'companies.addAddress': 'Добавить адрес',
  'companies.hideOnlyForMe': 'Скрыть только для меня',
  'companies.hide': 'Скрыть',
  'request.PENDING': 'проверяется…',
  'request.CONNECTED': 'подключена',
  'request.NOT_CONNECTED': 'не подключена',
  'reason.SITE_NOT_FOUND': 'сайт не найден',
  'reason.PAGE_NOT_FOUND': 'кадровая страница не найдена',
  'reason.FORMAT_UNSUPPORTED': 'кадровая страница не читается',
  'reason.SOURCE_UNAVAILABLE': 'сайт не ответил',
  'reason.USE_FORBIDDEN': 'использование запрещено',
  'reason.UNREACHABLE': 'страница не открылась',
  'reason.NO_CAREER_PAGE': 'не кадровая страница',
  'group.YES': 'Кадровая страница читается',
  'group.PORTAL': 'Только вакансии государственного портала',
  'group.MAYBE': 'Сайт найден, кадровая страница не читается',
  'group.NO': 'Сайт не найден',
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
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(storedLanguage);

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  const value = useMemo<I18n>(() => ({
    language,
    t: translator(language),
    locale: language,
    setLanguage: (next: Language) => {
      storeLanguage(next);
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
 * тексты сервера не переводятся): неверные поля — какие; 401 — неверная почта или пароль; 409 — почта занята
 * (единственный 409 на экранах пользователя, где ошибка показывается этим способом, — регистрация); 429 — лимит за
 * сутки; 404 — не найдено; остальное — текст ответа как есть.
 */
export function errorText(failure: unknown, t: Translate): string {
  if (failure instanceof ApiError) {
    if (failure.fields.length > 0) {
      return t('error.fields', { fields: failure.fields.map((field) => field.pointer.substring(1)).join(', ') });
    }
    if (failure.status === 401) {
      return t('error.signIn');
    }
    if (failure.status === 409) {
      return t('error.emailTaken');
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
