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
  /** Язык интерфейса (§162). */
  language: 'en' | 'ru';
  /** Частота сводки на почту (§164). */
  digestFrequency: DigestFrequency;
  /** Часовой пояс сводки (IANA): при открытии приложения сверяется с поясом браузера (аудит §165). */
  timeZone: string;
}

/** Частота сводки на почту (§164): каждый день, по будням, раз в неделю (понедельник), не присылать. */
export type DigestFrequency = 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'NEVER';

/** Часовой пояс браузера (IANA): письмо-сводка приходит в 8:00 по нему (§164). */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const TOKEN_SOURCE = '/api/v1/countries';

function csrfToken(): string | undefined {
  const cookie = document.cookie.split('; ').find((part) => part.startsWith(CSRF_COOKIE + '='));
  return cookie === undefined ? undefined : decodeURIComponent(cookie.substring(CSRF_COOKIE.length + 1));
}

/** Язык интерфейса для названий групп ISCO-08 в ответах (§204): заголовок Accept-Language; ставит I18nProvider. */
let requestLanguage = 'en';

/** @param language язык интерфейса — с ним уходят следующие запросы */
export function setRequestLanguage(language: string) {
  requestLanguage = language;
}

async function send(method: string, path: string, body?: unknown, ifMatch?: string | null): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', 'Accept-Language': requestLanguage };
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

/** Регистрация с языком экрана входа и часовым поясом браузера (вход — отдельным запросом). */
export function register(email: string, password: string, language: 'en' | 'ru'): Promise<Account> {
  return request<Account>('POST', '/api/v1/auth/register', { email, password, language, timeZone: browserTimeZone() });
}

/** Настройки пользователя (§162, §164): не переданное не меняется. */
export interface SettingsChange {
  language?: 'en' | 'ru';
  digestFrequency?: DigestFrequency;
  timeZone?: string;
}

/** Сохранить настройки пользователя. */
export function saveSettings(settings: SettingsChange): Promise<Account> {
  return request<Account>('PUT', '/api/v1/me/settings', settings);
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
  /** Позиции и группы ISCO-08 (вместе от 1 до 10, §126, §204); выключенная позиция остаётся в условиях (§160). */
  positions: ChosenPosition[];
  format: WorkFormat | null;
  portionLimit: number;
}

