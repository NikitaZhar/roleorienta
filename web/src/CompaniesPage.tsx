import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  addCareerPage, ApiError, type CareerRequest, careerRequests, type CompanyItem, doubtfulCompanies, hiddenCompanies,
  searchCompanies, setCompanyHidden,
} from './api';
import { GROUPS } from './companyGroups';

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
  PENDING: 'checking',
  CONNECTED: 'connected',
  NOT_CONNECTED: 'not connected',
};

/**
 * Компании (бизнес-описание §4.2, §5; §137): поиск по названию или IČO, сомнительные компании («может быть» и «нет» с
 * признаком найма) с причиной, сайтом и адресом; у компании без читаемой кадровой страницы — ввод её адреса
 * (адрес общий для всех пользователей, проверяется в течение минут); свои адреса и их состояние. Личное «скрыть»
 * (§138): компания пропадает из поиска и сомнительных только у этого пользователя, возвращается из списка скрытых.
 */
export function CompaniesPage() {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CompanyItem[] | null>(null);
  const [doubtful, setDoubtful] = useState<CompanyItem[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [requests, setRequests] = useState<CareerRequest[]>([]);
  const [hidden, setHidden] = useState<CompanyItem[]>([]);
  const [message, setMessage] = useState<string | null>(null);

  const loadRequests = useCallback(() => {
    careerRequests().then(setRequests, (failure: Error) => setMessage(failure.message));
  }, []);

  const loadHidden = useCallback(() => {
    hiddenCompanies().then(setHidden, (failure: Error) => setMessage(failure.message));
  }, []);

  async function hide(item: CompanyItem) {
    try {
      await setCompanyHidden(item.id, true);
      setFound((current) => current === null ? null : current.filter((other) => other.id !== item.id));
      setDoubtful((current) => current.filter((other) => other.id !== item.id));
      loadHidden();
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  async function unhide(item: CompanyItem) {
    try {
      await setCompanyHidden(item.id, false);
      setHidden((current) => current.filter((other) => other.id !== item.id));
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  useEffect(() => {
    doubtfulCompanies(null).then((page) => {
      setDoubtful(page.items);
      setNext(page.next);
    }, (failure: Error) => setMessage(failure.message));
    loadRequests();
    loadHidden();
  }, [loadRequests, loadHidden]);

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      setFound(await searchCompanies(query));
    } catch (failure) {
      setMessage(failure instanceof ApiError && failure.fields.length > 0
        ? failure.fields[0].detail : (failure as Error).message);
    }
  }

  async function more() {
    if (next === null) {
      return;
    }
    try {
      const page = await doubtfulCompanies(next);
      setDoubtful((current) => [...current, ...page.items]);
      setNext(page.next);
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  return (
    <>
      <h1>Companies</h1>
      {message !== null && <p className="error" role="alert">{message}</p>}
      <form className="inline-form" onSubmit={search}>
        <input placeholder="Company name or IČO" value={query} onChange={(event) => setQuery(event.target.value)} />
        <button type="submit">Search</button>
      </form>
      {found !== null && (
        <section>
          <h2>Found</h2>
          {found.length === 0 ? <p>No companies found.</p> : <CompanyTable items={found} onAdded={loadRequests}
            onHide={hide} />}
        </section>
      )}
      <section>
        <h2>My career page addresses</h2>
        {requests.length === 0 ? <p className="muted">You have not added any address yet.</p> : (
          <table className="companies">
            <tbody>
              {requests.map((item) => (
                <tr key={item.id}>
                  <td>{item.companyName}</td>
                  <td><a href={item.url} target="_blank" rel="noreferrer">{item.url}</a></td>
                  <td>{STATES[item.status.state]}{item.status.reason !== null
                    && ': ' + (REASONS[item.status.reason] ?? item.status.reason)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <button type="button" onClick={loadRequests}>Refresh</button>
      </section>
      <section>
        <h2>Doubtful companies</h2>
        <p className="muted">Companies without a readable career page. Their vacancies from the state portal are shown
          with the others. Add a career page address — it is checked within minutes and used for all users.</p>
        <CompanyTable items={doubtful} onAdded={loadRequests} onHide={hide} />
        {next !== null && <button type="button" onClick={more}>More</button>}
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
                  <td>{GROUPS[item.check.group]}</td>
                  <td><button type="button" onClick={() => unhide(item)}>Show again</button></td>
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
        <tr><th>Company</th><th>Group</th><th>Site</th><th>Career page</th><th /></tr>
      </thead>
      <tbody>
        {items.map((item) => <CompanyRow key={item.id} item={item} onAdded={onAdded} onHide={onHide} />)}
      </tbody>
    </table>
  );
}

function CompanyRow({ item, onAdded, onHide }: { item: CompanyItem; onAdded: () => void;
  onHide: (item: CompanyItem) => void }) {
  const [url, setUrl] = useState('');
  const [note, setNote] = useState<string | null>(null);
  const { check } = item;

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      await addCareerPage(item.id, url);
      setNote('Added, checking…');
      setUrl('');
      onAdded();
    } catch (failure) {
      setNote(failure instanceof ApiError && failure.fields.length > 0
        ? failure.fields[0].detail : (failure as Error).message);
    }
  }

  return (
    <tr>
      <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
      <td>{GROUPS[check.group]}{check.reason !== null && <><br /><span className="muted">
        {REASONS[check.reason] ?? check.reason}</span></>}</td>
      <td>{check.site !== null && <a href={'https://' + check.site} target="_blank" rel="noreferrer">{check.site}</a>}</td>
      <td>
        {check.careerUrl !== null && <a href={check.careerUrl} target="_blank" rel="noreferrer">{check.careerUrl}</a>}
        {check.group !== 'YES' && check.group !== 'NOT_EMPLOYER' && (
          <form className="inline-form" onSubmit={add}>
            <input type="url" required placeholder="https://…/kariera" value={url}
              onChange={(event) => setUrl(event.target.value)} />
            <button type="submit">Add</button>
          </form>
        )}
        {note !== null && <p className="muted">{note}</p>}
      </td>
      <td><button type="button" title="Hide only for me" onClick={() => onHide(item)}>Hide</button></td>
    </tr>
  );
}
