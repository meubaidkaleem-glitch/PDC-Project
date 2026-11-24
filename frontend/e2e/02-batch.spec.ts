import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { apiCall, awaitExperiment, matrixTask, signIn } from './helpers';

test('a benchmark batch runs the modes one after another and its comparison is downloadable', async ({ page }) => {
  await signIn(page);
  const source = await apiCall<{ summary: { id: string } }>(page, 'POST', '/api/v1/experiments',
    { name: 'E2E batch source', task: matrixTask(384, 'SEQUENTIAL_CPU') });
  await awaitExperiment(page, source.summary.id);

  await page.goto('/batches');
  await expect(page.getByRole('option', { name: /E2E batch source/ })).toBeAttached();
  await page.getByLabel('Start from').selectOption(source.summary.id);
  await page.getByLabel('Thread counts').fill('2');
  await page.getByLabel('Repetitions per experiment').fill('2');
  await expect(page.getByText('3 experiments, run in this order:')).toBeVisible();
  await page.getByRole('button', { name: /Submit the batch/ }).click();

  await expect(page.getByRole('heading', { name: 'Benchmark: E2E batch source' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Cancel the batch/ })).toHaveCount(0, { timeout: 180_000 });
  await expect(page.getByText('3 completed')).toBeVisible();

  await page.getByRole('link', { name: /Compare/ }).click();
  await expect(page.getByText('Findings')).toBeVisible();
  await expect(page.getByText(/as fast as|took .* the time of|about the same time/).first()).toBeVisible();
  const group = page.getByRole('group', { name: 'Download this comparison' });
  const [pdf] = await Promise.all([page.waitForEvent('download'), group.getByRole('link', { name: 'PDF' }).click()]);
  expect(readFileSync(await pdf.path()).subarray(0, 4).toString()).toBe('%PDF');
});
