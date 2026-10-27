import { type FormEvent, useEffect, useState } from 'react';
import {
  type Admin, adminLogin, adminSearchCompanies, type CompanyItem, coreStatus, type CoreStatus, currentAdmin, logout,
  metrics, type MetricRow, type MetricsTable, searchMisses, setNotEmployer,
} from './api';
import { GroupBadge } from './companyGroups';
import { CareerRequestsAdmin, UnlinkedSourcesAdmin } from './CompaniesAdmin';
import { DictionaryAdmin } from './DictionaryAdmin';
import { SearchMissesAdmin } from './SearchMissesAdmin';

/** Разделы страницы — по задачам администратора (§206): ядро работает, растёт ли охват, находят ли пользователи
 * нужное, ошибки по компаниям. */
type Tab = 'status' | 'metrics' | 'positions' | 'companies';

const TABS: { tab: Tab; label: string; note: string }[] = [
  { tab: 'status', label: 'Status', note: 'Is the core working right now?' },
  { tab: 'metrics', label: 'Metrics', note: 'Are there more vacancies day by day?' },
  { tab: 'positions', label: 'Positions', note: 'Do users find what they look for?' },
  { tab: 'companies', label: 'Companies', note: 'Fix what the application got wrong about a company.' },
];

/** Ключ выбранной вкладки в хранилище вкладки браузера: перезагрузка страницы не сбрасывает раздел. */
const TAB_KEY = 'roleorienta.admin.tab';

function storedTab(): Tab {
  try {
    const value = sessionStorage.getItem(TAB_KEY);
    return TABS.some((item) => item.tab === value) ? value as Tab : 'status';
  } catch {
    return 'status';
  }
}

function storeTab(tab: Tab) {
  try {
    sessionStorage.setItem(TAB_KEY, tab);
  } catch {
    // Хранилище недоступно: вкладка запоминается до перезагрузки.
  }
}

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
  vacancies_recognized: 'Vacancies with recognized profession',
  vacancies_new: 'Vacancies new',
  vacancies_closed: 'Vacancies closed',
  sources: 'Sources',
  source_reads: 'Sources by last read of the day',
  sources_overdue: 'Sources with overdue read',
  operations_failed: 'Operations failed after all retries',
};

/** Сравнение сводки — с днём неделей раньше (дни — новые первыми); данных меньше — с самым старым днём. */
const WEEK = 7;

/** Итог показателя по дням (разрез «»); нет — пустой. */
function totals(table: MetricsTable, metric: string): (number | null)[] {
  return table.rows.find((row) => row.metric === metric && row.dimension === '')?.values ?? [];
}

/** Открытые вакансии (актуальные и на перепроверке) по дням — та же база, что у доли распознанных (аудит §211). */
function openVacancies(table: MetricsTable): (number | null)[] {
  const active = totals(table, 'vacancies_active');
  const recheck = totals(table, 'vacancies_recheck');
  return table.days.map((_, index) => (active[index] === null || active[index] === undefined ? null
    : (active[index] ?? 0) + (recheck[index] ?? 0)));
}

/** Доля распознанной профессии среди открытых (актуальные и на перепроверке), %, по дням. */
function recognizedShare(table: MetricsTable): (number | null)[] {
  const recognized = totals(table, 'vacancies_recognized');
  const active = totals(table, 'vacancies_active');
  const recheck = totals(table, 'vacancies_recheck');
  return table.days.map((_, index) => {
    const open = (active[index] ?? 0) + (recheck[index] ?? 0);
    const value = recognized[index];
    return value === null || value === undefined || open === 0 ? null : Math.round((value * 100) / open);
  });
}

/**
 * Сводка над таблицей (§206): пять чисел последнего дня и изменение за неделю — «вакансий становится больше?».
 * У просроченных чтений и сбоев рост — плохо: стрелка та же, вывод за администратором.
 */
