import { useEffect, useState } from 'react';
import {
  adminCareerRequests, type CareerRequest, closeSource, type UnlinkedSource, unlinkedSources,
} from './api';

/** Состояние проверки адреса, введённого пользователем. */
const REQUEST_STATES: Record<CareerRequest['status']['state'], string> = {
  PENDING: 'checking',
  CONNECTED: 'connected',
  NOT_CONNECTED: 'not connected',
};

/**
 * Кадровые страницы, добавленные пользователями (§206): компания, адрес, состояние проверки и причина неудачи.
 * Только просмотр — проверку делает ядро.
 */
export function CareerRequestsAdmin() {
  const [items, setItems] = useState<CareerRequest[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    adminCareerRequests().then(setItems, (failure: Error) => setMessage(failure.message));
  }, []);

  return (
    <section>
      <h2>Added by users</h2>
      <p className="muted">Career page addresses that users entered, and what the check found.</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {items !== null && (items.length === 0 ? <p className="muted">Nothing yet.</p> : (
        <div className="scroll">
          <table className="companies">
            <thead><tr><th>Company</th><th>Address</th><th>State</th><th>Added</th></tr></thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td>{item.companyName}</td>
                  <td><a href={item.url} target="_blank" rel="noreferrer">{item.url}</a></td>
                  <td className="nowrap">{REQUEST_STATES[item.status.state]}
                    {item.status.reason !== null && <span className="muted"> · {item.status.reason}</span>}</td>
                  <td className="nowrap">{new Date(item.status.createdAt).toLocaleDateString('en-GB')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}

/**
 * Источники вакансий без компании (§206): адрес, число открытых публикаций и первые названия — видно, вакансии ли
 * это (блог, товары). «Close», затем «Confirm close» (действие не отменяется, аудит §211) — источник больше не
 * читается, его публикации и вакансии без других публикаций закрываются; настоящие вакансии не трогать — лучше лишние,
 * чем потерянные (принцип владельца).
 */
export function UnlinkedSourcesAdmin() {
  const [items, setItems] = useState<UnlinkedSource[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Источник, у которого нажата «Close»: второе нажатие («Confirm close») закрывает — действие не отменяется. */
  const [confirming, setConfirming] = useState<number | null>(null);

  useEffect(() => {
    unlinkedSources().then(setItems, (failure: Error) => setMessage(failure.message));
  }, []);

  async function close(item: UnlinkedSource) {
    setBusy(true);
    setMessage(null);
    try {
      await closeSource(item.id);
      setItems((current) => current?.filter((other) => other.id !== item.id) ?? null);
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>Sources without company</h2>
      <p className="muted">Career pages found on sites but not linked to any company. If the titles are not vacancies
        (a blog, products), press Close: the page is no longer read and its vacancies are closed. Keep real
        vacancies.</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {items !== null && (items.length === 0 ? <p className="muted">None.</p> : (
        <div className="scroll">
          <table className="companies">
            <thead>
              <tr>
                <th>Page</th><th className="nowrap">Open</th><th>First titles</th>
                <th><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td><a href={item.board.startsWith('http') ? item.board : 'https://' + item.board} target="_blank"
                    rel="noreferrer">{item.board}</a></td>
                  <td className="nowrap">{item.openPostings}</td>
                  <td>{item.titles.join(' · ')}</td>
                  <td className="row-actions nowrap">
                    {confirming === item.id ? (
                      <>
                        <button type="button" className="danger" disabled={busy} onClick={() => close(item)}>
                          Confirm close
                        </button>
                        <button type="button" disabled={busy} onClick={() => setConfirming(null)}>Cancel</button>
                      </>
                    ) : (
                      <button type="button" disabled={busy} onClick={() => setConfirming(item.id)}>Close</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}
