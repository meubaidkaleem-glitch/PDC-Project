import { expect, test } from '@playwright/test';
import { apiCall, awaitExperiment, matrixTask, signIn } from './helpers';
import { killBackend, killWorker, startBackend, startWorker } from './stack';

interface Live {
  partitions: { attempts: { workerId: string | null; status: string }[] }[];
}

test('a worker process killed during a run: its partitions are retried elsewhere and the result is verified', async ({ page }) => {
  await startWorker('e2e-victim');
  await signIn(page);
  const created = await apiCall<{ summary: { id: string } }>(page, 'POST', '/api/v1/experiments', {
    name: 'E2E worker crash',
    task: matrixTask(1500, 'DISTRIBUTED_CPU', { targetPartitions: 24, schedulingPolicy: 'ROUND_ROBIN', maxAttempts: 4 }),
  });
  const id = created.summary.id;

  await expect.poll(async () => {
    const live = await apiCall<Live>(page, 'GET', `/api/v1/experiments/${id}/live`);
    return live.partitions.some((p) => p.attempts.some((a) => a.workerId === 'e2e-victim'
      && ['DISPATCHED', 'ACCEPTED', 'RUNNING'].includes(a.status)));
  }, { timeout: 90_000, intervals: [100] }).toBe(true);
  killWorker('e2e-victim');

  const summary = await awaitExperiment(page, id);
  expect(summary.status).toBe('COMPLETED');
  const detail = await apiCall<{ runs: { verificationPassed: boolean }[]; metrics: { name: string; median: number }[] }>(
    page, 'GET', `/api/v1/experiments/${id}`);
  expect(detail.runs[0].verificationPassed).toBe(true);
  expect(detail.metrics.find((m) => m.name === 'retries')!.median).toBeGreaterThanOrEqual(1);

  await page.goto(`/experiments/${id}`);
  await page.getByRole('tab', { name: /Events/ }).click();
  await expect(page.getByText('partition retried').first()).toBeVisible();
  await page.goto('/workers');
  await expect(page.getByRole('row', { name: /e2e-victim/ }).getByText(/lost|disconnected/)).toBeVisible({ timeout: 60_000 });
});

test('a backend crash during a run: after the restart the experiment is marked interrupted and work continues', async ({ page }) => {
  await signIn(page);
  const created = await apiCall<{ summary: { id: string } }>(page, 'POST', '/api/v1/experiments', {
    name: 'E2E backend crash', task: matrixTask(1200, 'SEQUENTIAL_CPU', { repetitions: 5 }),
  });
  const id = created.summary.id;
  await expect.poll(async () => (await apiCall<{ summary: { status: string } }>(page, 'GET', `/api/v1/experiments/${id}`))
    .summary.status, { timeout: 60_000 }).toBe('RUNNING');

  await killBackend('SIGKILL');
  await startBackend();
  await signIn(page);

  const summary = await awaitExperiment(page, id, 10_000);
  expect(summary.status).toBe('FAILED');
  expect(summary.errorMessage).toMatch(/restarted/);
  await page.goto(`/experiments/${id}`);
  await expect(page.getByText('failed', { exact: true }).first()).toBeVisible();

  await expect.poll(async () => (await apiCall<{ workerId: string; connected: boolean }[]>(page, 'GET', '/api/v1/workers'))
    .filter((w) => w.workerId.startsWith('e2e-w') && w.connected).length, { timeout: 90_000 }).toBe(2);
  const after = await apiCall<{ summary: { id: string } }>(page, 'POST', '/api/v1/experiments',
    { name: 'E2E after restart', task: matrixTask(128, 'DISTRIBUTED_CPU') });
  expect((await awaitExperiment(page, after.summary.id)).status).toBe('COMPLETED');
});
