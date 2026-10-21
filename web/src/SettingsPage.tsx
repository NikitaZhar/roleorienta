import { type FormEvent, useState } from 'react';
import { saveSettings } from './api';
import { errorText, type Language, useI18n } from './i18n';

/** Языки на выбор: подпись — на самом языке, чтобы её узнал тот, кто им владеет. */
const LANGUAGES: { value: Language; label: string }[] = [
  { value: 'en', label: 'English' },
  { value: 'ru', label: 'Русский' },
];

/**
 * «Settings» из меню учётной записи (макет §147; стенограмма §162): сейчас — язык интерфейса; частота сводки на почту
 * и часовой пояс — в шаге 8. «Save» записывает выбор в учётную запись и сразу переключает экран.
 */
export function SettingsPage() {
  const { language, setLanguage, t } = useI18n();
  const [choice, setChoice] = useState<Language>(language);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const account = await saveSettings(choice);
      setLanguage(account.language);
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
