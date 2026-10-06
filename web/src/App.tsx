import { useEffect, useState } from 'react';
import { type Account, currentAccount, logout } from './api';
import { ConditionsPage } from './ConditionsPage';
import { LoginPage } from './LoginPage';

/**
 * Корень SPA: при загрузке спрашивает /api/v1/me — вошёл пользователь или нет. После входа — условия
 * поиска; накопленный список, сведения и отмеченные добавляются следующим срезом подэтапа 1.5.
 */
export function App() {
  const [account, setAccount] = useState<Account | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    currentAccount().then(setAccount, (failure: Error) => setError(failure.message));
  }, []);

  async function signOut() {
    await logout();
    setAccount(null);
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
        <ConditionsPage />
      </main>
    </>
  );
}