function MetricsSummary({ table }: { table: MetricsTable }) {
  const items: { label: string; values: (number | null)[]; unit?: string }[] = [
    { label: 'Open vacancies', values: openVacancies(table) },
    { label: 'With recognized profession', values: recognizedShare(table), unit: ' %' },
    { label: 'Companies with channel', values: totals(table, 'companies_connected') },
    { label: 'Sources read overdue', values: totals(table, 'sources_overdue') },
    { label: 'Operations failed', values: totals(table, 'operations_failed') },
  ];
  const before = Math.min(WEEK, table.days.length - 1);
  return (
    <ul className="metric-summary">
      {items.map((item) => {
        const now = item.values[0] ?? null;
        const then = before > 0 ? item.values[before] ?? null : null;
        const change = now !== null && then !== null ? now - then : null;
        return (
          <li key={item.label}>
            <span className="muted">{item.label}</span>
            <strong>{now === null ? '–' : now + (item.unit ?? '')}</strong>
            {change !== null && change !== 0 && (
              <span className="muted">{change > 0 ? '↑' : '↓'} {Math.abs(change)}{item.unit ?? ''} since {table.days[before]}</span>
            )}
          </li>
        );
      })}
    </ul>
  );
}

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
 * Экран администратора (/admin, §132): отдельный вход (имя admin); разделы вкладками по задачам администратора (§206),
 * открыт один, под вкладками — строка «на какой вопрос отвечает». Status — состояние ядра сейчас с тревогами (§142);
 * тревога видна точкой у вкладки из любого раздела. Metrics — показатели ядра по дням (бизнес-описание §7.3): строки —
 * показатель и разрез (итог жирным), столбцы — дни, новые слева; у сайта и подключения — доля от «обработано».
 * Positions — запросы без результата (§205) и словарь позиций (§160). Companies — отметка «не работодатель» (§138),
 * общая для всех: сайт и кадровая страница не ищутся, адрес не вводится; подключённые источники читаются дальше. При
 * ошибке загрузки блока — «Retry» (§143).
 */