/** Позиция или группа в условиях: код, название, включена ли (выключенная позиция — с пометкой, аудит §165). */
export interface ChosenPosition {
  code: string;
  name: string;
  active: boolean;
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

/** Вариант подсказки: позиция словаря или группа ISCO-08 (group — подходят все вакансии группы, §204). */
export interface PositionOption {
  code: string;
  name: string;
  group: boolean;
}

/** @returns до 20 вариантов: сначала позиции словаря, затем группы, в названии (или синониме) которых есть query */
export function positions(query: string): Promise<PositionOption[]> {
  return request<PositionOption[]>('GET', '/api/v1/positions?query=' + encodeURIComponent(query));
}

/** Группа ISCO-08 в «Полном каталоге» (Full catalog): позиции словаря внутри неё. */
export interface GroupNode {
  code: string;
  name: string;
  positions: CatalogItem[];
}

/** Направление в «Полном каталоге» (Full catalog): подраздел ISCO-08 с группами. */
export interface Direction {
  code: string;
  name: string;
  groups: GroupNode[];
}

/** @returns все направления с группами и позициями (§204) */
export function occupations(): Promise<Direction[]> {
  return request<Direction[]>('GET', '/api/v1/occupations');
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
  /** Формат вакансии: в строке списка — меткой Office / Remote, гибрид — без метки (§192); в сведениях — «Format» (§158). */
  format: 'OFFICE' | 'HYBRID' | 'REMOTE' | null;
  remoteTerritory: string[] | null;
  countryUncertain: boolean;
}

/** Вакансия в списке. */
export interface VacancyItem {
  id: number;
  /**
   * Позиция и адрес первичной публикации — открывается прямо из строки (§192); matches — к каким позициям условий
   * подошла (§194; в «Моих откликах» и «Не интересно» — пусто).
   */
  posting: { position: string; url: string; matches: string[] };
  parties: Parties;
  work: Work;
  timeline: Timeline;
}

/** Статус, который ставит пользователь или приложение (§156); RETURNED — «Вернуть» из «Не интересно». */
export type StatusEvent = 'VIEWED' | 'NOT_INTERESTED' | 'APPLIED' | 'INTERVIEW' | 'REJECTED' | 'RETURNED';

/**
 * Текущий статус вакансии у пользователя (§156): NEW — «Новая» (сведения ещё не открывались); at — когда поставлен
 * (у новой — когда получена); closed — закрыта работодателем.
 */
export interface UserStatus {
  code: 'NEW' | 'VIEWED' | 'NOT_INTERESTED' | 'APPLIED' | 'INTERVIEW' | 'REJECTED';
  at: string | null;
  closed: boolean;
}

/** Когда вакансия открыта и её статус у пользователя. */
export interface Timeline {
  opened: Opened;
  status: UserStatus;
  /** Впервые получена после прошлого захода в результаты (§191) — точка у даты; в других списках и сведениях — false. */
  sinceLastVisit: boolean;
}

/**
 * Когда вакансия открыта (§127): byEmployer — дата размещения работодателем (начало суток UTC); иначе — когда
 * вакансия впервые найдена системой.
 */
export interface Opened {
  at: string;
  byEmployer: boolean;
}

/**
 * Страница списка по номеру (§151): результаты — открытые позже сверху, «Мои отклики» и «Не интересно» — статус
 * изменён позже сверху; total — во всём списке; newCount — новых (только у результатов, §156).
 */
export interface VacancyPage {
  items: VacancyItem[];
  page: number;
  size: number;
  total: number;
  newCount: number;
}

/**
 * Строка истории (§156): RECEIVED — «Получена», CLOSED — «Закрыта работодателем», иначе статус; RETURNED —
 * «Возвращена».
 */
export interface HistoryLine {
  event: 'RECEIVED' | 'CLOSED' | StatusEvent;
  at: string;
}

/** Требование к языку из текста публикации (§158): код ISO 639-1, обязателен или желателен. */
export interface LanguageRequirement {
  language: string;
  required: boolean;
}

/**
 * Публикация и работа пользователя с вакансией: ссылка, статус, история (раньше сверху), требования к языку (пусто —
 * «не указано»).
 */
export interface Publication {
  url: string | null;
  timeline: Timeline;
  history: HistoryLine[];
  languages: LanguageRequirement[];
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

/** Отбор внутри результатов: позиция условий (§194) и «после прошлого захода» (§200). */
export interface ResultFilter {
  position?: string;
  sinceLastVisit?: boolean;
}

/**
 * @param filter отбор внутри результатов; нет — все
 * @returns страница накопленного списка: номер с 1, строк на странице
 */
export function vacancies(page: number, size: number, filter: ResultFilter = {}): Promise<VacancyPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filter.position !== undefined) {
    query.set('position', filter.position);
  }
  if (filter.sinceLastVisit === true) {
    query.set('since', 'true');
  }
  return request<VacancyPage>('GET', '/api/v1/me/vacancies?' + query.toString());
}

/** Позиция условий и число видимых вакансий (§194: метки отбора над результатами). */
export interface PositionCount {
  code: string;
  name: string;
  count: number;
}

/**
 * Метки отбора над результатами: позиции условий с числами (§194) и сколько пришло после прошлого захода (§200;
 * число на экране не показывается — только метка, если больше нуля).
 */
export interface ResultFilters {
  positions: PositionCount[];
  sinceLastVisit: number;
}

/** @returns метки отбора над результатами; условия не заданы — пусто */
export function resultFilters(): Promise<ResultFilters> {
  return request<ResultFilters>('GET', '/api/v1/me/vacancies/filters');
}

/** @returns страница отмеченных «Не интересно» */
export function unsuitableVacancies(page: number, size: number): Promise<VacancyPage> {
  return listPage('/api/v1/me/unsuitable', page, size);
}

