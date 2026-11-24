/** HTTP client for the backend API: session cookie, CSRF header, RFC 9457 errors. */

export interface Violation {
  field: string;
  code: string;
  message: string;
}

/** An error answered by the API (or a network failure, with status 0). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly violations: Violation[];

  constructor(status: number, code: string, message: string, violations: Violation[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.violations = violations;
  }
}

/** Fired when a request answers 401, so the application can return to the sign-in page. */
export const SESSION_EXPIRED = 'districompute:session-expired';

function csrfToken(): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.substring('XSRF-TOKEN='.length)) : undefined;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  form?: FormData | URLSearchParams;
  signal?: AbortSignal;
}

/** Calls the API and parses JSON. Throws {@link ApiError} for any non-2xx answer. */
export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? (options.body !== undefined || options.form ? 'POST' : 'GET');
  const headers: Record<string, string> = { Accept: 'application/json' };
  let body: BodyInit | undefined;
  if (options.form) {
    body = options.form;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  if (method !== 'GET') {
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  let response: Response;
  try {
    response = await fetch(path, { method, headers, body, credentials: 'same-origin', signal: options.signal });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', 'The server cannot be reached. Check your connection and that the backend runs.');
  }
  if (response.status === 401 && !path.startsWith('/api/v1/auth/')) {
    window.dispatchEvent(new Event(SESSION_EXPIRED));
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const data = text ? safeJson(text) : undefined;
  if (!response.ok) {
    const problem = (data ?? {}) as { code?: string; detail?: string; title?: string; violations?: Violation[] };
    throw new ApiError(response.status, problem.code ?? String(response.status),
      problem.detail ?? problem.title ?? `Request failed with status ${response.status}`, problem.violations ?? []);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** Makes sure the CSRF cookie exists (any GET sets it). */
export async function ensureCsrf(): Promise<void> {
  if (!csrfToken()) await api('/api/v1/auth/session');
}
