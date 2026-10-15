/**
 * Клиент REST API job-api (технический документ §8, §16.17). Тот же origin: cookie сессии отправляется
 * браузером сам. Изменяющие запросы несут CSRF-токен: Spring Security кладёт его в cookie XSRF-TOKEN,
 * клиент повторяет его в заголовке X-XSRF-TOKEN. Нет cookie — токен берётся любым GET (открытый
 * /api/v1/countries); отказ 403 повторяется один раз со свежим токеном.
 */

/** Ошибка API в формате problem+json (RFC 9457). */
export class ApiError extends Error {
  readonly status: number;
  readonly fields: FieldError[];

  constructor(status: number, detail: string, fields: FieldError[] = []) {
    super(detail);
    this.status = status;
    this.fields = fields;
  }
}

/** Ошибка поля запроса: JSON Pointer поля и текст. */
export interface FieldError {
  pointer: string;
  detail: string;
}

/** Учётная запись. */
export interface Account {
  id: number;
  email: string;
}

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const TOKEN_SOURCE = '/api/v1/countries';

function csrfToken(): string | undefined {
  const cookie = document.cookie.split('; ').find((part) => part.startsWith(CSRF_COOKIE + '='));
  return cookie === undefined ? undefined : decodeURIComponent(cookie.substring(CSRF_COOKIE.length + 1));
}

async function send(method: string, path: string, body?: unknown, ifMatch?: string | null): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (ifMatch !== undefined && ifMatch !== null) {
    headers['If-Match'] = ifMatch;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const token = csrfToken();
  if (method !== 'GET' && token !== undefined) {
    headers[CSRF_HEADER] = token;
  }
  return fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** Ответ API: данные и версия ресурса (заголовок ETag; нет — null). */
export interface Versioned<T> {
  data: T;
  etag: string | null;
}

/**
 * Запрос к API с версией ресурса.
 *
 * @param method  HTTP-метод
 * @param path    путь от корня, например /api/v1/me
 * @param body    тело (JSON)
 * @param ifMatch версия для заголовка If-Match (оптимистическая блокировка)
 * @returns разобранный ответ и ETag; 204 — data undefined
 * @throws ApiError ответ не 2xx
 */
export async function exchange<T>(method: string, path: string, body?: unknown,
  ifMatch?: string | null): Promise<Versioned<T>> {
  if (method !== 'GET' && csrfToken() === undefined) {
    await fetch(TOKEN_SOURCE, { credentials: 'same-origin' });
  }
  let response = await send(method, path, body, ifMatch);
  if (response.status === 403 && method !== 'GET') {
    await fetch(TOKEN_SOURCE, { credentials: 'same-origin' });
    response = await send(method, path, body, ifMatch);
  }
  const text = await response.text();
  const json = (response.headers.get('Content-Type') ?? '').includes('json');
  const data = json && text.length > 0 ? JSON.parse(text) : undefined;
  if (!response.ok) {
    throw new ApiError(response.status, data?.detail ?? data?.title ?? response.statusText, data?.errors ?? []);
  }
  return { data: data as T, etag: response.headers.get('ETag') };
}

/**
 * Запрос к API.
 *
 * @param method HTTP-метод
 * @param path   путь от корня, например /api/v1/me
 * @param body   тело (JSON)
 * @returns разобранный ответ; 204 — undefined
 * @throws ApiError ответ не 2xx
 */
export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  return (await exchange<T>(method, path, body)).data;
}

/** @returns вошедший пользователь; null — не вошёл (401) */
export async function currentAccount(): Promise<Account | null> {
  try {
    return await request<Account>('GET', '/api/v1/me');
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      return null;
    }
    throw error;
  }
}

/** Вход: новая сессия. */
export function login(email: string, password: string): Promise<Account> {
  return request<Account>('POST', '/api/v1/auth/login', { email, password });
}

/** Регистрация (вход — отдельным запросом). */
export function register(email: string, password: string): Promise<Account> {
  return request<Account>('POST', '/api/v1/auth/register', { email, password });
}

/** Выход: сессия закрывается. */
export function logout(): Promise<void> {
  return request<void>('POST', '/api/v1/auth/logout');
}

