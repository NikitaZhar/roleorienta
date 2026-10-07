import { useEffect, useState } from 'react';
import { type Account, currentAccount, logout } from './api';
import { ConditionsPage } from './ConditionsPage';
import { LoginPage } from './LoginPage';
import { VacancyList } from './VacancyList';
import { VacancyPage } from './VacancyPage';

/**
 * Корень SPA: при загрузке спрашивает /api/v1/me — вошёл пользователь или нет. После входа — один экран: страна и
 * искомая вакансия, под ними накопленный список; вакансия открывается поверх него (бизнес-описание §7.2; на этапе
 * стабилизации ядра — только это, решение владельца 2026-10-07).
 */
export function App() {
  const [account, setAccount] = useState<Account | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
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
        <span className="who">{account.email}</span>
        <button type="button" onClick={signOut}>Sign out</button>
      </header>
      <main className="page">
        {notice !== null && <p className="error">{notice}</p>}
        {opened !== null && <VacancyPage id={opened} onBack={() => setOpened(null)} />}
        {opened === null && (
          <>
            <ConditionsPage onSaved={() => setSearches((count) => count + 1)} />
            <VacancyList key={searches} onOpen={setOpened} />
          </>
        )}
      </main>
    </>
  );
}
