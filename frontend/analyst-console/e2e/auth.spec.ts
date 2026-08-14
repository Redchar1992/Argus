import { expect, test, type Page } from '@playwright/test';

async function signIn(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Username').fill('analyst');
  await page.getByLabel('Password').fill('analyst12345');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByText('Run an investigation')).toBeVisible();
}

test('an anonymous browser sees only the login page', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in to Argus' })).toBeVisible();
  await expect(page.getByText('Run an investigation')).toHaveCount(0);
});

test('successful login opens the protected console with an HttpOnly session', async ({ page, context }) => {
  await signIn(page);
  await expect(page.getByText('analyst', { exact: true })).toBeVisible();

  const cookies = await context.cookies();
  const session = cookies.find((cookie) => cookie.name === 'argus_session');
  expect(session).toBeDefined();
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe('Strict');

  const browserStorage = await page.evaluate(() => ({
    local: { ...localStorage },
    session: { ...sessionStorage },
  }));
  expect(JSON.stringify(browserStorage).toLowerCase()).not.toContain('jwt');
  expect(JSON.stringify(browserStorage).toLowerCase()).not.toContain('bearer');
});

test('logout destroys the session and returns to the login page', async ({ page, context }) => {
  await signIn(page);
  await page.getByRole('button', { name: 'Sign out' }).click();

  await expect(page.getByRole('heading', { name: 'Sign in to Argus' })).toBeVisible();
  await expect(page.getByText('Run an investigation')).toHaveCount(0);
  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === 'argus_session')).toBeUndefined();
});
