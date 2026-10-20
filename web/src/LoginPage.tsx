import { type FormEvent, useState } from 'react';
import { type Account, ApiError, login, register } from './api';
import { Logo } from './Logo';

interface Props {
  onSignedIn: (account: Account) => void;
}

/** Размер эмблемы на экране входа, px. */
const LOGO_SIZE = 34;

/**
 * Вход и регистрация одной формой: регистрация — создание учётной записи и сразу вход. Экран — по согласованному
 * макету (технический документ §15, шаг 2): эмблема и название, строка о приложении, почта, пароль, «Sign in» —
 * главное действие, «Create account» — действие-ссылка. Переключатель EN | RU — со срезом языка (стенограмма §150).
 * Ошибки API показываются как есть: неверные поля (400), занятый email (409), неверный пароль (401).
 */
export function LoginPage({ onSignedIn }: Props) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(create: boolean) {
    setBusy(true);
    setMessage(null);
    try {
      if (create) {
        await register(email, password);
      }
      onSignedIn(await login(email, password));
    } catch (failure) {
      setMessage(failure instanceof ApiError && failure.fields.length > 0
        ? failure.fields.map((field) => `${field.pointer.substring(1)}: ${field.detail}`).join('; ')
        : (failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <div className="login-card">
        <h1><Logo size={LOGO_SIZE} />roleorienta</h1>
        <p className="tagline">Jobs from companies’ own career pages</p>
        <form onSubmit={(event: FormEvent<HTMLFormElement>) => {
          event.preventDefault();
          submit(false);
        }}>
          <label>
            Email
            <input type="email" autoComplete="username" required value={email}
              onChange={(event) => setEmail(event.target.value)} />
          </label>
          <label>
            Password
            <input type="password" autoComplete="current-password" required minLength={8} value={password}
              onChange={(event) => setPassword(event.target.value)} />
          </label>
          {message !== null && <p className="error" role="alert">{message}</p>}
          <div className="actions">
            <button type="submit" className="primary" disabled={busy}>Sign in</button>
            <button type="button" className="link" disabled={busy} onClick={() => submit(true)}>Create account</button>
          </div>
        </form>
      </div>
    </main>
  );
}
