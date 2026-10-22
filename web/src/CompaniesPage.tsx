import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  addCareerPage, type CareerRequest, careerRequests, type CompanyItem, type CompanyPage, doubtfulCompanies,
  hiddenCompanies,
  searchCompanies, setCompanyHidden,
} from './api';
import { GroupBadge } from './companyGroups';
import { errorText, type MessageKey, type Translate, useI18n } from './i18n';
import { PAGE_SIZES, PageSize, Pager, storedSize, storeSize } from './Pager';

/** Подпись причины итога проверки и запроса на языке интерфейса (§162); неизвестная — код как есть. */
function reasonText(reason: string, t: Translate): string {
  return t(('reason.' + reason) as MessageKey) ?? reason;
}

/** Состояние адреса кадровой страницы — меткой цвета групп компаний (новый облик, §175): цвет дополняет текст. */
const REQUEST_BADGES: Record<CareerRequest['status']['state'], string> = {
  PENDING: 'badge-maybe',
  CONNECTED: 'badge-yes',
  NOT_CONNECTED: 'badge-no',
};

/**
 * Вкладки раздела в порядке показа (решение владельца 2026-10-22, §176): первая и открытая при входе — компании, чьи
 * вакансии не собираются (ради них раздел и нужен), затем поиск, добавленные пользователем адреса, скрытые. У каждой —
 * одна строка пояснения под вкладками: что здесь и что можно сделать.
 */
type Tab = 'doubtful' | 'find' | 'addresses' | 'hidden';

const TABS: { tab: Tab; label: MessageKey; note: MessageKey }[] = [
  { tab: 'doubtful', label: 'companies.tabDoubtful', note: 'companies.noteDoubtful' },
  { tab: 'find', label: 'companies.tabFind', note: 'companies.noteFind' },
  { tab: 'addresses', label: 'companies.tabAddresses', note: 'companies.noteAdded' },
  { tab: 'hidden', label: 'companies.tabHidden', note: 'companies.noteHidden' },
];

/** Пока есть адреса «в проверке», их состояние обновляется само с таким промежутком. */
const REQUEST_POLL_MS = 15000;


/**
 * Компании (бизнес-описание §4.2, §5; §137, §138, §143): поиск по названию или IČO, сомнительные компании (без
 * вакансий: «может быть» и «нет» с признаком найма) с причиной, сайтом и адресом; у компании без своей читаемой
 * кадровой страницы (и у «только портал») — ввод её адреса по кнопке (адрес общий для всех пользователей, проверяется
 * в течение минут); свои адреса и их состояние — обновляются сами, пока идёт проверка. Личное «скрыть»: компания
 * пропадает из поиска и сомнительных только у этого пользователя, «Show again» возвращает её в списки. Новый облик
 * (§175, §176): вкладки «No vacancies collected · Find · Added by me · Hidden» с числом записей (данные всех вкладок
 * загружаются при открытии раздела, как раньше) и строкой пояснения; поиск — главное действие вкладки «Find»;
 * состояние своего адреса — цветной меткой; причина, по которой страница вакансий не найдена, — серым в строке;
 * действия в строках — ссылками.
 */
