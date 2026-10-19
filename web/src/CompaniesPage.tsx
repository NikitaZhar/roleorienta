import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  addCareerPage, ApiError, type CareerRequest, careerRequests, type CompanyItem, doubtfulCompanies, hiddenCompanies,
  searchCompanies, setCompanyHidden,
} from './api';
import { GroupBadge } from './companyGroups';

/** Подписи причин итога проверки и запроса. */
const REASONS: Record<string, string> = {
  SITE_NOT_FOUND: 'site not found',
  PAGE_NOT_FOUND: 'career page not found',
  FORMAT_UNSUPPORTED: 'career page not readable',
  SOURCE_UNAVAILABLE: 'site did not answer',
  USE_FORBIDDEN: 'use not allowed',
  UNREACHABLE: 'page did not open',
  NO_CAREER_PAGE: 'not a career page',
};

/** Подписи состояния запроса. */
const STATES: Record<string, string> = {
  PENDING: 'checking…',
  CONNECTED: 'connected',
  NOT_CONNECTED: 'not connected',
};

/** Пока есть адреса «в проверке», их состояние обновляется само с таким промежутком. */
const REQUEST_POLL_MS = 15000;

/** Текст ошибки: первая ошибка поля или текст ответа. */
function problem(failure: unknown): string {
  return failure instanceof ApiError && failure.fields.length > 0
    ? failure.fields[0].detail : (failure as Error).message;
}

/**
 * Компании (бизнес-описание §4.2, §5; §137, §138, §143): поиск по названию или IČO, сомнительные компании (без
 * вакансий: «может быть» и «нет» с признаком найма) с причиной, сайтом и адресом; у компании без своей читаемой
 * кадровой страницы (и у «только портал») — ввод её адреса по кнопке (адрес общий для всех пользователей, проверяется
 * в течение минут); свои адреса и их состояние — обновляются сами, пока идёт проверка. Личное «скрыть»: компания
 * пропадает из поиска и сомнительных только у этого пользователя, «Show again» возвращает её в списки.
 */
