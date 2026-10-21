import { type KeyboardEvent, useEffect, useState } from 'react';
import { ownSuggestions, type Suggestion, suggestPosition } from './api';
import { errorText, type Translate, useI18n } from './i18n';

/** Наибольшая длина предложения (как на сервере). */
const MAX_TEXT = 500;

/** Состояние предложения для пользователя: на рассмотрении, добавлена — какая позиция, отклонена — причина. */
export function suggestionStateText(suggestion: Suggestion, t: Translate): string {
  const { decision } = suggestion;
  if (decision.state === 'ADDED') {
    return t('suggest.added', { name: decision.positionName ?? decision.positionCode ?? '' });
  }
  if (decision.state === 'REJECTED') {
    return decision.reason === null ? t('suggest.rejected') : t('suggest.rejectedBecause', { reason: decision.reason });
  }
  return t('suggest.onReview');
}

/** Пилюля состояния предложения: цвет дополняет текст. */
const STATE_CLASS: Record<Suggestion['decision']['state'], string> = {
  PENDING: 'pill',
  ADDED: 'pill pill-fresh',
  REJECTED: 'pill pill-muted',
};

interface Props {
  onClose: () => void;
}

/**
 * «Suggest a position» (бизнес-описание §3, §161; новый облик — §171): окно поверх экрана — нужной позиции нет в
 * словаре, пользователь пишет её название и примеры названий вакансий; ниже — свои предложения с состоянием пилюлей.
 * Список читается при каждом открытии: решение администратора видно без перезагрузки страницы. Добавленную позицию
 * пользователь выбирает в условиях обычным поиском. Закрывается «×», Escape и щелчком по затемнению.
 */
export function PositionSuggestions({ onClose }: Props) {
  const { t } = useI18n();
  const [text, setText] = useState('');
  const [items, setItems] = useState<Suggestion[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    ownSuggestions().then(setItems, (failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  async function send() {
    if (text.trim() === '') {
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const added = await suggestPosition(text.trim());
      setItems((current) => [added, ...(current ?? [])]);
      setText('');
    } catch (failure) {
      setMessage(errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  function onKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      onClose();
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}
      onKeyDown={onKey}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="suggest-title">
        <div className="modal-head">
          <h2 id="suggest-title">{t('suggest.link')}</h2>
          <button type="button" className="icon" aria-label={t('suggest.close')} onClick={onClose}>×</button>
        </div>
        <label>
          {t('suggest.field')}
          <textarea rows={3} autoFocus maxLength={MAX_TEXT} value={text}
            onChange={(event) => setText(event.target.value)} placeholder={t('suggest.placeholder')} />
        </label>
        <div className="actions">
          <button type="button" className="primary" disabled={busy || text.trim() === ''} onClick={send}>
            {t('suggest.send')}
          </button>
        </div>
        {message !== null && <p className="error" role="alert">{message}</p>}
        {items !== null && items.length > 0 && (
          <section className="suggestions">
            <h3>{t('suggest.yours')}</h3>
            <ul>
              {items.map((item) => (
                <li key={item.id}>
                  <span>{item.text}</span>
                  <span className={STATE_CLASS[item.decision.state]}>{suggestionStateText(item, t)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  );
}
