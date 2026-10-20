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

/** @returns вошедший пользователь; null — не вошёл (401) или в сессии вход администратора (403) */
export async function currentAccount(): Promise<Account | null> {
  try {
    return await request<Account>('GET', '/api/v1/me');
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
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

/** Формат работы в условиях поиска; null — любой (Any). Гибрида нет: гибридные вакансии выдаются при любом формате (§125). */
export type WorkFormat = 'OFFICE' | 'REMOTE';

/** Условия поиска (технический документ §8; бизнес-описание §7.2). */
export interface SearchCondition {
  countries: string[];
  /** Коды позиций (от 1 до 10, §126). */
  positions: string[];
  format: WorkFormat | null;
  portionLimit: number;
}

/** Условия к сохранению: лимит null — значение по умолчанию сервера. */
export interface SearchConditionInput {
  countries: string[];
  positions: string[];
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
  /** Формат вакансии; на экране не показывается (решение владельца 2026-10-16, §124). */
  format: 'OFFICE' | 'HYBRID' | 'REMOTE' | null;
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
  opened: Opened;
}

/**
 * Когда вакансия открыта (§127): byEmployer — дата размещения работодателем (начало суток UTC); иначе — когда
 * вакансия впервые найдена системой.
 */
export interface Opened {
  at: string;
  byEmployer: boolean;
}

/** Страница списка по номеру (§151): открытые позже сверху (отмеченные — отмеченные позже); total — во всём списке. */
export interface VacancyPage {
  items: VacancyItem[];
  page: number;
  size: number;
  total: number;
}

/** Публикация и её проверка. */
export interface Publication {
  url: string | null;
  opened: Opened;
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

function listPage(path: string, page: number, size: number): Promise<VacancyPage> {
  return request<VacancyPage>('GET', path + '?page=' + page + '&size=' + size);
}

/** @returns страница накопленного списка: номер с 1, строк на странице */
export function vacancies(page: number, size: number): Promise<VacancyPage> {
  return listPage('/api/v1/me/vacancies', page, size);
}

/** @returns страница отмеченных «Не интересно» */
export function unsuitableVacancies(page: number, size: number): Promise<VacancyPage> {
  return listPage('/api/v1/me/unsuitable', page, size);
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

/** Вошедший администратор. */
export interface Admin {
  username: string;
}

/** Строка показателя: код, разрез ('' — итог), значения по дням таблицы (null — нет строки за день). */
export interface MetricRow {
  metric: string;
  dimension: string;
  values: (number | null)[];
}

/** Показатели ядра страны по дням (дни — новые первыми). */
export interface MetricsTable {
  country: string;
  countries: string[];
  days: string[];
  rows: MetricRow[];
}

/** @returns вошедший администратор; null — не вошёл (401) или вошёл пользователь (403) */
export async function currentAdmin(): Promise<Admin | null> {
  try {
    return await request<Admin>('GET', '/api/v1/admin/me');
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      return null;
    }
    throw error;
  }
}

/** Вход администратора: новая сессия. */
export function adminLogin(username: string, password: string): Promise<Admin> {
  return request<Admin>('POST', '/api/v1/admin/login', { username, password });
}

/**
 * Показатели ядра.
 *
 * @param country страна; null — первая страна с кодом ISO
 * @param days    сколько последних дней
 */
export function metrics(country: string | null, days: number): Promise<MetricsTable> {
  const query = new URLSearchParams({ days: String(days) });
  if (country !== null) {
    query.set('country', country);
  }
  return request<MetricsTable>('GET', '/api/v1/admin/metrics?' + query.toString());
}

/**
 * Группа компании (бизнес-описание §4.2): YES — своя кадровая страница читается, PORTAL — только вакансии
 * государственного портала (§143), MAYBE — может быть, NO — нет; NOT_EMPLOYER — отметка администратора «не
 * работодатель» (§138).
 */
export type CompanyGroup = 'YES' | 'PORTAL' | 'MAYBE' | 'NO' | 'NOT_EMPLOYER';

/** Группа и итог проверки компании. */
export interface CompanyCheck {
  group: CompanyGroup;
  reason: string | null;
  site: string | null;
  careerUrl: string | null;
}

/** Компания из реестра. */
export interface CompanyItem {
  id: number;
  name: string;
  registrationNumber: string;
  check: CompanyCheck;
}

/** Страница сомнительных компаний; next — курсор следующей, null — последняя. */
export interface CompanyPage {
  items: CompanyItem[];
  next: number | null;
}

/** Адрес кадровой страницы, введённый пользователем, и его состояние. */
export interface CareerRequest {
  id: number;
  companyName: string;
  url: string;
  status: { state: 'PENDING' | 'CONNECTED' | 'NOT_CONNECTED'; reason: string | null; createdAt: string };
}

/** Поиск компании по названию или IČO. */
export function searchCompanies(query: string): Promise<CompanyItem[]> {
  return request<CompanyItem[]>('GET', '/api/v1/companies?query=' + encodeURIComponent(query));
}

/** Сомнительные компании: «может быть» и «нет» с признаком найма. */
export function doubtfulCompanies(after: number | null): Promise<CompanyPage> {
  return request<CompanyPage>('GET', '/api/v1/companies/doubtful' + (after === null ? '' : '?after=' + after));
}

/** Ввод адреса кадровой страницы компании. */
export function addCareerPage(companyId: number, url: string): Promise<CareerRequest> {
  return request<CareerRequest>('POST', '/api/v1/companies/' + companyId + '/career-page', { url });
}

/** Адреса, введённые пользователем, новые первыми. */
export function careerRequests(): Promise<CareerRequest[]> {
  return request<CareerRequest[]>('GET', '/api/v1/me/career-requests');
}

/** Личное скрытие компании (§138): пропадает из поиска и сомнительных только у этого пользователя. */
export function setCompanyHidden(companyId: number, hidden: boolean): Promise<void> {
  return request<void>(hidden ? 'PUT' : 'DELETE', '/api/v1/me/hidden-companies/' + companyId);
}

/** Компании, скрытые пользователем. */
export function hiddenCompanies(): Promise<CompanyItem[]> {
  return request<CompanyItem[]>('GET', '/api/v1/me/hidden-companies');
}

/** Поиск компании администратором. */
export function adminSearchCompanies(query: string): Promise<CompanyItem[]> {
  return request<CompanyItem[]>('GET', '/api/v1/admin/companies?query=' + encodeURIComponent(query));
}

/** Отметка администратора «не работодатель» (§138), общая для всех. */
export function setNotEmployer(companyId: number, notEmployer: boolean): Promise<void> {
  return request<void>(notEmployer ? 'PUT' : 'DELETE', '/api/v1/admin/companies/' + companyId + '/not-employer');
}

/** Состояние ядра сейчас (§142): чтения, задания, тревоги NO_RECENT_READ и QUEUE_STALLED. */
export interface CoreStatus {
  checkedAt: string;
  reads: { lastSuccessAt: string | null; lastHour: number; failedLastHour: number };
  tasks: { queued: number; oldestQueuedAt: string | null; failedLastDay: number };
  warnings: string[];
}

/** Состояние ядра для администратора. */
export function coreStatus(): Promise<CoreStatus> {
  return request<CoreStatus>('GET', '/api/v1/admin/status');
}
