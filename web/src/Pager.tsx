interface PagerProps {
  /** Текущая страница, с 1. */
  page: number;
  /** Всего страниц. */
  pages: number;
  busy: boolean;
  onPage: (page: number) => void;
}

interface SizeProps {
  size: number;
  sizes: number[];
  /** Идёт загрузка — выбор недоступен: ответы на быстрые нажатия могли бы прийти не по порядку. */
  busy: boolean;
  onSize: (size: number) => void;
}

/** Столько номеров и меньше показываются все; больше — первая, последняя и соседи текущей, пропуски — «…». */
const ALL_NUMBERS_UP_TO = 7;

/**
 * Номера страниц (решение владельца 2026-10-20, §147): «‹ 1 2 3 … 7 ›». Текущая страница отмечена
 * {@code aria-current} — экранный диктор её называет.
 */
export function Pager({ page, pages, busy, onPage }: PagerProps) {
  if (pages <= 1) {
    return null;
  }
  return (
    <nav className="pager" aria-label="Pages">
      <button type="button" aria-label="Previous page" disabled={busy || page <= 1} onClick={() => onPage(page - 1)}>
        ‹
      </button>
      {numbers(page, pages).map((number, index) => (number === null
        ? <span key={'gap' + index} className="gap">…</span>
        : (
          <button key={number} type="button" className={number === page ? 'current' : ''}
            aria-current={number === page ? 'page' : undefined} disabled={busy} onClick={() => onPage(number)}>
            {number}
          </button>
        )))}
      <button type="button" aria-label="Next page" disabled={busy || page >= pages} onClick={() => onPage(page + 1)}>
        ›
      </button>
    </nav>
  );
}

/** Выбор числа строк на странице: «Per page: 20 · 50 · 100». */
export function PageSize({ size, sizes, busy, onSize }: SizeProps) {
  return (
    <div className="page-size" role="group" aria-label="Vacancies per page">
      <span className="muted">Per page:</span>
      {sizes.map((choice) => (
        <button key={choice} type="button" className={choice === size ? 'current' : ''}
          aria-pressed={choice === size} disabled={busy} onClick={() => onSize(choice)}>
          {choice}
        </button>
      ))}
    </div>
  );
}

/** Номера для показа; {@code null} — пропуск. */
function numbers(page: number, pages: number): (number | null)[] {
  if (pages <= ALL_NUMBERS_UP_TO) {
    return [...Array(pages).keys()].map((index) => index + 1);
  }
  const shown = [1, page - 1, page, page + 1, pages].filter((number) => number >= 1 && number <= pages);
  const unique = [...new Set(shown)].sort((left, right) => left - right);
  const result: (number | null)[] = [];
  unique.forEach((number, index) => {
    if (index > 0 && number - unique[index - 1] > 1) {
      result.push(null);
    }
    result.push(number);
  });
  return result;
}
