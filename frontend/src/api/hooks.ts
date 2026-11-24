import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from './client';

export interface Resource<T> {
  data: T | undefined;
  error: ApiError | undefined;
  loading: boolean;
  reload: () => void;
}

/** Loads a resource; {@code path === null} loads nothing. Reloading keeps the previous data while loading. */
export function useApi<T>(path: string | null): Resource<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<ApiError>();
  const [loading, setLoading] = useState<boolean>(path !== null);
  const [version, setVersion] = useState(0);
  const reload = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    if (path === null) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    api<T>(path, { signal: controller.signal })
      .then((value) => {
        setData(value);
        setError(undefined);
      })
      .catch((e: unknown) => {
        if (e instanceof DOMException && e.name === 'AbortError') return;
        setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, version]);

  return { data, error, loading, reload };
}

export type StreamState = 'connecting' | 'open' | 'reconnecting' | 'closed';

/**
 * Subscribes to a Server-Sent-Events stream. The browser reconnects by itself and sends Last-Event-ID, so the
 * backend replays missed events. {@code onEvent} receives every named event.
 */
export function useEventStream(path: string | null, types: string[], onEvent: (type: string, data: unknown) => void):
  StreamState {
  const [state, setState] = useState<StreamState>(path ? 'connecting' : 'closed');
  const handler = useRef(onEvent);
  handler.current = onEvent;
  const typeKey = types.join(',');

  useEffect(() => {
    if (!path || typeof EventSource === 'undefined') {
      setState('closed');
      return;
    }
    const source = new EventSource(path, { withCredentials: true });
    setState('connecting');
    source.onopen = () => setState('open');
    source.onerror = () => setState(source.readyState === EventSource.CLOSED ? 'closed' : 'reconnecting');
    const listeners = typeKey.split(',').map((type) => {
      const listener = (e: MessageEvent<string>) => {
        try {
          handler.current(type, JSON.parse(e.data));
        } catch {
          handler.current(type, e.data);
        }
      };
      source.addEventListener(type, listener as EventListener);
      return [type, listener] as const;
    });
    return () => {
      listeners.forEach(([type, l]) => source.removeEventListener(type, l as EventListener));
      source.close();
    };
  }, [path, typeKey]);

  return state;
}

/** Calls {@code fn} at most once per {@code ms}, always running the last call. */
export function useThrottled(fn: () => void, ms: number): () => void {
  const last = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const latest = useRef(fn);
  latest.current = fn;
  useEffect(() => () => clearTimeout(timer.current), []);
  return useCallback(() => {
    const now = Date.now();
    const wait = last.current + ms - now;
    if (wait <= 0) {
      last.current = now;
      latest.current();
    } else if (!timer.current) {
      timer.current = setTimeout(() => {
        timer.current = undefined;
        last.current = Date.now();
        latest.current();
      }, wait);
    }
  }, [ms]);
}
