import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
// Standalone local helper; the server still verifies the RFC 6238 value normally.
// @ts-expect-error JavaScript demo helper has no declaration file.
import { totpCode } from '../../../scripts/totp-code.mjs';

const CLEAN_WALLET = '0xc1ean000000000000000000000000000000c1ean';
const OFAC_SNAPSHOT_WALLET = '0x098B716B8Aaf21512996dC57EB0615e2383E2f96';
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const DEMO_PASSWORD = 'demo-password-2026';

async function registerLocalUser(prefix: string): Promise<string> {
  const username = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
  const profile = readFileSync(resolve(REPO_ROOT, '.demo/profile'), 'utf8').trim();
  const payload = JSON.stringify({ username, password: DEMO_PASSWORD });
  await new Promise<void>((resolveRequest, rejectRequest) => {
    const secure = profile === 'full';
    const request = (secure ? httpsRequest : httpRequest)({
      hostname: 'localhost',
      port: 8081,
      path: '/api/auth/register',
      method: 'POST',
      headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) },
      ...(secure ? {
        ca: readFileSync(resolve(REPO_ROOT, 'infra/tls/generated/ca.crt')),
        cert: readFileSync(resolve(REPO_ROOT, 'infra/tls/generated/bff-auth-client.crt')),
        key: readFileSync(resolve(REPO_ROOT, 'infra/tls/generated/bff-auth-client.key')),
        servername: 'localhost',
        rejectUnauthorized: true,
      } : {}),
    }, (response) => {
      let body = '';
      response.setEncoding('utf8');
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => {
        if (response.statusCode === 201) resolveRequest();
        else rejectRequest(new Error(`Unable to create local test user (${response.statusCode}): ${body}`));
      });
    });
    request.on('error', rejectRequest);
    request.end(payload);
  });
  return username;
}

async function passwordSignIn(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
}

