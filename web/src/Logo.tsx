interface Props {
  /** Сторона знака в пикселях. */
  size: number;
}

/** Восемь лучей картушки: четыре главных (по две половины) и четыре промежуточных; координаты — в поле 32×32. */
const MINOR = 'M22.4 9.6L16.85 16.85L15.15 15.15ZM9.6 22.4L16.85 16.85L15.15 15.15Z'
  + 'M22.4 22.4L16.85 15.15L15.15 16.85ZM9.6 9.6L16.85 15.15L15.15 16.85Z';
const LIGHT_HALVES = 'M16 4L16 16L13.7 13.7ZM28 16L16 16L18.3 13.7ZM16 28L16 16L18.3 18.3ZM4 16L16 16L13.7 18.3Z';
const DARK_HALVES = 'M16 4L18.3 13.7L16 16ZM28 16L18.3 18.3L16 16ZM16 28L13.7 18.3L16 16ZM4 16L13.7 13.7L16 16Z';

/** Меньше этого размера промежуточные лучи сливаются — рисуются только главные (технический документ §15, шаг 2). */
const MINOR_FROM = 24;

/**
 * Эмблема roleorienta — картушка морского компаса в медном кольце (решение владельца 2026-10-20, стенограмма §147).
 * Цвета — переменные темы ({@code --ink}, {@code --accent}, {@code --muted}), поэтому знак сам меняется в тёмной теме.
 * Для экранного диктора знак декоративный: рядом всегда есть название.
 */
export function Logo({ size }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" className="logo">
      <circle cx="16" cy="16" r="14.5" fill="none" stroke="var(--accent)" strokeWidth="1.6" />
      {size >= MINOR_FROM && <path d={MINOR} fill="var(--muted)" />}
      <path d={LIGHT_HALVES} fill="var(--ink)" />
      <path d={DARK_HALVES} fill="var(--accent)" />
    </svg>
  );
}