export function AdminApp() {
  const [admin, setAdmin] = useState<Admin | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>(storedTab);
  const [status, setStatus] = useState<CoreStatus | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [missCount, setMissCount] = useState(0);
  const [adding, setAdding] = useState<string | null>(null);

  /** Состояние ядра — на уровне страницы: тревога видна точкой у вкладки Status из любого раздела. */
  function loadStatus() {
    setStatusError(null);
    coreStatus().then(setStatus, (failure: Error) => setStatusError(failure.message));
  }

  useEffect(() => {
    if (admin) {
      loadStatus();
      searchMisses().then((items) => setMissCount(items.length), () => setMissCount(0));
    }
  }, [admin]);

  function choose(next: Tab) {
    setTab(next);
    storeTab(next);
  }

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
        <div className="bar-inner admin">
          <span className="brand">roleorienta · admin</span>
          <button type="button" onClick={signOut}>Sign out</button>
        </div>
      </header>
      <main className="page wide">
        <h1 className="sr-only">Administration</h1>
        <nav className="tabs" aria-label="Administration">
          {TABS.map((item) => (
            <button key={item.tab} type="button" className={tab === item.tab ? 'current' : ''}
              aria-current={tab === item.tab ? 'page' : undefined} onClick={() => choose(item.tab)}>
              {item.label}
              {item.tab === 'positions' && missCount > 0 && ` · ${missCount}`}
              {item.tab === 'status' && status !== null && status.warnings.length > 0 && (
                <span className="tab-alert" role="img" aria-label="warning" />
              )}
            </button>
          ))}
        </nav>
        <p className="muted tab-note">{TABS.find((item) => item.tab === tab)!.note}</p>
        {tab === 'status' && <StatusView status={status} message={statusError} onRefresh={loadStatus} />}
        {tab === 'metrics' && <MetricsView />}
        {tab === 'positions' && (
          <>
            <SearchMissesAdmin onAdd={setAdding} onCount={setMissCount} />
            <DictionaryAdmin adding={adding} onAdding={setAdding} />
          </>
        )}
        {tab === 'companies' && <><NotEmployerView /><CareerRequestsAdmin /><UnlinkedSourcesAdmin /></>}
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

  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    setMessage(null);
    metrics(country, DAYS).then(setTable, (failure: Error) => setMessage(failure.message));
  }, [country, attempt]);

  if (message !== null || table === null) {
    return (
      <section>
        <h2>Core metrics</h2>
        {message === null ? <p className="muted">Loading…</p> : (
          <>
            <p className="error" role="alert">{message}</p>
            <button type="button" onClick={() => setAttempt((count) => count + 1)}>Retry</button>
          </>
        )}
      </section>
    );
  }
  const processed = table.rows.find((row) => row.metric === 'companies_processed' && row.dimension === '');
  return (
    <section>
      <h2>Core metrics</h2>
      <label className="inline">
        Country
        <select value={table.country} onChange={(event) => setCountry(event.target.value)}>
          {table.countries.map((code) => <option key={code} value={code}>{countryLabel(code)}</option>)}
        </select>
      </label>
      {table.days.length === 0
        ? <p>No metrics yet: they are computed for each past day shortly after midnight.</p>
        : (
          <>
          <MetricsSummary table={table} />
          <details>
          <summary>Details</summary>
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
          </details>
          </>
        )}
    </section>
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
      <h2>Find a company</h2>
      <p className="muted">The collection channel of a company, and the mark «not an employer» — for a trade union, an
        association, an owners' community: the mark is shared by all users. The
        company's site and career page are no longer searched by name and on its sites, and users cannot add its
        career page address. Vacancies of sources already connected are still
        read.</p>
      <form className="inline-form" onSubmit={search} role="search">
        <input aria-label="Company name or IČO" placeholder="Company name or IČO" value={query}
          onChange={(event) => setQuery(event.target.value)} />
        <button type="submit">Search</button>
      </form>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {found !== null && (found.length === 0 ? <p className="muted">No companies found.</p> : (
        <table className="companies">
          <thead>
            <tr><th>Company</th><th>Group</th><th>Site</th><th><span className="sr-only">Actions</span></th></tr>
          </thead>
          <tbody>
            {found.map((item) => (
              <tr key={item.id}>
                <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
                <td><GroupBadge item={item} /></td>
                <td>{item.check.site ?? ''}</td>
                <td className="row-actions">
                  {item.check.group === 'NOT_EMPLOYER'
                    ? <button type="button" onClick={() => mark(item, false)}>Remove mark</button>
                    : <button type="button" onClick={() => mark(item, true)}>Mark not an employer</button>}
                  {(item.check.group === 'YES' || item.check.group === 'PORTAL') && (
                    <p className="muted">Vacancies are collected from this company.</p>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </section>
  );
}

/** Тексты тревог состояния ядра. */
const WARNINGS: Record<string, string> = {
  NO_RECENT_READ: 'No source has been read for more than 2 hours — the worker may be stopped.',
  QUEUE_STALLED: 'A task has been waiting in the queue for more than 2 hours — the queue is not processed.',
};

/** Сколько прошло от момента до проверки: «12 min ago», «3 h ago». */
function ago(at: string | null, now: string): string {
  if (at === null) {
    return 'never';
  }
  const minutes = Math.round((Date.parse(now) - Date.parse(at)) / 60000);
  return minutes < 120 ? `${minutes} min ago` : `${Math.round(minutes / 60)} h ago`;
}

function StatusView({ status, message, onRefresh }: {
  status: CoreStatus | null;
  message: string | null;
  onRefresh: () => void;
}) {
  if (message !== null || status === null) {
    return (
      <section>
        <h2>Core status</h2>
        {message === null ? <p className="muted">Loading…</p> : (
          <>
            <p className="error" role="alert">{message}</p>
            <button type="button" onClick={onRefresh}>Retry</button>
          </>
        )}
      </section>
    );
  }
  const { reads, tasks } = status;
  return (
    <section>
      <h2>Core status</h2>
      {status.warnings.length === 0
        ? <p className="status-ok">Working: sources are read, the queue is processed.</p>
        : status.warnings.map((code) => <p key={code} className="error" role="alert">{WARNINGS[code] ?? code}</p>)}
      <table className="companies">
        <tbody>
          <tr><th scope="row">Last successful read</th><td>{ago(reads.lastSuccessAt, status.checkedAt)}</td></tr>
          <tr>
            <th scope="row">Reads in the last hour</th>
            <td>{reads.lastHour} ok, {reads.failedLastHour} failed</td>
          </tr>
          <tr><th scope="row">Tasks in the queue</th>
            <td>{tasks.queued}{tasks.oldestQueuedAt !== null
              && `, oldest ${ago(tasks.oldestQueuedAt, status.checkedAt)}`}</td></tr>
          <tr><th scope="row">Tasks failed after all retries, last 24 h</th><td>{tasks.failedLastDay}</td></tr>
        </tbody>
      </table>
      <button type="button" onClick={onRefresh}>Refresh</button>
    </section>
  );
}
