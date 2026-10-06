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

async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
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
  if (method !== 'GET' && csrfToken() === undefined) {
    await fetch(TOKEN_SOURCE, { credentials: 'same-origin' });
  }
  let response = await send(method, path, body);
  if (response.status === 403 && method !== 'GET') {
    await fetch(TOKEN_SOURCE, { credentials: 'same-origin' });
    response = await send(method, path, body);
  }
  const text = await response.text();
  const data = text.length > 0 ? JSON.parse(text) : undefined;
  if (!response.ok) {
    throw new ApiError(response.status, data?.detail ?? data?.title ?? response.statusText, data?.errors ?? []);
  }
  return data as T;
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