/** Элемент справочника: код и название. */
export interface CatalogItem {
  code: string;
  name: string;
}

/** Формат работы. */
export type WorkFormat = 'OFFICE' | 'HYBRID' | 'REMOTE';

/** Условия поиска (технический документ §8; бизнес-описание §7.2). */
export interface SearchCondition {
  countries: string[];
  position: string;
  format: WorkFormat | null;
  portionLimit: number;
}

/** Условия к сохранению: лимит null — значение по умолчанию сервера. */
export interface SearchConditionInput {
  countries: string[];
  position: string;
  format: WorkFormat | null;
  portionLimit: number | null;
}

/** @returns поддерживаемые страны поиска */
export function countries(): Promise<CatalogItem[]> {
  return request<CatalogItem[]>('GET', '/api/v1/countries');
}

/** @returns до 20 позиций словаря, в коде или названии которых есть query */
export function positions(query: string): Promise<CatalogItem[]> {
  return request<CatalogItem[]>('GET', '/api/v1/positions?query=' + encodeURIComponent(query));
}

/** @returns условия с версией; null — условия ещё не заданы (404) */
export async function searchCondition(): Promise<Versioned<SearchCondition> | null> {
  try {
    return await exchange<SearchCondition>('GET', '/api/v1/me/search-condition');
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

/**
 * Сохраняет условия. Первое сохранение — без версии; изменение — с версией прежних условий
 * (If-Match): чужое изменение между чтением и записью — 412.
 */
export function saveSearchCondition(condition: SearchConditionInput,
  etag: string | null): Promise<Versioned<SearchCondition>> {
  return exchange<SearchCondition>('PUT', '/api/v1/me/search-condition', condition, etag);
}

/** Работодатель и кадровое агентство; null — не указан. */
export interface Parties {
  employer: string | null;
  agency: string | null;
}

/** Где и как выполняется работа; countries «*» — без ограничения страны. */
export interface Work {
  countries: string[];
  format: WorkFormat | null;
  remoteTerritory: string[] | null;
  countryUncertain: boolean;
  formatUncertain: boolean;
}

/** Вакансия в списке. */
export interface VacancyItem {
  id: number;
  position: string;
  parties: Parties;
  work: Work;
  listedAt: string;
}

/** Страница списка: новые сверху; nextCursor null — страниц больше нет. */
export interface VacancyPage {
  items: VacancyItem[];
  nextCursor: string | null;
}

/** Публикация и её проверка. */
export interface Publication {
  url: string | null;
  firstSeenAt: string;
  lastConfirmedAt: string | null;
  state: 'ACTIVE' | 'NEEDS_RECHECK' | 'CLOSED';
  unsuitable: boolean;
}

/** Сведения о вакансии (бизнес-описание §6). */
export interface VacancyDetails {
  id: number;
  position: string;
  parties: Parties;
  work: Work;
  publication: Publication;
}

function page(path: string, cursor: string | null): Promise<VacancyPage> {
  return request<VacancyPage>('GET', cursor === null ? path : path + '?cursor=' + encodeURIComponent(cursor));
}

/** @returns страница накопленного списка */
export function vacancies(cursor: string | null): Promise<VacancyPage> {
  return page('/api/v1/me/vacancies', cursor);
}

/** @returns страница отмеченных «не подходит» */
export function unsuitableVacancies(cursor: string | null): Promise<VacancyPage> {
  return page('/api/v1/me/unsuitable', cursor);
}

/** @returns сведения о вакансии */
export function vacancy(id: number): Promise<VacancyDetails> {
  return request<VacancyDetails>('GET', '/api/v1/me/vacancies/' + id);
}

/** Отметка «не подходит»: вакансия уходит из накопленного списка этого пользователя. */
export function markUnsuitable(id: number): Promise<void> {
  return request<void>('PUT', '/api/v1/me/vacancies/' + id + '/unsuitable');
}

/** Снятие отметки: вакансия возвращается по правилам выдачи. */
export function unmarkUnsuitable(id: number): Promise<void> {
  return request<void>('DELETE', '/api/v1/me/vacancies/' + id + '/unsuitable');
}
