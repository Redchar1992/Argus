import { expect, test } from '@playwright/test';

test('runs the self-contained Pages investigation and discloses its static boundary', async ({ page }) => {
  await page.goto('/Argus/');

  await expect(page.getByText('GitHub Pages static demo')).toBeVisible();
  await expect(page.getByText(/no live screening, identity service, backend or model call/i)).toBeVisible();

  await page.getByText(/0xc1ean/i).click();
  await page.getByRole('button', { name: 'Investigate' }).click();

  await expect(page.getByText('COMPLETED', { exact: true })).toBeVisible();
  await expect(page.getByText('CLEAR', { exact: true })).toBeVisible();
  await expect(page.getByText('pages-fixture (deterministic)', { exact: true }).first()).toBeVisible();
});
