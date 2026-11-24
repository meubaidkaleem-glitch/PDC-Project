import { expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { stack } from './stack';

/** Signs in through the sign-in form. */
export async function signIn(page: Page) {
  await page.goto('/');
  await page.getByLabel('User name').fill(stack.adminUser);
  await page.getByLabel('Password').fill(stack.adminPassword);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
}

/** Calls the API with the page's session and CSRF token, as the interface does. */
export async function apiCall<T>(page: Page, method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
  const response = method === 'GET'
    ? await page.request.get(path)
    : await page.request.post(path, { data: body ?? {}, headers: { 'X-XSRF-TOKEN': decodeURIComponent(token) } });
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return (response.status() === 204 ? undefined : await response.json()) as T;
}

export interface Summary {
  id: string;
  name: string;
  status: string;
  completedRuns: number;
  errorMessage: string | null;
}

/** Waits until an experiment has finished and returns its summary. */
export async function awaitExperiment(page: Page, id: string, timeoutMs = 180_000): Promise<Summary> {
  let summary: Summary | undefined;
  await expect.poll(async () => {
    summary = (await apiCall<{ summary: Summary }>(page, 'GET', `/api/v1/experiments/${id}`)).summary;
    return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(summary.status);
  }, { timeout: timeoutMs, intervals: [500] }).toBe(true);
  return summary!;
}

/** A matrix multiplication task. */
export function matrixTask(size: number, mode: string, execution: Record<string, unknown> = {}) {
  return { algorithm: 'matrix.multiply', parameters: { rowsA: size, colsA: size, colsB: size }, inputs: {},
    execution: { mode, ...execution } };
}
