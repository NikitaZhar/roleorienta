import { type FormEvent, useState } from 'react';
import { type Account, ApiError, login, register } from './api';
import { errorText, LanguageSwitch, useI18n } from './i18n';
import { Logo } from './Logo';

interface Props {
  onSignedIn: (account: Account) => void;
}

/** Размер эмблемы на экране входа, px. */
const LOGO_SIZE = 34;

/**
 * Вход и регистрация одной формой: регистрация — создание учётной записи и сразу вход. Экран — по согласованному
 * макету (технический документ §15, шаг 2): эмблема и название, строка о приложении, почта, пароль, «Sign in» —
 * главное действие, «Create account» — действие-ссылка; переключатель EN | RU в правом верхнем углу (§162): выбор
 * запоминается в браузере и записывается в учётную запись при регистрации. Ошибки — на языке экрана: неверные поля
 * (400), занятая почта (409), неверный пароль (401).
 */
export function LoginPage({ onSignedIn }: Props) {
  const { language, t } = useI18n();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(create: boolean) {
    setBusy(true);
    setMessage(null);
    try {
      if (create) {
        await register(email, password, language);
      }
      onSignedIn(await login(email, password));
    } catch (failure) {
      setMessage(failure instanceof ApiError && failure.status === 409 ? t('error.emailTaken')
        : errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <div className="login-language"><LanguageSwitch /></div>
      <div className="login-card">
        <h1><Logo size={LOGO_SIZE} />roleorienta</h1>
        <p className="tagline">{t('app.tagline')}</p>
        <form onSubmit={(event: FormEvent<HTMLFormElement>) => {
          event.preventDefault();
          submit(false);
        }}>
          <label>
            {t('login.email')}
            <input type="email" autoComplete="username" required value={email}
              onChange={(event) => setEmail(event.target.value)} />
          </label>
          <label>
            {t('login.password')}
            <input type="password" autoComplete="current-password" required minLength={8} value={password}
              onChange={(event) => setPassword(event.target.value)} />
          </label>
          {message !== null && <p className="error" role="alert">{message}</p>}
          <div className="actions">
            <button type="submit" className="primary" disabled={busy}>{t('login.signIn')}</button>
            <button type="button" className="link" disabled={busy} onClick={() => submit(true)}>
              {t('login.create')}
            </button>
          </div>
        </form>
      </div>
    </main>
  );
}
