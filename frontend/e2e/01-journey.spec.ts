import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { signIn } from './helpers';

test('a user runs a distributed matrix multiplication on worker processes and downloads its result and report', async ({ page }) => {
  await signIn(page);

  await page.goto('/workers');
  for (const worker of ['e2e-w1', 'e2e-w2']) {
    await expect(page.getByRole('cell', { name: new RegExp(worker) })).toBeVisible();
  }

  await page.goto('/tasks/new');
  await page.getByRole('button', { name: /^Matrix/ }).click();
  await page.getByRole('button', { name: /Matrix multiplication/ }).click();
  await page.getByLabel('rowsA').fill('256');
  await page.getByLabel('colsA').fill('256');
  await page.getByLabel('colsB').fill('256');
  await page.getByRole('button', { name: /Analyse and recommend/ }).click();
  await expect(page.getByText('Execution strategy advisor')).toBeVisible();
  await page.getByLabel('Execution mode').selectOption('DISTRIBUTED_CPU');
  const override = page.getByLabel(/instead of the recommended/);
  if (await override.count()) await override.fill('End-to-end test of the worker processes');
  await page.getByLabel('Experiment name').fill('E2E journey');
  await page.getByRole('button', { name: 'Run with Distributed CPU (workers)' }).click();

  await expect(page.getByRole('heading', { name: 'E2E journey' })).toBeVisible();
  await expect(page.getByText('completed', { exact: true }).first()).toBeVisible({ timeout: 120_000 });
  await expect(page.getByRole('img', { name: /Assignment timeline of \d+ attempts on 2 workers/ })).toBeVisible();

  await page.getByRole('tab', { name: /Results/ }).click();
  await expect(page.getByText('verified', { exact: true })).toBeVisible();
  const [csv] = await Promise.all([page.waitForEvent('download'), page.getByRole('link', { name: /Download CSV/ }).click()]);
  const rows = readFileSync(await csv.path(), 'utf8').trim().split('\n');
  expect(rows).toHaveLength(257);

  await page.getByRole('link', { name: 'Report', exact: true }).click();
  const group = page.getByRole('group', { name: 'Download the experiment report' });
  const [pdf] = await Promise.all([page.waitForEvent('download'), group.getByRole('link', { name: 'PDF' }).click()]);
  expect(readFileSync(await pdf.path()).subarray(0, 4).toString()).toBe('%PDF');
  expect(pdf.suggestedFilename()).toMatch(/^districompute-experiment-.*\.pdf$/);

  await page.goto('/experiments');
  const row = page.getByRole('row', { name: /E2E journey/ });
  await expect(row.getByText('completed')).toBeVisible();
});
