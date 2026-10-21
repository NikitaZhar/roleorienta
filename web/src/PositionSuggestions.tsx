import { useEffect, useState } from 'react';
import { ownSuggestions, type Suggestion, suggestPosition } from './api';

/** Наибольшая длина предложения (как на сервере). */
const MAX_TEXT = 500;

/** Состояние предложения для пользователя: на рассмотрении, добавлена — какая позиция, отклонена — причина. */
export function suggestionStateText(suggestion: Suggestion): string {
  const { decision } = suggestion;
  if (decision.state === 'ADDED') {
    return 'added — ' + (decision.positionName ?? decision.positionCode ?? '');
  }
  if (decision.state === 'REJECTED') {
    return decision.reason === null ? 'rejected' : 'rejected — ' + decision.reason;
  }
  return 'on review';
}

/**
 * «Suggest a position» в форме условий (бизнес-описание §3, макет §147, §161): нужной позиции нет в словаре —
 * пользователь пишет её название и примеры названий вакансий; ниже — свои предложения с состоянием. Добавленную
 * позицию пользователь выбирает в условиях обычным поиском. Не форма (форма условий вокруг): отправка — кнопкой.
 * Видно в раскрытой форме условий: при свёрнутых условиях — «Edit», затем «Suggest a position».
 */
export function PositionSuggestions() {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [items, setItems] = useState<Suggestion[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** Список перечитывается при каждом раскрытии: решение администратора видно без перезагрузки страницы. */
  useEffect(() => {
    if (open) {
      ownSuggestions().then(setItems, (failure: Error) => setMessage(failure.message));
    }
  }, [open]);

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
      setMessage((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="suggest">
      <button type="button" className="link" aria-expanded={open} onClick={() => setOpen(!open)}>
        Suggest a position
      </button>
      {open && (
        <>
          <label>
            Position and example vacancy titles
            <textarea rows={2} maxLength={MAX_TEXT} value={text} onChange={(event) => setText(event.target.value)}
              placeholder="Rust developer — e.g. Senior Rust Engineer, Rust backend developer" />
          </label>
          <div className="actions">
            <button type="button" disabled={busy || text.trim() === ''} onClick={send}>Send</button>
          </div>
          {message !== null && <p className="error" role="alert">{message}</p>}
          {items !== null && items.length > 0 && (
            <>
              <p className="muted">Your suggestions</p>
              <ul className="suggestions">
                {items.map((item) => (
                  <li key={item.id}>{item.text} <span className="muted">· {suggestionStateText(item)}</span></li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
    </div>
  );
}
