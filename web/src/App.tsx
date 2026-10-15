import { useEffect, useState } from 'react';
import { type Account, currentAccount, logout, unsuitableVacancies, vacancies } from './api';
import { ConditionsPage } from './ConditionsPage';
import { LoginPage } from './LoginPage';
import { VacancyList } from './VacancyList';
import { VacancyPage } from './VacancyPage';

type View = 'list' | 'marked';

/**
 * Корень SPA: при загрузке спрашивает /api/v1/me — вошёл пользователь или нет. После входа — два раздела
 * (бизнес-описание §7.2): «Vacancies» — условия поиска и под ними накопленный список; «Not suitable» — отмеченные
 * «не подходит». Вакансия открывается поверх раздела; отметка или её снятие возвращает к разделу и перечитывает
 * список.
 */
export function App() {
  const [account, setAccount] = useState<Account | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<View>('list');
  const [opened, setOpened] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [searches, setSearches] = useState(0);

  useEffect(() => {
    currentAccount().then(setAccount, (failure: Error) => setError(failure.message));
  }, []);

  async function signOut() {
    try {
      await logout();
      setNotice(null);
      setAccount(null);
    } catch (failure) {
      setNotice('Sign out failed: ' + (failure as Error).message);
    }
  }

  function show(next: View) {
    setNotice(null);
    setOpened(null);
    setView(next);
  }

  function marked() {
    setOpened(null);
    setSearches((count) => count + 1);
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
        <span className="brand">roleorienta</span>
        <nav>
          <button type="button" className={view === 'list' ? 'current' : ''} onClick={() => show('list')}>Vacancies</button>
          <button type="button" className={view === 'marked' ? 'current' : ''} onClick={() => show('marked')}>Not suitable</button>
        </nav>
        <span className="who">{account.email}</span>
        <button type="button" onClick={signOut}>Sign out</button>
      </header>
      <main className="page">
        {notice !== null && <p className="error">{notice}</p>}
        {opened !== null && <VacancyPage id={opened} onBack={() => setOpened(null)} onMarked={marked} />}
        {opened === null && view === 'list' && (
          <>
            <ConditionsPage onSaved={() => setSearches((count) => count + 1)} />
            <VacancyList key={searches} title="Vacancies" load={vacancies} marked={false} onOpen={setOpened} />
          </>
        )}
        {opened === null && view === 'marked' && (
          <VacancyList key={searches} title="Not suitable" load={unsuitableVacancies} marked onOpen={setOpened} />
        )}
      </main>
    </>
  );
}
