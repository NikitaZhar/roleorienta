import { type FormEvent, useEffect, useState } from 'react';
import {
  type Admin, adminLogin, adminSearchCompanies, type CompanyItem, currentAdmin, logout, metrics, type MetricRow,
  type MetricsTable, setNotEmployer,
} from './api';
import { GROUPS } from './companyGroups';

/** Сколько последних дней показывать. */
const DAYS = 14;

/** Названия показателей (технический документ §11). */
const LABELS: Record<string, string> = {
  companies_known: 'Companies known',
  companies_processed: 'Companies processed',
  companies_remaining: 'Companies remaining',
  companies_with_site: 'Companies with confirmed site',
  companies_connected: 'Companies with connected channel',
  companies_failed: 'Channel not found',
  vacancies_active: 'Vacancies active',
  vacancies_recheck: 'Vacancies needing recheck',
  vacancies_new: 'Vacancies new',
  vacancies_closed: 'Vacancies closed',
  sources: 'Sources',
  source_reads: 'Sources by last read of the day',
  sources_overdue: 'Sources with overdue read',
  operations_failed: 'Operations failed after all retries',
};

/** Показатели с долей от «обработано». */
const SHARE_OF_PROCESSED = new Set(['companies_with_site', 'companies_connected']);

/** Подпись страны: код, «весь мир» или «без страны». */
function countryLabel(code: string): string {
  if (code === '*') {
    return 'Remote, worldwide';
  }
  return code === '' ? 'No country' : code;
}

/**
 * Экран администратора (/admin, §132): отдельный вход (имя admin) и показатели ядра по дням (бизнес-описание §7.3):
 * строки — показатель и разрез (итог жирным), столбцы — дни, новые слева; у сайта и подключения — доля от
 * «обработано»; отметка компании «не работодатель» (§138) — общая для всех, такая компания не проверяется и не
 * предлагается пользователям.
 */
export function AdminApp() {
  const [admin, setAdmin] = useState<Admin | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    currentAdmin().then(setAdmin, (failure: Error) => setError(failure.message));
  }, []);

  async function signOut() {
    try {
      await logout();
      setAdmin(null);
    } catch (failure) {
      setError('Sign out failed: ' + (failure as Error).message);
    }
  }

  if (error !== null) {
    return <main className="page"><p className="error">{error}</p></main>;
  }
  if (admin === undefined) {
    return <main className="page"><p>Loading…</p></main>;
  }
  if (admin === null) {
    return <AdminLogin onSignedIn={setAdmin} />;
  }
  return (
    <>
      <header className="bar">
        <span className="brand">roleorienta · admin</span>
        <button type="button" onClick={signOut}>Sign out</button>
      </header>
      <main className="page wide">
        <MetricsView />
        <NotEmployerView />
      </main>
    </>
  );
}

function AdminLogin({ onSignedIn }: { onSignedIn: (admin: Admin) => void }) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      onSignedIn(await adminLogin(username, password));
    } catch (failure) {
      setMessage((failure as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page narrow">
      <h1>roleorienta · admin</h1>
      <form onSubmit={submit}>
        <label>
          Name
          <input autoComplete="username" required value={username}
            onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label>
          Password
          <input type="password" autoComplete="current-password" required value={password}
            onChange={(event) => setPassword(event.target.value)} />
        </label>
        {message !== null && <p className="error" role="alert">{message}</p>}
        <div className="actions">
          <button type="submit" disabled={busy}>Sign in</button>
        </div>
      </form>
    </main>
  );
}

function MetricsView() {
  const [country, setCountry] = useState<string | null>(null);
  const [table, setTable] = useState<MetricsTable | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    metrics(country, DAYS).then(setTable, (failure: Error) => setMessage(failure.message));
  }, [country]);

  if (message !== null) {
    return <p className="error">{message}</p>;
  }
  if (table === null) {
    return <p>Loading…</p>;
  }
  const processed = table.rows.find((row) => row.metric === 'companies_processed' && row.dimension === '');
  return (
    <>
      <h1>Core metrics</h1>
      <label className="inline">
        Country
        <select value={table.country} onChange={(event) => setCountry(event.target.value)}>
          {table.countries.map((code) => <option key={code} value={code}>{countryLabel(code)}</option>)}
        </select>
      </label>
      {table.days.length === 0
        ? <p>No metrics yet: they are computed for each past day shortly after midnight.</p>
        : (
          <div className="scroll">
            <table className="metrics">
              <thead>
                <tr>
                  <th>Metric</th>
                  {table.days.map((day) => <th key={day}>{day}</th>)}
                </tr>
              </thead>
              <tbody>
                {table.rows.map((row) => (
                  <MetricLine key={row.metric + '|' + row.dimension} row={row} processed={processed} />
                ))}
              </tbody>
            </table>
          </div>
        )}
    </>
  );
}

function MetricLine({ row, processed }: { row: MetricRow; processed: MetricRow | undefined }) {
  const total = row.dimension === '';
  const share = total && SHARE_OF_PROCESSED.has(row.metric) ? processed : undefined;
  return (
    <tr className={total ? 'total' : 'part'}>
      <th scope="row">{total ? (LABELS[row.metric] ?? row.metric) : row.dimension}</th>
      {row.values.map((value, index) => (
        <td key={index}>
          {value ?? '–'}
          {share !== undefined && value !== null && percent(value, share.values[index])}
        </td>
      ))}
    </tr>
  );
}

function percent(value: number, of: number | null): string {
  return of === null || of === 0 ? '' : ` (${Math.round((value * 100) / of)} %)`;
}

function NotEmployerView() {
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CompanyItem[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      setFound(await adminSearchCompanies(query));
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  async function mark(item: CompanyItem, notEmployer: boolean) {
    setMessage(null);
    try {
      await setNotEmployer(item.id, notEmployer);
      setFound(await adminSearchCompanies(query));
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  return (
    <section>
      <h1>Not an employer</h1>
      <p className="muted">A trade union, an association, an owners' community: the mark is shared by all users, the
        company is no longer checked and not offered as doubtful.</p>
      <form className="inline-form" onSubmit={search}>
        <input placeholder="Company name or IČO" value={query} onChange={(event) => setQuery(event.target.value)} />
        <button type="submit">Search</button>
      </form>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {found !== null && (found.length === 0 ? <p>No companies found.</p> : (
        <table className="companies">
          <tbody>
            {found.map((item) => (
              <tr key={item.id}>
                <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
                <td>{GROUPS[item.check.group]}</td>
                <td>{item.check.site ?? ''}</td>
                <td>
                  {item.check.group === 'NOT_EMPLOYER'
                    ? <button type="button" onClick={() => mark(item, false)}>Remove mark</button>
                    : <button type="button" onClick={() => mark(item, true)}>Mark not an employer</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </section>
  );
}
