import { useEffect, useState } from 'react';
import { type AdminSuggestion, adminSuggestions, decideSuggestion, dictionary, type DictionaryPosition } from './api';
import { useI18n } from './i18n';
import { suggestionStateText } from './PositionSuggestions';

/**
 * Предложения позиций для администратора (§161), в нынешнем виде страницы /admin: на рассмотрении — первыми, их число —
 * в заголовке. Решение в строке: позиция словаря и «Add» (новую позицию сначала заводят в «Position dictionary» —
 * список позиций перечитывается кнопкой «Refresh») или причина (необязательна) и «Reject». Решение можно изменить.
 */
export function SuggestionsAdmin() {
  const [items, setItems] = useState<AdminSuggestion[] | null>(null);
  const [positions, setPositions] = useState<DictionaryPosition[]>([]);
  const [message, setMessage] = useState<string | null>(null);

  async function load() {
    setMessage(null);
    try {
      const [suggestions, words] = await Promise.all([adminSuggestions(), dictionary()]);
      setItems(suggestions);
      setPositions(words.positions);
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function decide(id: number, decision: 'ADDED' | 'REJECTED', value: string) {
    setMessage(null);
    try {
      await decideSuggestion(id, decision, value);
      setItems(await adminSuggestions());
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  const pending = items?.filter((item) => item.suggestion.decision.state === 'PENDING').length ?? 0;
  return (
    <section>
      <h2>Position suggestions{pending > 0 && ` (${pending} on review)`}</h2>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {items !== null && (items.length === 0 ? <p className="muted">No suggestions yet.</p> : (
        <div className="scroll">
          <table className="companies">
            <thead>
              <tr><th>Date</th><th>User</th><th>Suggestion</th><th>State</th><th>Decision</th></tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <SuggestionRow key={item.suggestion.id} item={item} positions={positions} onDecide={decide} />
              ))}
            </tbody>
          </table>
        </div>
      ))}
      <button type="button" onClick={load}>Refresh</button>
    </section>
  );
}

function SuggestionRow({ item, positions, onDecide }: {
  item: AdminSuggestion;
  positions: DictionaryPosition[];
  onDecide: (id: number, decision: 'ADDED' | 'REJECTED', value: string) => void;
}) {
  const { suggestion } = item;
  const { t } = useI18n();
  const [position, setPosition] = useState(suggestion.decision.positionCode ?? '');
  const [reason, setReason] = useState(suggestion.decision.reason ?? '');

  return (
    <tr>
      <td>{new Date(suggestion.createdAt).toLocaleDateString()}</td>
      <td>{item.email}</td>
      <td>{suggestion.text}</td>
      <td>{suggestionStateText(suggestion, t)}</td>
      <td className="row-actions">
        <select aria-label="Position" value={position} onChange={(event) => setPosition(event.target.value)}>
          <option value="">Choose a position…</option>
          {positions.map((option) => <option key={option.code} value={option.code}>{option.name}</option>)}
        </select>
        <button type="button" disabled={position === ''} onClick={() => onDecide(suggestion.id, 'ADDED', position)}>
          Add
        </button>
        <input aria-label="Reason" placeholder="Reason (optional)" value={reason}
          onChange={(event) => setReason(event.target.value)} />
        <button type="button" onClick={() => onDecide(suggestion.id, 'REJECTED', reason)}>Reject</button>
      </td>
    </tr>
  );
}
