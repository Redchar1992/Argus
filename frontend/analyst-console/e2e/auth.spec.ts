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

test('registers a discoverable passkey and uses it for passwordless sign-in', async ({ page, context }) => {
  const cdp = await context.newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      ctap2Version: 'ctap2_1',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  });

  await signIn(page);
  await page.getByRole('button', { name: 'Manage passkeys' }).click();
  await page.getByLabel('Passkey label').fill('Playwright authenticator');
  await page.getByRole('button', { name: 'Add passkey' }).click();
  await expect(page.getByText('Playwright authenticator', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
  await expect(page.getByText('Run an investigation')).toBeVisible();

  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === 'argus_session')?.httpOnly).toBe(true);
  await cdp.send('WebAuthn.disable');
});
