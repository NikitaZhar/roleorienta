import { useEffect, useRef, useState } from 'react';
import { type Account, currentAccount, logout, responses, saveSettings, unsuitableVacancies, vacancies } from './api';
import { CompaniesPage } from './CompaniesPage';
import { ConditionsPage } from './ConditionsPage';
import { errorText, type Language, LanguageSwitch, type MessageKey, useI18n } from './i18n';
import { LoginPage } from './LoginPage';
import { Logo } from './Logo';
import { VacancyList } from './VacancyList';
import { SettingsPage } from './SettingsPage';
import { VacancyPage } from './VacancyPage';

type View = 'list' | 'applications' | 'marked' | 'companies' | 'settings';

/** Разделы верхней панели в порядке показа (бизнес-описание §6, решение владельца 2026-10-20). */
const SECTIONS: { view: View; label: MessageKey }[] = [
  { view: 'list', label: 'nav.search' },
  { view: 'applications', label: 'nav.applications' },
  { view: 'marked', label: 'nav.notInterested' },
  { view: 'companies', label: 'nav.companies' },
];

/** Подпись возврата из сведений о вакансии к разделу (§147). */
const BACK_LABELS: Record<View, MessageKey> = {
  list: 'back.results',
  applications: 'back.applications',
  marked: 'back.notInterested',
  companies: 'back.default',
  settings: 'back.default',
};

/** Размер эмблемы в панели, px. */
const LOGO_SIZE = 24;

/**
 * Корень SPA: при загрузке спрашивает /api/v1/me — вошёл пользователь или нет. После входа — панель с эмблемой и
 * четырьмя разделами (бизнес-описание §6): «Search» — условия поиска и под ними результаты; «My applications» —
 * вакансии с откликом (§156); «Not interested»; «Companies». Справа — меню учётной записи. Вакансия открывается
 * вместо раздела, а раздел не снимается, а скрывается ({@code hidden}): после «Back» видна та же страница списка и
 * те же условия, прокрутка — та же, что была до открытия (аудит §154). Если статус вакансии менялся (и «Viewed» при
 * открытии новой), возврат перечитывает ту же страницу ({@code reloads}): ушедшая в другой раздел вакансия пропадает,
 * плашка «New» снимается. Новые условия начинают список заново с первой страницы ({@code searches} — ключ списка).
 * Выход сбрасывает раздел и открытую вакансию. Язык интерфейса (§162): после входа — из учётной записи; переключатель
 * EN | RU в панели меняет его сразу и записывает в учётную запись; «Settings» в меню учётной записи — тоже.
 */
export function App() {
  const { setLanguage, t } = useI18n();
  const [account, setAccount] = useState<Account | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<View>('list');
  const [opened, setOpened] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [searches, setSearches] = useState(0);
  const [reloads, setReloads] = useState(0);
  const [fresh, setFresh] = useState(false);
  /** Меню учётной записи ({@code <details>}): закрывается при переходе в раздел и щелчке мимо (§163). */
  const menu = useRef<HTMLDetailsElement>(null);
  /** Прокрутка раздела в момент открытия вакансии: карточка короче списка, браузер прижал бы прокрутку. */
  const listScroll = useRef(0);

  useEffect(() => {
    currentAccount().then(signedIn, (failure: Error) => setError(failure.message));
  }, []);

  /** Учётная запись получена (загрузка или вход): язык экрана — из неё. */
  function signedIn(next: Account | null) {
    if (next !== null) {
      setLanguage(next.language);
    }
    setAccount(next);
  }

  /** Переключатель в панели: язык записывается в учётную запись; ошибка — сообщением, экран уже переключён. */
  function switchLanguage(language: Language) {
    saveSettings({ language }).catch((failure: unknown) => setNotice(errorText(failure, t)));
  }

  useEffect(() => {
    window.scrollTo(0, opened === null ? listScroll.current : 0);
  }, [opened]);

  useEffect(() => {
    function closeOutside(event: MouseEvent) {
      if (menu.current !== null && !menu.current.contains(event.target as Node)) {
        menu.current.open = false;
      }
    }
    document.addEventListener('click', closeOutside);
    return () => document.removeEventListener('click', closeOutside);
  }, []);

  function open(id: number) {
    listScroll.current = window.scrollY;
    setOpened(id);
  }

  async function signOut() {
    try {
      await logout();
      setNotice(null);
      setView('list');
      listScroll.current = 0;
      setOpened(null);
      setFresh(false);
      setAccount(null);
    } catch (failure) {
      setNotice(t('menu.signOutFailed', { detail: (failure as Error).message }));
    }
  }

  function show(next: View) {
    if (menu.current !== null) {
      menu.current.open = false;
    }
    setNotice(null);
    listScroll.current = 0;
    window.scrollTo(0, 0);
    setOpened(null);
    setFresh(false);
    setView(next);
  }

  /** Возврат из сведений; {@code changed} — статус менялся, список перечитывается. */
  function closeVacancy(changed: boolean) {
    setOpened(null);
    if (changed) {
      setFresh(false);
      setReloads((count) => count + 1);
    }
  }

  /** Условия сохранены; {@code changed} — создана новая версия условий (список начинается заново). */
  function searched(changed: boolean) {
    if (changed) {
      setFresh(true);
      setSearches((count) => count + 1);
    }
  }

  if (error !== null) {
    return <main className="page"><p className="error">{t('app.unavailable', { detail: error })}</p></main>;
  }
  if (account === undefined) {
    return <main className="page"><p>{t('app.loading')}</p></main>;
  }
  if (account === null) {
    return <LoginPage onSignedIn={signedIn} />;
  }
  return (
    <>
      <header className="bar">
        <span className="brand"><Logo size={LOGO_SIZE} />roleorienta</span>
        <nav aria-label={t('nav.sections')}>
          {SECTIONS.map((section) => (
            <button key={section.view} type="button" className={view === section.view ? 'current' : ''}
              aria-current={view === section.view ? 'page' : undefined} onClick={() => show(section.view)}>
              {t(section.label)}
            </button>
          ))}
        </nav>
        <LanguageSwitch onChange={switchLanguage} />
        <details className="account" ref={menu}>
          <summary>{account.email}</summary>
          <div className="menu">
            <button type="button" onClick={() => show('settings')}>{t('menu.settings')}</button>
            <button type="button" onClick={signOut}>{t('menu.signOut')}</button>
          </div>
        </details>
      </header>
      <main className="page">
        {notice !== null && <p className="error">{notice}</p>}
        {opened !== null && <VacancyPage id={opened} backLabel={t(BACK_LABELS[view])} onBack={closeVacancy} />}
        <div hidden={opened !== null}>
          {view === 'list' && (
            <>
              <ConditionsPage onSaved={searched} />
              <VacancyList key={searches} kind="results" load={vacancies} poll={fresh} reload={reloads}
                onOpen={open} />
            </>
          )}
          {view === 'applications' && (
            <VacancyList title={t('nav.applications')} kind="responses" load={responses} poll={false} reload={reloads}
              onOpen={open} />
          )}
          {view === 'marked' && (
            <VacancyList title={t('nav.notInterested')} kind="notInterested" load={unsuitableVacancies} poll={false}
              reload={reloads} onOpen={open} />
          )}
          {view === 'companies' && <CompaniesPage />}
          {view === 'settings' && <SettingsPage account={account} onSaved={setAccount} />}
        </div>
      </main>
    </>
  );
}
