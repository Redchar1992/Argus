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

test('routes an elevated fixture through the local human review gate', async ({ page }) => {
  await page.goto('/Argus/');

  await page.getByText(/0xc0ffee/i).click();
  await page.getByRole('button', { name: 'Investigate' }).click();

  await expect(page.getByText('PENDING_REVIEW', { exact: true })).toBeVisible();
  await page.getByPlaceholder('Evidence or rationale for the intervention')
    .fill('Reviewed mixer exposure and confirmed the case can proceed.');
  await page.getByRole('button', { name: 'Mark CLEAR' }).click();

  await expect(page.getByText('RESOLVED', { exact: true })).toBeVisible();
  await expect(page.locator('.review-resolved')).toContainText('Human decision CLEAR recorded');
});
