import { useEffect, useRef, useState } from 'react';
import { type Account, currentAccount, logout, responses, unsuitableVacancies, vacancies } from './api';
import { CompaniesPage } from './CompaniesPage';
import { ConditionsPage } from './ConditionsPage';
import { LoginPage } from './LoginPage';
import { Logo } from './Logo';
import { VacancyList } from './VacancyList';
import { VacancyPage } from './VacancyPage';

type View = 'list' | 'applications' | 'marked' | 'companies';

/** Разделы верхней панели в порядке показа (бизнес-описание §6, решение владельца 2026-10-20). */
const SECTIONS: { view: View; label: string }[] = [
  { view: 'list', label: 'Search' },
  { view: 'applications', label: 'My applications' },
  { view: 'marked', label: 'Not interested' },
  { view: 'companies', label: 'Companies' },
];

/** Подпись возврата из сведений о вакансии к разделу (§147). */
const BACK_LABELS: Record<View, string> = {
  list: '← Back to results',
  applications: '← Back to applications',
  marked: '← Back to Not interested',
  companies: '← Back',
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
 * Выход сбрасывает раздел и открытую вакансию.
 */
export function App() {
  const [account, setAccount] = useState<Account | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<View>('list');
  const [opened, setOpened] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [searches, setSearches] = useState(0);
  const [reloads, setReloads] = useState(0);
  const [fresh, setFresh] = useState(false);
  /** Прокрутка раздела в момент открытия вакансии: карточка короче списка, браузер прижал бы прокрутку. */
  const listScroll = useRef(0);

  useEffect(() => {
    currentAccount().then(setAccount, (failure: Error) => setError(failure.message));
  }, []);

  useEffect(() => {
    window.scrollTo(0, opened === null ? listScroll.current : 0);
  }, [opened]);

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
      setNotice('Sign out failed: ' + (failure as Error).message);
    }
  }

  function show(next: View) {
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
    return <main className="page"><p className="error">Service unavailable: {error}</p></main>;
  }
  if (account === undefined) {
    return <main className="page"><p>Loading…</p></main>;
  }
  if (account === null) {
    return <LoginPage onSignedIn={setAccount} />;
  }
  return (
    <>
      <header className="bar">
        <span className="brand"><Logo size={LOGO_SIZE} />roleorienta</span>
        <nav aria-label="Sections">
          {SECTIONS.map((section) => (
            <button key={section.view} type="button" className={view === section.view ? 'current' : ''}
              aria-current={view === section.view ? 'page' : undefined} onClick={() => show(section.view)}>
              {section.label}
            </button>
          ))}
        </nav>
        <details className="account">
          <summary>{account.email}</summary>
          <div className="menu">
            <button type="button" onClick={signOut}>Sign out</button>
          </div>
        </details>
      </header>
      <main className="page">
        {notice !== null && <p className="error">{notice}</p>}
        {opened !== null && <VacancyPage id={opened} backLabel={BACK_LABELS[view]} onBack={closeVacancy} />}
        <div hidden={opened !== null}>
          {view === 'list' && (
            <>
              <ConditionsPage onSaved={searched} />
              <VacancyList key={searches} kind="results" load={vacancies} poll={fresh} reload={reloads}
                onOpen={open} />
            </>
          )}
          {view === 'applications' && (
            <VacancyList title="My applications" kind="responses" load={responses} poll={false} reload={reloads}
              onOpen={open} />
          )}
          {view === 'marked' && (
            <VacancyList title="Not interested" kind="notInterested" load={unsuitableVacancies} poll={false}
              reload={reloads} onOpen={open} />
          )}
          {view === 'companies' && <CompaniesPage />}
        </div>
      </main>
    </>
  );
}
