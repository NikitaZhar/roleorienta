import { useEffect, useState } from 'react';
import { type CatalogItem, type Direction, type GroupNode, occupations } from './api';
import { errorText, useI18n } from './i18n';

interface Props {
  /** Коды уже выбранных позиций и групп: отмечены и не выбираются повторно. */
  chosen: string[];
  /** Выбрано 10 (§126): больше не выбрать — пункты недоступны, над списком — пояснение (аудит §211). */
  full: boolean;
  /** Выбраны позиция словаря или группа ISCO-08 (код группы — 4 цифры). */
  onChoose: (item: CatalogItem) => void;
}

/** Строка ищется с начала слова, без учёта регистра (как подсказка при вводе, §205); знаки выражения экранируются. */
function wordStart(query: string): RegExp | null {
  const text = query.trim();
  return text.length < 2 ? null
    : new RegExp('(^|[^\\p{L}\\p{N}])' + text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'iu');
}

/**
 * «Полный каталог» (Full catalog) в условиях (§204, §208): направления (подразделы ISCO-08) — заголовками со стрелкой;
 * нажатие раскрывает направление — его группы, под группой — позиции словаря с отступом. Выбор — одним нажатием:
 * группа — все вакансии группы (пометка), позиция — только вакансии этой позиции. Поле поиска сверху оставляет
 * группы и позиции, в названии (у позиции — и в эквивалентном названии) которых есть строка, направления с ними
 * раскрыты. Названия — на языке интерфейса; смена языка перечитывает каталог.
 */
export function PositionCatalog({ chosen, full, onChoose }: Props) {
  const { language, t } = useI18n();
  const [directions, setDirections] = useState<Direction[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    occupations().then((items) => {
      if (current) {
        setDirections(items);
      }
    }, (failure: unknown) => {
      if (current) {
        setMessage(errorText(failure, t));
      }
    });
    return () => {
      current = false;
    };
  }, [language]);

  if (directions === null) {
    return message === null ? <p className="muted">{t('app.loading')}</p> : <p className="error">{message}</p>;
  }
  const word = wordStart(query);
  const shown = word === null ? directions : directions.map((direction) => ({
    ...direction,
    groups: direction.groups.map((group) => {
      const positions = group.positions.filter((position) => word.test(position.name)
        || position.titles.some((title) => word.test(title)));
      return word.test(group.name) || positions.length > 0 ? { ...group, positions } : null;
    }).filter((group): group is GroupNode => group !== null),
  })).filter((direction) => direction.groups.length > 0);
  return (
    <div className="catalog-box">
      <input type="search" className="catalog-search" placeholder={t('conditions.catalogSearch')} value={query}
        aria-label={t('conditions.catalogSearch')} onChange={(event) => setQuery(event.target.value)} />
      {full && <p className="muted">{t('conditions.catalogFull')}</p>}
      {shown.length === 0 && <p className="muted">{t('conditions.catalogNone')}</p>}
      <ul className="catalog">
        {shown.map((direction) => {
          const expanded = word !== null || open === direction.code;
          return (
            <li key={direction.code}>
              <button type="button" className="catalog-direction" aria-expanded={expanded}
                onClick={() => setOpen(open === direction.code ? null : direction.code)}>
                <span aria-hidden="true">{expanded ? '▾' : '▸'}</span> {direction.name}
              </button>
              {expanded && (
                <ul className="choices">
                  {direction.groups.map((group) => (
                    <li key={group.code}>
                      <button type="button" disabled={full || chosen.includes(group.code)}
                        onClick={() => onChoose({ code: group.code, name: group.name })}>
                        {group.name} <span className="muted">· {t('conditions.wholeGroup')}</span>
                      </button>
                      {group.positions.length > 0 && (
                        <ul className="choices nested">
                          {group.positions.map((position) => (
                            <li key={position.code}>
                              <button type="button" disabled={full || chosen.includes(position.code)}
                                onClick={() => onChoose({ code: position.code, name: position.name })}>
                                {position.name}
                              </button>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
