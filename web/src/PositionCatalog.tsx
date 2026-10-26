import { useEffect, useState } from 'react';
import { type CatalogItem, type Direction, occupations } from './api';
import { errorText, useI18n } from './i18n';

interface Props {
  /** Коды уже выбранных позиций и групп: отмечены и не выбираются повторно. */
  chosen: string[];
  /** Выбраны позиция словаря или группа ISCO-08 (код группы — 4 цифры). */
  onChoose: (item: CatalogItem) => void;
}

/**
 * «Полный каталог» (Full catalog) в условиях (§204): для тех, кто не нашёл нужное вводом. Список направлений (подразделы ISCO-08);
 * нажатие раскрывает направление — его группы, под группой — позиции словаря. Выбор — одним нажатием: группа — все
 * вакансии группы (пометка), позиция — только вакансии этой позиции. Названия — на языке интерфейса; смена языка
 * перечитывает список.
 */
export function PositionCatalog({ chosen, onChoose }: Props) {
  const { language, t } = useI18n();
  const [directions, setDirections] = useState<Direction[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
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
  return (
    <ul className="catalog">
      {directions.map((direction) => (
        <li key={direction.code}>
          <button type="button" className="link" aria-expanded={open === direction.code}
            onClick={() => setOpen(open === direction.code ? null : direction.code)}>{direction.name}</button>
          {open === direction.code && (
            <ul className="choices">
              {direction.groups.map((group) => (
                <li key={group.code}>
                  <button type="button" disabled={chosen.includes(group.code)}
                    onClick={() => onChoose({ code: group.code, name: group.name })}>
                    {group.name} <span className="muted">· {t('conditions.wholeGroup')}</span>
                  </button>
                  {group.positions.length > 0 && (
                    <ul className="choices nested">
                      {group.positions.map((position) => (
                        <li key={position.code}>
                          <button type="button" disabled={chosen.includes(position.code)}
                            onClick={() => onChoose(position)}>{position.name}</button>
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}
