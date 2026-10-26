import { useState } from 'react';
import { type Account, type DigestFrequency, saveSettings } from './api';
import { errorText, type MessageKey, useI18n } from './i18n';

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
 * четырёх), строка «письмо приходит в 8:00 по вашему времени на <почта>». Язык интерфейса — только переключателем EN |
 * RU в панели (§200, аудит §197: два места для одного выбора). Новый облик (§175, макет §170): кнопки «Save» нет — выбор
 * записывается в учётную запись сразу; заголовок — как у остальных разделов. Часовой пояс браузера записывает сверка
 * при открытии приложения ({@code App}): пояс, которого не знает PostgreSQL, не мешает сохранить частоту (аудит
 * §165).
 */
export function SettingsPage({ account, onSaved }: Props) {
  const { t } = useI18n();
  const [frequency, setFrequency] = useState<DigestFrequency>(account.digestFrequency);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  /**
   * Выбор записывается сразу (макет §170); язык экрана не трогается (аудит §182: язык из панели мог не записаться в
   * учётную запись — экран не переключается назад); ошибка — сообщением, частота на экране возвращается к записанной
   * в учётной записи.
   */
  async function save(digestFrequency: DigestFrequency) {
    setBusy(true);
    setMessage(null);
    setSaved(false);
    try {
      const updated = await saveSettings({ digestFrequency });
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
                  void save(item.value);
                }} />
              {t(item.label)}
            </label>
          ))}
        </div>
        <p className="muted">{t('settings.digestNote', { email: account.email })}</p>
      </fieldset>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {saved && <p className="muted" role="status">{t('settings.saved')}</p>}
    </section>
  );
}