/** @returns страница «Мои отклики» (§156) */
export function responses(page: number, size: number): Promise<VacancyPage> {
  return listPage('/api/v1/me/responses', page, size);
}

/** @returns страница «Employer declined» — завершённые отклики, под действующими (§196) */
export function declinedResponses(page: number, size: number): Promise<VacancyPage> {
  return request<VacancyPage>('GET', '/api/v1/me/responses?declined=true&page=' + page + '&size=' + size);
}

/** @returns сведения о вакансии */
export function vacancy(id: number): Promise<VacancyDetails> {
  return request<VacancyDetails>('GET', '/api/v1/me/vacancies/' + id);
}

/**
 * Смена статуса вакансии (§156). Часовой пояс браузера задаёт день: статус того же дня заменяет прежний выбор.
 */
export function setStatus(id: number, status: StatusEvent): Promise<void> {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return request<void>('PUT', '/api/v1/me/vacancies/' + id + '/status', { status, zone });
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

/** Ввод адреса кадровой страницы компании. */
export function addCareerPage(companyId: number, url: string): Promise<CareerRequest> {
  return request<CareerRequest>('POST', '/api/v1/companies/' + companyId + '/career-page', { url });
}

/** Адреса, введённые пользователем, новые первыми. */
export function careerRequests(): Promise<CareerRequest[]> {
  return request<CareerRequest[]>('GET', '/api/v1/me/career-requests');
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

/** Названия позиции словаря по видам (§160): по одному слову или словосочетанию. */
export interface PositionTerms {
  titles: string[];
  roles: string[];
  keywords: string[];
  exclude: string[];
}

/** Позиция словаря для администратора; код не меняется, выключенная не сопоставляется и не предлагается. */
export interface DictionaryPosition {
  id: number;
  code: string;
  name: string;
  /** Группа ISCO-08 (4 цифры, §204; null — не задана) и включена ли. */
  placement: { group: string | null; active: boolean };
  terms: PositionTerms;
}

/** Словарь позиций целиком: номер версии растёт при каждом изменении (§160). */
export interface Dictionary {
  version: number;
  contentHits: number;
  otherSpecializations: string[];
  positions: DictionaryPosition[];
}

/** Словарь позиций для администратора. */
export function dictionary(): Promise<Dictionary> {
  return request<Dictionary>('GET', '/api/v1/admin/dictionary');
}

/** Новая позиция словаря: 409 — код уже есть; 400 — группы ISCO-08 нет в справочнике. */
export function addPosition(code: string, name: string, group: string, terms: PositionTerms): Promise<Dictionary> {
  return request<Dictionary>('POST', '/api/v1/admin/positions', { code, name, group, terms });
}

/** Изменение позиции словаря (название, включена ли, группа ISCO-08, названия по видам). */
export function changePosition(id: number, name: string, active: boolean, group: string,
  terms: PositionTerms): Promise<Dictionary> {
  return request<Dictionary>('PUT', '/api/v1/admin/positions/' + id, { name, active, group, terms });
}

/** Общие настройки словаря. */
export function changeDictionarySettings(contentHits: number, otherSpecializations: string[]): Promise<Dictionary> {
  return request<Dictionary>('PUT', '/api/v1/admin/dictionary', { contentHits, otherSpecializations });
}

/** Запрос подсказки позиции без результата (§205): строка, сколько раз, когда последний раз. */
export interface SearchMiss {
  query: string;
  misses: number;
  lastAt: string;
}

/** @returns что искали и не нашли, чаще — первыми (не больше 100) */
export function searchMisses(): Promise<SearchMiss[]> {
  return request<SearchMiss[]>('GET', '/api/v1/admin/position-misses');
}

/** Убрать запрос из списка; повторится — появится снова. */
export function forgetSearchMiss(query: string): Promise<void> {
  return request<void>('DELETE', '/api/v1/admin/position-misses?query=' + encodeURIComponent(query));
}
