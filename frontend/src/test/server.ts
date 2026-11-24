import { vi } from 'vitest';

/** A request the fake backend received. */
export interface Recorded {
  method: string;
  path: string;
  body: unknown;
  headers: Record<string, string>;
}

type Handler = (request: Recorded) => { status?: number; body?: unknown } | unknown;

/**
 * A fake backend for unit tests: routes are matched on "METHOD /path" (the query string is ignored unless the
 * route contains '?'). Unmatched requests answer 404 so a missing route is visible in the test.
 */
/** A fixed answer (any JSON value) or a function of the request. */
export type Route = Handler | object | string | number | boolean | null;

export function fakeBackend(routes: Record<string, Route>) {
  const requests: Recorded[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://localhost');
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = (init.headers ?? {}) as Record<string, string>;
    let body: unknown = init.body;
    if (typeof init.body === 'string') {
      try { body = JSON.parse(init.body); } catch { body = init.body; }
    } else if (init.body instanceof URLSearchParams) {
      body = Object.fromEntries(init.body);
    }
    const recorded: Recorded = { method, path: url.pathname + url.search, body, headers };
    requests.push(recorded);
    const key = Object.keys(routes).find((k) => {
      const [m, p] = k.split(' ');
      return m === method && (p.includes('?') ? p === url.pathname + url.search : p === url.pathname);
    });
    if (!key) {
      return json(404, { code: 'NOT_FOUND', detail: `No fake route for ${method} ${url.pathname}` });
    }
    const route = routes[key];
    const answer = typeof route === 'function' ? (route as Handler)(recorded) : route;
    if (answer && typeof answer === 'object' && 'status' in (answer as object) && 'body' in (answer as object)) {
      const { status, body: b } = answer as { status: number; body: unknown };
      return json(status, b);
    }
    return json(200, answer);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { requests, fetchMock };
}

function json(status: number, body: unknown): Response {
  return new Response(status === 204 || body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

/** Minimal EventSource for tests: records instances so a test can emit events. */
export class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  readonly url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, Set<(e: MessageEvent<string>) => void>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (e: MessageEvent<string>) => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(listener);
  }

  removeEventListener(type: string, listener: (e: MessageEvent<string>) => void) {
    this.listeners.get(type)?.delete(listener);
  }

  open() {
    this.readyState = 1;
    this.onopen?.();
  }

  emit(type: string, data: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(data) });
    this.listeners.get(type)?.forEach((l) => l(event));
  }

  close() {
    this.readyState = 2;
  }
}