export function CompaniesPage() {
  const { locale, t } = useI18n();
  const problem = (failure: unknown) => errorText(failure, t);
  const [query, setQuery] = useState('');
  const [searched, setSearched] = useState<string | null>(null);
  const [found, setFound] = useState<CompanyItem[] | null>(null);
  const [doubtful, setDoubtful] = useState<CompanyPage | null>(null);
  const [size, setSize] = useState(storedSize);
  const [loadingPage, setLoadingPage] = useState(false);
  const [requests, setRequests] = useState<CareerRequest[]>([]);
  const [hidden, setHidden] = useState<CompanyItem[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('doubtful');

  const loadRequests = useCallback(() => {
    careerRequests().then(setRequests, (failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  const loadHidden = useCallback(() => {
    hiddenCompanies().then(setHidden, (failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  /** Страница сомнительных; опустела (скрыта последняя компания последней страницы) — последняя непустая. */
  const loadDoubtful = useCallback(async (page: number, rows: number) => {
    setLoadingPage(true);
    try {
      let next = await doubtfulCompanies(page, rows);
      if (next.items.length === 0 && next.total > 0 && page > 1) {
        next = await doubtfulCompanies(Math.ceil(next.total / rows), rows);
      }
      setDoubtful(next);
    } catch (failure) {
      setMessage(errorText(failure, t));
    } finally {
      setLoadingPage(false);
    }
  }, []);

  useEffect(() => {
    loadDoubtful(1, size);
    loadRequests();
    loadHidden();
  }, [loadDoubtful, loadRequests, loadHidden]);

  function resize(rows: number) {
    setSize(rows);
    storeSize(rows);
    loadDoubtful(1, rows);
  }

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

  async function hide(item: CompanyItem) {
    setMessage(null);
    try {
      await setCompanyHidden(item.id, true);
      setFound((current) => current === null ? null : current.filter((other) => other.id !== item.id));
      await loadDoubtful(doubtful?.page ?? 1, size);
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
      await loadDoubtful(doubtful?.page ?? 1, size);
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

  /** Число записей вкладки; у «Find» и пока не загружено — нет. */
  function tabCount(item: Tab): number | null {
    switch (item) {
      case 'addresses':
        return requests.length;
      case 'doubtful':
        return doubtful?.total ?? null;
      case 'hidden':
        return hidden.length;
      default:
        return null;
    }
  }

  /** Подпись вкладки: «No vacancies collected · 12»; без числа — только название. */
  function tabLabel(item: { tab: Tab; label: MessageKey }): string {
    const count = tabCount(item.tab);
    return count === null ? t(item.label) : t(item.label) + ' · ' + count;
  }

  return (
    <>
      <h1>{t('companies.title')}</h1>
      <nav className="tabs" aria-label={t('companies.title')}>
        {TABS.map((item) => (
          <button key={item.tab} type="button" className={tab === item.tab ? 'current' : ''}
            aria-current={tab === item.tab ? 'page' : undefined} onClick={() => setTab(item.tab)}>
            {tabLabel(item)}
          </button>
        ))}
      </nav>
      <p className="muted tab-note">{t(TABS.find((item) => item.tab === tab)!.note)}</p>
      {message !== null && <p className="error" role="alert">{message}</p>}
      {tab === 'find' && (
        <section>
          <form className="inline-form company-search" onSubmit={search} role="search">
            <input aria-label={t('companies.query')} placeholder={t('companies.query')} value={query}
              onChange={(event) => setQuery(event.target.value)} />
            <button type="submit" className="primary">{t('companies.search')}</button>
          </form>
          {found !== null && (found.length === 0 ? <p className="muted">{t('companies.noneFound')}</p>
            : <CompanyTable items={found} onAdded={added} onHide={hide} />)}
        </section>
      )}
      {tab === 'addresses' && (
        <section>
          {requests.length === 0 ? <p className="muted">{t('companies.noAddresses')}</p> : (
            <table className="companies">
              <thead>
                <tr>
                  <th>{t('companies.company')}</th><th>{t('companies.address')}</th><th>{t('companies.state')}</th>
                  <th>{t('companies.added')}</th>
                </tr>
              </thead>
              <tbody>
                {requests.map((item) => (
                  <tr key={item.id}>
                    <td className="company">{item.companyName}</td>
                    <td><a href={item.url} target="_blank" rel="noreferrer">{item.url}</a></td>
                    <td>
                      <span className={'badge ' + REQUEST_BADGES[item.status.state]}>
                        {t(('request.' + item.status.state) as MessageKey)}
                      </span>
                      {item.status.reason !== null && <span className="muted"> {reasonText(item.status.reason, t)}</span>}
                    </td>
                    <td className="nowrap">{new Date(item.status.createdAt).toLocaleString(locale)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
      {tab === 'doubtful' && (
        <section>
          {doubtful === null ? <p className="muted">{t('app.loading')}</p>
            : doubtful.total === 0 ? <p className="muted">{t('companies.noDoubtful')}</p>
              : <CompanyTable items={doubtful.items} onAdded={added} onHide={hide} />}
          {doubtful !== null && doubtful.total > 0 && (
            <div className="list-footer">
              <Pager page={doubtful.page} pages={Math.ceil(doubtful.total / doubtful.size)} busy={loadingPage}
                onPage={(page) => loadDoubtful(page, size)} />
              <PageSize size={size} sizes={PAGE_SIZES} busy={loadingPage} onSize={resize} />
            </div>
          )}
        </section>
      )}
      {tab === 'hidden' && (
        <section>
          {hidden.length === 0 ? <p className="muted">{t('companies.noHidden')}</p> : (
            <table className="companies">
              <tbody>
                {hidden.map((item) => (
                  <tr key={item.id}>
                    <td className="company">{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
                    <td><Group item={item} /></td>
                    <td className="row-actions">
                      <button type="button" className="link" onClick={() => unhide(item)}>
                        {t('companies.showAgain')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </>
  );
}

interface TableProps {
  items: CompanyItem[];
  onAdded: () => void;
  onHide: (item: CompanyItem) => void;
}

function CompanyTable({ items, onAdded, onHide }: TableProps) {
  const { t } = useI18n();
  return (
    <table className="companies">
      <thead>
        <tr>
          <th>{t('companies.company')}</th><th>{t('companies.group')}</th><th>{t('companies.site')}</th>
          <th>{t('companies.careerPage')}</th><th><span className="sr-only">{t('companies.actions')}</span></th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => <CompanyRow key={item.id} item={item} onAdded={onAdded} onHide={onHide} />)}
      </tbody>
    </table>
  );
}

/** Источник вакансий компании меткой и под ней серым — причина, по которой страница вакансий не найдена (§176). */
function Group({ item }: { item: CompanyItem }) {
  const { t } = useI18n();
  return (
    <>
      <GroupBadge item={item} />
      {item.check.reason !== null && <div className="muted">{reasonText(item.check.reason, t)}</div>}
    </>
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
  const { t } = useI18n();

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    try {
      await addCareerPage(item.id, url);
      setNote(t('companies.addedNote'));
      setUrl('');
      setAdding(false);
      onAdded();
    } catch (failure) {
      setNote(errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr>
      <td className="company">{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
      <td><Group item={item} /></td>
      <td>
        {check.site !== null && <a href={'https://' + check.site} target="_blank" rel="noreferrer">{check.site}</a>}
      </td>
      <td>
        {check.careerUrl !== null && <a href={check.careerUrl} target="_blank" rel="noreferrer">{check.careerUrl}</a>}
        {adding && (
          <form className="inline-form" onSubmit={add}>
            <input type="url" required aria-label={t('companies.addressOf', { name: item.name })}
              placeholder="https://…/kariera" value={url} autoFocus onChange={(event) => setUrl(event.target.value)} />
            <button type="submit" className="primary" disabled={busy}>{t('companies.add')}</button>
            <button type="button" className="link" onClick={() => setAdding(false)}>{t('companies.cancel')}</button>
          </form>
        )}
        {note !== null && <p className="muted">{note}</p>}
      </td>
      <td className="row-actions">
        {acceptsAddress(item) && !adding && (
          <button type="button" className="link" onClick={() => { setNote(null); setAdding(true); }}>
            {t('companies.addAddress')}
          </button>
        )}
        <button type="button" className="link" title={t('companies.hideOnlyForMe')} onClick={() => onHide(item)}>
          {t('companies.hide')}
        </button>
      </td>
    </tr>
  );
}
