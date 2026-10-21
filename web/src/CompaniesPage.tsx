import { type FormEvent, useCallback, useEffect, useState } from 'react';
import {
  addCareerPage, type CareerRequest, careerRequests, type CompanyItem, doubtfulCompanies, hiddenCompanies,
  searchCompanies, setCompanyHidden,
} from './api';
import { GroupBadge } from './companyGroups';
import { errorText, type MessageKey, type Translate, useI18n } from './i18n';

/** Подпись причины итога проверки и запроса на языке интерфейса (§162); неизвестная — код как есть. */
function reasonText(reason: string, t: Translate): string {
  return t(('reason.' + reason) as MessageKey) ?? reason;
}

/** Пока есть адреса «в проверке», их состояние обновляется само с таким промежутком. */
const REQUEST_POLL_MS = 15000;


/**
 * Компании (бизнес-описание §4.2, §5; §137, §138, §143): поиск по названию или IČO, сомнительные компании (без
 * вакансий: «может быть» и «нет» с признаком найма) с причиной, сайтом и адресом; у компании без своей читаемой
 * кадровой страницы (и у «только портал») — ввод её адреса по кнопке (адрес общий для всех пользователей, проверяется
 * в течение минут); свои адреса и их состояние — обновляются сами, пока идёт проверка. Личное «скрыть»: компания
 * пропадает из поиска и сомнительных только у этого пользователя, «Show again» возвращает её в списки.
 */
export function CompaniesPage() {
  const { locale, t } = useI18n();
  const problem = (failure: unknown) => errorText(failure, t);
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
    careerRequests().then(setRequests, (failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  const loadHidden = useCallback(() => {
    hiddenCompanies().then(setHidden, (failure: unknown) => setMessage(errorText(failure, t)));
  }, []);

  const loadDoubtful = useCallback(() => {
    doubtfulCompanies(null).then((page) => {
      setDoubtful(page.items);
      setNext(page.next);
    }, (failure: unknown) => setMessage(errorText(failure, t)));
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
      <h1>{t('companies.title')}</h1>
      {message !== null && <p className="error" role="alert">{message}</p>}
      <form className="inline-form" onSubmit={search} role="search">
        <input aria-label={t('companies.query')} placeholder={t('companies.query')} value={query}
          onChange={(event) => setQuery(event.target.value)} />
        <button type="submit">{t('companies.search')}</button>
      </form>
      {found !== null && (
        <section>
          <h2>{t('companies.found')}</h2>
          {found.length === 0 ? <p className="muted">{t('companies.noneFound')}</p>
            : <CompanyTable items={found} onAdded={added} onHide={hide} />}
        </section>
      )}
      <section>
        <h2>{t('companies.myAddresses')}</h2>
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
                  <td>{item.companyName}</td>
                  <td><a href={item.url} target="_blank" rel="noreferrer">{item.url}</a></td>
                  <td>{t(('request.' + item.status.state) as MessageKey)}{item.status.reason !== null
                    && ': ' + reasonText(item.status.reason, t)}</td>
                  <td className="nowrap">{new Date(item.status.createdAt).toLocaleString(locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {checking && <p className="muted">{t('companies.refreshing')}</p>}
      </section>
      <section>
        <h2>{t('companies.doubtful')}</h2>
        <p className="muted">{t('companies.doubtfulNote')}</p>
        {doubtful === null ? <p className="muted">{t('app.loading')}</p>
          : doubtful.length === 0 ? <p className="muted">{t('companies.noDoubtful')}</p>
            : <CompanyTable items={doubtful} onAdded={added} onHide={hide} />}
        {next !== null && (
          <button type="button" onClick={more} disabled={loadingMore}>{loadingMore ? t('app.loading') : t('companies.more')}</button>
        )}
      </section>
      <section>
        <h2>{t('companies.hidden')}</h2>
        <p className="muted">{t('companies.hiddenNote')}</p>
        {hidden.length === 0 ? <p className="muted">{t('companies.noHidden')}</p> : (
          <table className="companies">
            <tbody>
              {hidden.map((item) => (
                <tr key={item.id}>
                  <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
                  <td><GroupBadge item={item} /></td>
                  <td className="row-actions">
                    <button type="button" onClick={() => unhide(item)}>{t('companies.showAgain')}</button>
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
      <td>{item.name}<br /><span className="muted">IČO {item.registrationNumber}</span></td>
      <td><GroupBadge item={item} />{check.reason !== null && <><br /><span className="muted">
        {reasonText(check.reason, t)}</span></>}</td>
      <td>
        {check.site !== null && <a href={'https://' + check.site} target="_blank" rel="noreferrer">{check.site}</a>}
      </td>
      <td>
        {check.careerUrl !== null && <a href={check.careerUrl} target="_blank" rel="noreferrer">{check.careerUrl}</a>}
        {adding && (
          <form className="inline-form" onSubmit={add}>
            <input type="url" required aria-label={t('companies.addressOf', { name: item.name })}
              placeholder="https://…/kariera" value={url} autoFocus onChange={(event) => setUrl(event.target.value)} />
            <button type="submit" disabled={busy}>{t('companies.add')}</button>
            <button type="button" onClick={() => setAdding(false)}>{t('companies.cancel')}</button>
          </form>
        )}
        {note !== null && <p className="muted">{note}</p>}
      </td>
      <td className="row-actions">
        {acceptsAddress(item) && !adding && (
          <button type="button" onClick={() => { setNote(null); setAdding(true); }}>{t('companies.addAddress')}</button>
        )}
        <button type="button" title={t('companies.hideOnlyForMe')} onClick={() => onHide(item)}>
          {t('companies.hide')}
        </button>
      </td>
    </tr>
  );
}