export function CompaniesPage() {
  const [query, setQuery] = useState('');
  const [searched, setSearched] = useState<string | null>(null);
  const [found, setFound] = useState<CompanyItem[] | null>(null);
  const [doubtful, setDoubtful] = useState<CompanyItem[] | null>(null);
  const [next, setNext] = useState<number | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [requests, setRequests] = useState<CareerRequest[]>([]);
  const [hidden, setHidden] = useState<CompanyItem[]>([]);
  const [message, setMessage] = useState<string | null>(null);

  const loadRequests = useCallback(() => {
    careerRequests().then(setRequests, (failure: Error) => setMessage(failure.message));
  }, []);

  const loadHidden = useCallback(() => {
    hiddenCompanies().then(setHidden, (failure: Error) => setMessage(failure.message));
  }, []);

  const loadDoubtful = useCallback(() => {
    doubtfulCompanies(null).then((page) => {
      setDoubtful(page.items);
      setNext(page.next);
    }, (failure: Error) => setMessage(failure.message));
  }, []);

  useEffect(() => {
    loadDoubtful();
    loadRequests();
    loadHidden();
  }, [loadDoubtful, loadRequests, loadHidden]);

  const checking = requests.some((item) => item.status.state === 'PENDING');
  useEffect(() => {
    if (!checking) {
      return undefined;
    }
    const timer = window.setInterval(loadRequests, REQUEST_POLL_MS);
    return () => window.clearInterval(timer);
  }, [checking, loadRequests]);

  async function runSearch(text: string) {
    setMessage(null);
    try {
      setFound(await searchCompanies(text));
      setSearched(text);
    } catch (failure) {
      setMessage(problem(failure));
    }
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void runSearch(query);
  }

  async function more() {
    if (next === null || loadingMore) {
      return;
    }
    setLoadingMore(true);
    setMessage(null);
    try {
      const page = await doubtfulCompanies(next);
      setDoubtful((current) => [...(current ?? []), ...page.items]);
      setNext(page.next);
    } catch (failure) {
      setMessage(problem(failure));
    } finally {
      setLoadingMore(false);
    }
  }

  async function hide(item: CompanyItem) {
    setMessage(null);
    try {
      await setCompanyHidden(item.id, true);
      setFound((current) => current === null ? null : current.filter((other) => other.id !== item.id));
      setDoubtful((current) => current === null ? null : current.filter((other) => other.id !== item.id));
      loadHidden();
    } catch (failure) {
      setMessage(problem(failure));
    }
  }

  async function unhide(item: CompanyItem) {
    setMessage(null);
    try {
      await setCompanyHidden(item.id, false);
      setHidden((current) => current.filter((other) => other.id !== item.id));
      loadDoubtful();
      if (searched !== null) {
        await runSearch(searched);
      }
    } catch (failure) {
      setMessage(problem(failure));
    }
  }

  function added() {
    setMessage(null);
    loadRequests();
  }

  return (
    <>
      <h1>Companies</h1>
      {message !== null && <p className="error" role="alert">{message}</p>}
      <form className="inline-form" onSubmit={search} role="search">
        <input aria-label="Company name or IČO" placeholder="Company name or IČO" value={query}
          onChange={(event) => setQuery(event.target.value)} />
        <button type="submit">Search</button>
      </form>
      {found !== null && (
        <section>
          <h2>Found</h2>
          {found.length === 0 ? <p className="muted">No companies found.</p>
            : <CompanyTable items={found} onAdded={added} onHide={hide} />}
        </section>
      )}
      <section>
        <h2>My career page addresses</h2>
        {requests.length === 0 ? <p className="muted">You have not added any address yet.</p> : (
          <table className="companies">
            <thead>
              <tr><th>Company</th><th>Address</th><th>State</th><th>Added</th></tr>
            </thead>
            <tbody>
              {requests.map((item) => (
                <tr key={item.id}>
                  <td>{item.companyName}</td>
                  <td><a href={item.url} target="_blank" rel="noreferrer">{item.url}</a></td>
                  <td>{STATES[item.status.state]}{item.status.reason !== null
                    && ': ' + (REASONS[item.status.reason] ?? item.status.reason)}</td>
                  <td className="nowrap">{new Date(item.status.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {checking && <p className="muted">Addresses being checked are refreshed automatically.</p>}
      </section>
      <section>
        <h2>Doubtful companies</h2>
        <p className="muted">Companies with no vacancies collected: neither a readable career page nor vacancies on
          the state portal. Add a career page address — it is checked within minutes and used for all users.</p>
        {doubtful === null ? <p className="muted">Loading…</p>
          : doubtful.length === 0 ? <p className="muted">No doubtful companies.</p>
            : <CompanyTable items={doubtful} onAdded={added} onHide={hide} />}
        {next !== null && (
          <button type="button" onClick={more} disabled={loadingMore}>{loadingMore ? 'Loading…' : 'More'}</button>
        )}
      </section>
      <section>
        <h2>Hidden companies</h2>
        <p className="muted">Hidden only for you: not shown in search and doubtful companies. Other users still see
          them.</p>
        {hidden.length === 0 ? <p className="muted">You have not hidden any company.</p> : (
          <table className="companies">
            <tbody>
              {hidden.map((item) => (
                <tr key={item.id}>
                  <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
                  <td><GroupBadge item={item} /></td>
                  <td className="row-actions">
                    <button type="button" onClick={() => unhide(item)}>Show again</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

interface TableProps {
  items: CompanyItem[];
  onAdded: () => void;
  onHide: (item: CompanyItem) => void;
}

function CompanyTable({ items, onAdded, onHide }: TableProps) {
  return (
    <table className="companies">
      <thead>
        <tr>
          <th>Company</th><th>Group</th><th>Site</th><th>Career page</th>
          <th><span className="sr-only">Actions</span></th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => <CompanyRow key={item.id} item={item} onAdded={onAdded} onHide={onHide} />)}
      </tbody>
    </table>
  );
}

/** Адрес можно ввести, если своей читаемой кадровой страницы нет и компания не отмечена «не работодатель». */
function acceptsAddress(item: CompanyItem): boolean {
  return item.check.group !== 'YES' && item.check.group !== 'NOT_EMPLOYER';
}

function CompanyRow({ item, onAdded, onHide }: { item: CompanyItem; onAdded: () => void;
  onHide: (item: CompanyItem) => void }) {
  const [adding, setAdding] = useState(false);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const { check } = item;

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    try {
      await addCareerPage(item.id, url);
      setNote('Added — see “My career page addresses”.');
      setUrl('');
      setAdding(false);
      onAdded();
    } catch (failure) {
      setNote(problem(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr>
      <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
      <td><GroupBadge item={item} />{check.reason !== null && <><br /><span className="muted">
        {REASONS[check.reason] ?? check.reason}</span></>}</td>
      <td>
        {check.site !== null && <a href={'https://' + check.site} target="_blank" rel="noreferrer">{check.site}</a>}
      </td>
      <td>
        {check.careerUrl !== null && <a href={check.careerUrl} target="_blank" rel="noreferrer">{check.careerUrl}</a>}
        {adding && (
          <form className="inline-form" onSubmit={add}>
            <input type="url" required aria-label={'Career page address of ' + item.name}
              placeholder="https://…/kariera" value={url} autoFocus onChange={(event) => setUrl(event.target.value)} />
            <button type="submit" disabled={busy}>Add</button>
            <button type="button" onClick={() => setAdding(false)}>Cancel</button>
          </form>
        )}
        {note !== null && <p className="muted">{note}</p>}
      </td>
      <td className="row-actions">
        {acceptsAddress(item) && !adding && (
          <button type="button" onClick={() => { setNote(null); setAdding(true); }}>Add address</button>
        )}
        <button type="button" title="Hide only for me" onClick={() => onHide(item)}>Hide</button>
      </td>
    </tr>
  );
}
