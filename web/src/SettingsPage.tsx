import { useState } from 'react';
import { type Account, type DigestFrequency, saveSettings } from './api';
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
 * четырёх), строка «письмо приходит в 8:00 по вашему времени на <почта>», язык интерфейса. Новый облик (§175, макет
 * §170): кнопки «Save» нет — выбор записывается в учётную запись сразу, язык экрана переключается сразу; язык — тем же
 * переключателем на выбор, что и частота; заголовок — как у остальных разделов. Часовой пояс браузера записывает
 * сверка при открытии приложения ({@code App}): пояс, которого не знает PostgreSQL, не мешает сохранить язык и
 * частоту (аудит §165).
 */
export function SettingsPage({ account, onSaved }: Props) {
  const { language, setLanguage, t } = useI18n();
  const [frequency, setFrequency] = useState<DigestFrequency>(account.digestFrequency);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  /**
   * Выбор записывается сразу (макет §170): язык экрана переключается сразу; ошибка — сообщением, частота на экране
   * возвращается к записанной в учётной записи.
   */
  async function save(change: { language?: Language; digestFrequency?: DigestFrequency }) {
    setBusy(true);
    setMessage(null);
    setSaved(false);
    try {
      const updated = await saveSettings(change);
      setLanguage(updated.language);
      setFrequency(updated.digestFrequency);
      onSaved(updated);
      setSaved(true);
    } catch (failure) {
      setFrequency(account.digestFrequency);
      setMessage(errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="settings">
      <h1>{t('settings.title')}</h1>
      <fieldset disabled={busy}>
        <legend>{t('settings.digest')}</legend>
        <div className="segmented" role="radiogroup" aria-label={t('settings.digest')}>
          {FREQUENCIES.map((item) => (
            <label key={item.value} className={item.value === frequency ? 'current' : ''}>
              <input type="radio" name="frequency" value={item.value} checked={item.value === frequency}
                onChange={() => {
                  setFrequency(item.value);
                  void save({ digestFrequency: item.value });
                }} />
              {t(item.label)}
            </label>
          ))}
        </div>
        <p className="muted">{t('settings.digestNote', { email: account.email })}</p>
      </fieldset>
      <fieldset disabled={busy}>
        <legend>{t('settings.language')}</legend>
        <div className="segmented" role="radiogroup" aria-label={t('settings.language')}>
          {LANGUAGES.map((item) => (
            <label key={item.value} className={item.value === language ? 'current' : ''}>
              <input type="radio" name="language" value={item.value} checked={item.value === language}
                onChange={() => void save({ language: item.value })} />
              {item.label}
            </label>
          ))}
        </div>
      </fieldset>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {saved && <p className="muted" role="status">{t('settings.saved')}</p>}
    </section>
  );
}
