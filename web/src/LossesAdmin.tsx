import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import {
  adminAddCareerPage, type Loss, nextLoss, postponeLoss, setNoOwnCareerPage, setNotEmployer,
} from './api';

/** Как часто вкладка /admin спрашивает следующую компанию с потерями (§235). */
const POLL_MS = 5 * 60 * 1000;

/** Одна плашка уведомления на компанию: новое уведомление с тем же тегом заменяет прежнее. */
const NOTIFICATION_TAG = 'roleorienta-loss';

/** Состояние собственного канала компании (company_coverage.channel, §234) — что не так, словами. */
const CHANNELS: Record<string, string> = {
  READ: 'own career page is read, but some portal vacancies are not on it',
  READ_EMPTY: 'own career page is read, but gives no vacancies',
  GROUP_ONLY: 'only the group career site is read',
  PAGE_FOUND: 'career page found, but not read',
  FORMAT_UNSUPPORTED: 'career page found, format not supported',
  NO_CAREER_PAGE: 'site found, career page not found',
  UNREACHABLE: 'site does not open for the application',
  USE_FORBIDDEN: 'site forbids reading',
  NOT_CHECKED: 'site not checked yet',
  NO_SITE: 'site not found',
};

function channelText(channel: string): string {
  return CHANNELS[channel] ?? channel;
}

/** Текст уведомления: что теряется и почему. */
function notificationBody(loss: Loss): string {
  const counts = loss.company.counts;
  return (counts.portalVacancies - counts.foundOnOwn) + ' of ' + counts.portalVacancies
    + ' portal vacancies are not on its own page: ' + channelText(counts.channel) + '.';
}

/**
 * Очередь потерь вакансий с уведомлением браузера (§235; решение владельца: «получил уведомление — отработал — жду
 * следующее»). Пока вкладка /admin открыта (можно в фоне), раз в {@link POLL_MS} и сразу после действия
 * спрашивается следующая компания; новая компания при скрытой вкладке — системное уведомление (Web Notifications API,
 * https://developer.mozilla.org/en-US/docs/Web/API/Notification), клик открывает её карточку.
 *
 * @param onOpen  открыть вкладку «Losses» (стабильная функция: иначе опрос перезапускается при каждой отрисовке)
 * @param enabled администратор вошёл — до входа не спрашивается
 * @returns текущая компания (null — потерь нет), загрузка заново, сообщение об ошибке
 */
export function useLosses(onOpen: () => void, enabled: boolean) {
  const [loss, setLoss] = useState<Loss | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  /** Компания последнего уведомления: та же компания не уведомляется дважды. */
  const notified = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    try {
      const next = await nextLoss();
      setLoss(next);
      setMessage(null);
      if (next !== null && next.company.id !== notified.current && document.hidden
        && 'Notification' in window && Notification.permission === 'granted') {
        notified.current = next.company.id;
        const shown = new Notification('Losing vacancies: ' + next.company.name,
          { body: notificationBody(next), tag: NOTIFICATION_TAG });
        shown.onclick = () => {
          window.focus();
          onOpen();
          shown.close();
        };
      }
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }, [onOpen]);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    void refresh();
    const timer = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [refresh, enabled]);

  return { loss, refresh, message };
}

/**
 * Вкладка «Losses» (§235): одна компания — что теряется и почему, сайты, вакансии портала, которых нет на её
 * странице, и действия: свой адрес кадровой страницы, «своей страницы нет», «не работодатель», «позже» (10 дней).
 * После действия — следующая компания.
 */
export function LossesAdmin({ loss, message, onDone }: {
  loss: Loss | null; message: string | null; onDone: () => Promise<void>;
}) {
  const [address, setAddress] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [permission, setPermission] = useState(
    'Notification' in window ? Notification.permission : 'unsupported');

  async function act(action: () => Promise<unknown>) {
    setBusy(true);
    setFailure(null);
    try {
      await action();
      setAddress('');
      await onDone();
    } catch (error) {
      setFailure((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function addAddress(event: FormEvent<HTMLFormElement>, companyId: number) {
    event.preventDefault();
    void act(() => adminAddCareerPage(companyId, address.trim()));
  }

  async function enableNotifications() {
    setPermission(await Notification.requestPermission());
  }

  return (
    <section>
      <h2>Losing vacancies</h2>
      <p className="muted">Companies whose portal and profesia.sk vacancies are not found on their own career page,
        biggest loss first. Handle one, the next one comes.</p>
      {permission === 'default' && (
        <p><button type="button" onClick={() => void enableNotifications()}>Enable notifications</button>
          <span className="muted"> — a system notification for each next company while this tab is open.</span></p>
      )}
      {permission === 'denied' && (
        <p className="muted">Notifications are blocked in the browser settings for this site.</p>)}
      {message !== null && <p className="error" role="alert">{message}</p>}
      {loss === null ? <p className="muted">No losses to handle.</p> : (
        <article className="loss">
          <h3>{loss.company.name} <span className="muted">· IČO {loss.company.registrationNumber}</span></h3>
          <p>
            <strong>{loss.company.counts.portalVacancies - loss.company.counts.foundOnOwn}</strong> of{' '}
            {loss.company.counts.portalVacancies} portal vacancies are not on its own page
            ({loss.company.counts.ownVacancies} vacancies read there): {channelText(loss.company.counts.channel)}.
            <span className="muted"> {loss.company.remaining - 1} more in the queue.</span>
          </p>
          {loss.sites.length > 0 && (
            <ul>
              {loss.sites.map((site) => (
                <li key={site.host}>
                  <a href={'https://' + site.host} target="_blank" rel="noreferrer">{site.host}</a>
                  <span className="muted"> · {site.checkResult ?? 'not checked'}</span>
                  {site.careerUrl !== null && (
                    <> · <a href={site.careerUrl} target="_blank" rel="noreferrer">career page</a></>)}
                </li>
              ))}
            </ul>
          )}
          {loss.missing.length > 0 && (
            <>
              <p className="muted">Not on its own page, for example:</p>
              <ul>
                {loss.missing.map((item) => (
                  <li key={item.url}><a href={item.url} target="_blank" rel="noreferrer">{item.title}</a></li>
                ))}
              </ul>
            </>
          )}
          <form className="inline" onSubmit={(event) => addAddress(event, loss.company.id)}>
            <label>
              Career page address
              <input type="url" required placeholder="https://" value={address}
                onChange={(event) => setAddress(event.target.value)} />
            </label>
            <button type="submit" disabled={busy}>Add address</button>
          </form>
          <div className="actions">
            <button type="button" disabled={busy}
              onClick={() => void act(() => setNoOwnCareerPage(loss.company.id, true))}>No own career page</button>
            <button type="button" disabled={busy}
              onClick={() => void act(() => setNotEmployer(loss.company.id, true))}>Not employer</button>
            <button type="button" disabled={busy}
              onClick={() => void act(() => postponeLoss(loss.company.id))}>Later (10 days)</button>
          </div>
          {failure !== null && <p className="error" role="alert">{failure}</p>}
        </article>
      )}
    </section>
  );
}
