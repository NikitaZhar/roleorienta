import { useEffect, useRef, useState } from 'react';
import { setStatus, type StatusEvent, vacancy, type VacancyDetails } from './api';
import { countriesText, dateText, formatText, historyLabel, languagesText, openedText, statusLabel } from './format';
import { errorText, useI18n } from './i18n';

interface Props {
  id: number;
  /** Подпись возврата: к какому разделу («← Back to results», «← Back to applications» …). */
  backLabel: string;
  /** Вернуться к разделу; {@code changed} — статус вакансии изменился, список нужно перечитать. */
  onBack: (changed: boolean) => void;
}

/** Статусы отклика (§195): в карточке «Моих откликов» выбираются только они — «Не интересно» там не предлагается. */
const RESPONSES = ['APPLIED', 'INTERVIEW', 'REJECTED'] as const;

/**
 * Что можно сделать в карточке по текущему статусу (§195, решения владельца 2026-10-26): отклик — сменить статус
 * отклика (CV sent · Interview · Employer declined); «Не интересно» — только «Return» (вернуть в результаты); иначе
 * (карточка открыта не из раздела) — CV sent или Not interested.
 */
type Choice = 'APPLIED' | 'INTERVIEW' | 'REJECTED' | 'NOT_INTERESTED';

function choicesFor(code: string): Choice[] {
  if ((RESPONSES as readonly string[]).includes(code)) {
    return [...RESPONSES];
  }
  return code === 'NOT_INTERESTED' ? [] : ['APPLIED', 'NOT_INTERESTED'];
}

/**
 * Сведения о вакансии (бизнес-описание §6, экран §147; порядок — решение владельца 2026-10-21, §173): сначала вся
 * информация о вакансии одним списком, без кнопок между строками — позиция, работодатель и агентство, страна, формат
 * («Only office», «Only remote — from …», «Hybrid»), требования к языку (§158), дата открытия («found …», если
 * работодатель даты не дал, §127); чего нет, той строки нет (§192, принцип владельца: нет информации — показываем то,
 * что есть); затем ссылка на первичную публикацию; затем — вместе — текущий статус с датой и выбором (§195: у отклика —
 * «CV sent», «Interview», «Employer declined»; у «Не интересно» вместо выбора — «Return», вакансия возвращается в
 * результаты и карточка закрывается) и история с датами, раньше сверху. После смены статуса экран остаётся, без сообщения, и
 * перечитывается; при возврате список перечитывается — вакансия, ушедшая в другой раздел, из него пропадает.
 * Состояние и дата последней проверки не показываются (решение владельца 2026-10-20, §146).
 */
export function VacancyPage({ id, backLabel, onBack }: Props) {
  const i18n = useI18n();
  const { t } = i18n;
  const [details, setDetails] = useState<VacancyDetails | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** Статус менялся за время открытия (в том числе «Viewed» при открытии новой). */
  const changed = useRef(false);

  useEffect(() => {
    async function open() {
      let next = await vacancy(id);
      if (next.publication.timeline.status.code === 'NEW') {
        await setStatus(id, 'VIEWED');
        changed.current = true;
        next = await vacancy(id);
      }
      setDetails(next);
    }
    open().catch((failure: unknown) => setMessage(errorText(failure, t)));
  }, [id]);

  async function choose(status: StatusEvent) {
    setBusy(true);
    try {
      await setStatus(id, status);
      changed.current = true;
      setDetails(await vacancy(id));
    } catch (failure) {
      setMessage(errorText(failure, t));
    } finally {
      setBusy(false);
    }
  }

  /** «Return» у «Не интересно» (§195): вакансия возвращается к прежнему статусу, карточка закрывается. */
  async function giveBack() {
    setBusy(true);
    try {
      await setStatus(id, 'RETURNED');
      onBack(true);
    } catch (failure) {
      setMessage(errorText(failure, t));
      setBusy(false);
    }
  }

  const back = <button type="button" onClick={() => onBack(changed.current)}>{backLabel}</button>;
  if (message !== null) {
    return <p className="error">{message} {back}</p>;
  }
  if (details === null) {
    return <p>{t('app.loading')}</p>;
  }
  const { parties, work, publication } = details;
  const status = publication.timeline.status;
  const choices = choicesFor(status.code);
  return (
    <article className="details">
      {back}
      <h2>{details.position}</h2>
      <dl>
        {parties.employer !== null && (<><dt>{t('vacancy.employer')}</dt><dd>{parties.employer}</dd></>)}
        {parties.agency !== null && (<><dt>{t('vacancy.agency')}</dt><dd>{parties.agency}</dd></>)}
        {!work.countryUncertain && (<><dt>{t('vacancy.country')}</dt><dd>{countriesText(work, i18n)}</dd></>)}
        {work.format !== null && (<><dt>{t('vacancy.format')}</dt><dd>{formatText(work, i18n)}</dd></>)}
        {publication.languages.length > 0 && (
          <><dt>{t('vacancy.languages')}</dt><dd>{languagesText(publication.languages, i18n)}</dd></>
        )}
        <dt>{t('vacancy.opened')}</dt>
        <dd>{openedText(publication.timeline.opened, i18n)}</dd>
      </dl>
      {publication.url !== null && (
        <div className="actions">
          <a className="button" href={publication.url} target="_blank" rel="noopener noreferrer">{t('vacancy.openPosting')}</a>
        </div>
      )}
      <section className="tracking">
        <div className="status">
          {choices.length === 0 ? (
            <>
              <span><span className="muted">{t('vacancy.status')}</span> {statusLabel(status.code, i18n)}</span>
              <button type="button" disabled={busy} onClick={giveBack}>{t('list.return')}</button>
            </>
          ) : (
            <label>
              <span className="muted">{t('vacancy.status')}</span>{' '}
              <select value={status.code} disabled={busy} onChange={(event) => choose(event.target.value as StatusEvent)}>
                {!(choices as string[]).includes(status.code) && (
                  <option value={status.code} disabled>{statusLabel(status.code, i18n)}</option>
                )}
                {choices.map((choice) => <option key={choice} value={choice}>{statusLabel(choice, i18n)}</option>)}
              </select>
            </label>
          )}
          <span className="muted">{[dateText(status.at, i18n), status.closed ? t('vacancy.closedByEmployer') : '']
            .filter((part) => part !== '').join(' · ')}</span>
        </div>
        <h3>{t('vacancy.history')}</h3>
        <ol className="history">
          {publication.history.map((line, index) => (
            <li key={index}><span className="date">{dateText(line.at, i18n)}</span>{historyLabel(line.event, i18n)}</li>
          ))}
        </ol>
      </section>
    </article>
  );
}
