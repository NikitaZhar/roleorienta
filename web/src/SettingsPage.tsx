import { type FormEvent, useState } from 'react';
import { type Account, browserTimeZone, type DigestFrequency, saveSettings } from './api';
import { errorText, type Language, type MessageKey, useI18n } from './i18n';

/** Языки на выбор: подпись — на самом языке, чтобы её узнал тот, кто им владеет. */
const LANGUAGES: { value: Language; label: string }[] = [
  { value: 'en', label: 'English' },
  { value: 'ru', label: 'Русский' },
];

/** Частота сводки на почту (бизнес-описание §6, макет §147, §164): переключатель из четырёх. */
const FREQUENCIES: { value: DigestFrequency; label: MessageKey }[] = [
  { value: 'DAILY', label: 'settings.daily' },
  { value: 'WEEKDAYS', label: 'settings.weekdays' },
  { value: 'WEEKLY', label: 'settings.weekly' },
  { value: 'NEVER', label: 'settings.never' },
];

interface Props {
  account: Account;
  /** Настройки сохранены: учётная запись с новыми значениями. */
  onSaved: (account: Account) => void;
}

/**
 * «Settings» из меню учётной записи (макет §147; стенограмма §162, §164): частота сводки на почту (переключатель из
 * четырёх), строка «письмо приходит в 8:00 по вашему времени на <почта>», язык интерфейса. «Save» записывает выбор и
 * часовой пояс браузера в учётную запись и сразу переключает язык экрана.
 */
export function SettingsPage({ account, onSaved }: Props) {
  const { language, setLanguage, t } = useI18n();
  const [choice, setChoice] = useState<Language>(language);
  const [frequency, setFrequency] = useState<DigestFrequency>(account.digestFrequency);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const updated = await saveSettings({ language: choice, digestFrequency: frequency, timeZone: browserTimeZone() });
      setLanguage(updated.language);
      onSaved(updated);
      setSaved(true);
    } catch (failure) {
      setMessage(errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>{t('settings.title')}</h2>
      <form className="settings" onSubmit={save}>
        <fieldset>
          <legend>{t('settings.digest')}</legend>
          <div className="segmented" role="radiogroup" aria-label={t('settings.digest')}>
            {FREQUENCIES.map((item) => (
              <label key={item.value} className={item.value === frequency ? 'current' : ''}>
                <input type="radio" name="frequency" value={item.value} checked={item.value === frequency}
                  onChange={() => {
                    setFrequency(item.value);
                    setSaved(false);
                  }} />
                {t(item.label)}
              </label>
            ))}
          </div>
          <p className="muted">{t('settings.digestNote', { email: account.email })}</p>
        </fieldset>
        <label>
          {t('settings.language')}
          <select value={choice} onChange={(event) => {
            setChoice(event.target.value as Language);
            setSaved(false);
          }}>
            {LANGUAGES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
        </label>
        {message !== null && <p className="error" role="alert">{message}</p>}
        <div className="actions">
          <button type="submit" className="primary" disabled={busy}>{t('settings.save')}</button>
          {saved && <span className="muted" role="status">{t('settings.saved')}</span>}
        </div>
      </form>
    </section>
  );
}
