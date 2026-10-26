import { type FormEvent, useCallback, useEffect, useState } from 'react';
import { addCareerPage, type CareerRequest, careerRequests, type CompanyItem, searchCompanies } from './api';
import { GroupBadge } from './companyGroups';
import { errorText, type MessageKey, type Translate, useI18n } from './i18n';

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
 * Вкладки раздела в порядке показа (§189, решение владельца 2026-10-26 по аудиту логики и удобства): первая и
 * открытая при входе — поиск компании, затем добавленные пользователем адреса (охват компаний — задача
 * администратора). У каждой вкладки — одна строка пояснения.
 */
type Tab = 'find' | 'addresses';

const TABS: { tab: Tab; label: MessageKey; note: MessageKey }[] = [
  { tab: 'find', label: 'companies.tabFind', note: 'companies.noteFind' },
  { tab: 'addresses', label: 'companies.tabAddresses', note: 'companies.noteAdded' },
];

/** Пока есть адреса «в проверке», их состояние обновляется само с таким промежутком. */
const REQUEST_POLL_MS = 15000;

/**
 * Компании (бизнес-описание §4.2, §5; §137, §138, §143, §189): поиск по названию или IČO; у найденной компании без
 * своей читаемой кадровой страницы (и у «только портал») — ввод её адреса по кнопке (адрес общий для всех
 * пользователей, проверяется в течение минут); свои адреса и их состояние — обновляются сами, пока идёт проверка. Новый облик (§175,
 * §176): вкладки «Find · Added by me» (у «Added by me» — число адресов) и строка пояснения; поиск — главное действие
 * вкладки «Find»; состояние своего адреса — цветной меткой; причина, по которой страница вакансий не найдена, — серым в
 * строке; действия в строках — ссылками.
 */
export function CompaniesPage() {
  const { locale, t } = useI18n();
  const problem = (failure: unknown) => errorText(failure, t);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<CompanyItem[] | null>(null);
  const [requests, setRequests] = useState<CareerRequest[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('find');

  const loadRequests = useCallback(() => {
    careerRequests().then(setRequests, (failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  useEffect(() => {
    loadRequests();
  }, [loadRequests]);

  const checking = requests.some((item) => item.status.state === 'PENDING');
  useEffect(() => {
    if (!checking) {
      return undefined;
    }
    const timer = window.setInterval(loadRequests, REQUEST_POLL_MS);
    return () => window.clearInterval(timer);
  }, [checking, loadRequests]);

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    try {
      setFound(await searchCompanies(query));
    } catch (failure) {
      setMessage(problem(failure));
    }
  }

  function added() {
    setMessage(null);
    loadRequests();
  }

  /** Подпись вкладки: «Added by me · 2»; у «Find» — только название. */
  function tabLabel(item: { tab: Tab; label: MessageKey }): string {
    return item.tab === 'addresses' ? t(item.label) + ' · ' + requests.length : t(item.label);
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
            : <CompanyTable items={found} onAdded={added} />)}
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
    </>
  );
}

interface TableProps {
  items: CompanyItem[];
  onAdded: () => void;
}

function CompanyTable({ items, onAdded }: TableProps) {
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
        {items.map((item) => <CompanyRow key={item.id} item={item} onAdded={onAdded} />)}
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

function CompanyRow({ item, onAdded }: { item: CompanyItem; onAdded: () => void }) {
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
      </td>
    </tr>
  );
}
