import { useEffect, useState } from 'react';
import { setStatus, vacancy, type VacancyDetails } from './api';
import { countriesText, openedText } from './format';

interface Props {
  id: number;
  onBack: () => void;
  /** Отметка «Не интересно» поставлена или снята: вернуться к списку и перечитать его страницу. */
  onMarked: () => void;
}

/**
 * Сведения о вакансии (бизнес-описание §6): позиция, работодатель и агентство, страна (не определена — «not
 * specified»), территория удалённой работы, дата открытия («found …», если работодатель даты не дал, §127), ссылка
 * на первичную публикацию; «Не интересно» и «Вернуть» (статусы, §156). Состояние и дата последней проверки не
 * показываются (решение владельца 2026-10-20, §146; аудит §154). Остальные статусы и история — срез 4б, формат и
 * языки — срез 5.
 */
export function VacancyPage({ id, onBack, onMarked }: Props) {
  const [details, setDetails] = useState<VacancyDetails | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const notInterested = details?.publication.timeline.status.code === 'NOT_INTERESTED';

  useEffect(() => {
    vacancy(id).then(setDetails, (failure: Error) => setMessage(failure.message));
  }, [id]);

  async function toggle() {
    if (details === null) {
      return;
    }
    try {
      await setStatus(id, notInterested ? 'RETURNED' : 'NOT_INTERESTED');
      onMarked();
    } catch (failure) {
      setMessage((failure as Error).message);
    }
  }

  if (message !== null) {
    return <p className="error">{message} <button type="button" onClick={onBack}>Back</button></p>;
  }
  if (details === null) {
    return <p>Loading…</p>;
  }
  const { parties, work, publication } = details;
  return (
    <article className="details">
      <button type="button" onClick={onBack}>← Back</button>
      <h2>{details.position}</h2>
      <dl>
        <dt>Employer</dt>
        <dd>{parties.employer ?? 'not specified'}</dd>
        {parties.agency !== null && (<><dt>Agency</dt><dd>{parties.agency}</dd></>)}
        <dt>Country</dt>
        <dd>{countriesText(work)}</dd>
        {work.remoteTerritory !== null && (<><dt>Remote from</dt><dd>{work.remoteTerritory.join(', ')}</dd></>)}
        <dt>Opened</dt>
        <dd>{openedText(publication.timeline.opened)}</dd>
      </dl>
      <div className="actions">
        {publication.url !== null && (
          <a className="button" href={publication.url} target="_blank" rel="noopener noreferrer">Open the posting</a>
        )}
        <button type="button" onClick={toggle}>
          {notInterested ? 'Return to the list' : 'Not interested'}
        </button>
      </div>
    </article>
  );
}