test.describe.serial('real local stack', () => {
  test('password Session runs a real deterministic investigation', async ({ page, context }) => {
    await passwordSignIn(page, 'analyst', 'analyst12345');
    await expect(page.getByText('Run an investigation')).toBeVisible();

    const session = (await context.cookies()).find((cookie) => cookie.name === 'argus_session');
    expect(session?.httpOnly).toBe(true);
    expect(session?.sameSite).toBe('Strict');

    await page.getByPlaceholder(/enter a wallet address/i).fill(CLEAN_WALLET);
    await page.getByRole('button', { name: 'Investigate', exact: true }).click();
    await expect(page.locator('.verdict-badge')).toHaveText(/CLEAR/);
    await expect(page.getByText('sanctions_screen', { exact: true })).toBeVisible();
    await expect(page.getByText('risk_rules', { exact: true })).toBeVisible();
    await expect(page.getByText('trace_transactions', { exact: true })).toHaveCount(0);
  });

  test('official OFAC snapshot evidence forces a deterministic block', async ({ page }) => {
    await passwordSignIn(page, 'analyst', 'analyst12345');
    await expect(page.getByText('Run an investigation')).toBeVisible();
    await page.getByText(/official ofac snapshot/i).click();
    await expect(page.getByPlaceholder(/enter a wallet address/i)).toHaveValue(OFAC_SNAPSHOT_WALLET);

    const completedResponse = page.waitForResponse(async (response) => {
      if (response.request().method() !== 'GET'
          || !/\/bff\/api\/investigations\/[^/]+$/.test(response.url())
          || !response.ok()) return false;
      const body = await response.json() as { status?: string };
      return body.status === 'COMPLETED';
    });
    await page.getByRole('button', { name: 'Investigate', exact: true }).click();
    const investigation = await (await completedResponse).json() as {
      decision: string;
      steps: Array<{ toolName?: string; observation?: Record<string, unknown> }>;
    };

    await expect(page.locator('.verdict-badge')).toHaveText('BLOCK');
    expect(investigation.decision).toBe('BLOCK');
    const observation = investigation.steps.find((step) =>
      step.toolName === 'sanctions_screen'
      && step.observation
      && Array.isArray(step.observation.providers))?.observation;
    expect(observation?.directHit).toBe(true);
    expect(observation?.evidenceComplete).toBe(true);
    expect(observation?.hits).toEqual(expect.arrayContaining([
      expect.objectContaining({ listSource: 'OFAC-SDN', entity: 'Lazarus Group' }),
    ]));
    expect(observation?.providers).toEqual(expect.arrayContaining([
      expect.objectContaining({
        providerId: 'ofac',
        sanctioned: true,
        datasetVersion: expect.stringMatching(/^snapshot-2026-08-07-/),
      }),
    ]));
  });

  test('mock identity source completes a real OIDC code + PKCE login', async ({ page, context }) => {
    await page.goto('/');
    await page.getByRole('link', { name: /continue with oidc/i }).click();
    await expect(page).toHaveURL(/^http:\/\/localhost:9091\/authorize/);
    await expect(page.getByText(/local mock idp/i)).toBeVisible();
    await expect(page.getByText(/pkce, state, nonce, jwks signature/i)).toBeVisible();
    await page.getByRole('button', { name: /continue as gray demo/i }).click();

    await expect(page).toHaveURL(/localhost:5173\/\?auth=oidc_success/);
    await expect(page.getByText('Run an investigation')).toBeVisible();
    await expect(page.locator('.session-user strong')).toHaveText(/^oidc-/);
    expect((await context.cookies()).find((cookie) => cookie.name === 'argus_session')?.httpOnly).toBe(true);
  });

  test('enrolls TOTP, verifies MFA, and consumes an offline recovery code', async ({ page }) => {
    const username = await registerLocalUser('mfa-e2e');
    await passwordSignIn(page, username, DEMO_PASSWORD);
    await expect(page.getByText('Run an investigation')).toBeVisible();
    await page.getByRole('button', { name: /manage mfa & recovery/i }).click();
    await page.getByRole('button', { name: /set up authenticator/i }).click();
    const secret = (await page.getByTestId('totp-secret').textContent())?.trim();
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);

    // Avoid crossing two time windows between code generation and server verification.
    if (30 - (Math.floor(Date.now() / 1000) % 30) < 3) await page.waitForTimeout(3_100);
    await page.getByLabel('6-digit authenticator code').fill(totpCode(secret!, undefined, -1));
    await page.getByRole('button', { name: /enable mfa/i }).click();
    await expect(page.getByText(/save these recovery codes now/i)).toBeVisible();
    const recoveryCodes = await page.locator('.recovery-codes li code').allTextContents();
    expect(recoveryCodes).toHaveLength(10);
    const recoveryCode = recoveryCodes[0]!;

    await page.getByRole('button', { name: 'Sign out' }).click();
    await passwordSignIn(page, username, DEMO_PASSWORD);
    await expect(page.getByRole('heading', { name: /verify your identity/i })).toBeVisible();
    await page.getByLabel('6-digit authenticator code').fill(totpCode(secret!));
    await page.getByRole('button', { name: 'Verify', exact: true }).click();
    await expect(page.getByText('Run an investigation')).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.getByRole('button', { name: /recover account/i }).click();
    await page.getByLabel('Username').fill(username);
    await page.getByLabel('Recovery code').fill(recoveryCode);
    await page.getByLabel('New password').fill(DEMO_PASSWORD);
    await page.getByRole('button', { name: /reset password/i }).click();
    await expect(page.getByRole('alert')).toContainText(/password reset/i);
  });

  test('registers and reuses a real WebAuthn credential with a virtual authenticator', async ({ page, context }) => {
    const username = await registerLocalUser('passkey-e2e');
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

    await passwordSignIn(page, username, DEMO_PASSWORD);
    await expect(page.getByText('Run an investigation')).toBeVisible();
    await page.getByRole('button', { name: 'Manage passkeys' }).click();
    await page.getByLabel('Passkey label').fill('Real-stack virtual authenticator');
    await page.getByRole('button', { name: 'Add passkey' }).click();
    await expect(page.getByText('Real-stack virtual authenticator', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.getByRole('button', { name: 'Sign in with a passkey' }).click();
    await expect(page.getByText('Run an investigation')).toBeVisible();
    expect((await context.cookies()).find((cookie) => cookie.name === 'argus_session')?.httpOnly).toBe(true);
    await cdp.send('WebAuthn.disable');
  });
});
