import { afterEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import type { AppConfig } from '../src/config.js';
import { UpstreamError } from '../src/errors.js';
import type { OidcRelyingParty } from '../src/oidc.js';
import { SessionStore } from '../src/session-store.js';
import { MockUpstreamClient, type UpstreamClient } from '../src/upstream.js';

const ORIGIN = 'http://localhost:5173';
const config: AppConfig = {
  host: '127.0.0.1',
  port: 3001,
  authBaseUrl: 'http://127.0.0.1:8081',
  investigationBaseUrl: 'http://127.0.0.1:8082',
  allowedOrigins: new Set([ORIGIN]),
  requestTimeoutMs: 500,
  sessionTtlSeconds: 3_600,
  loginRateLimitMax: 10,
  loginRateLimitWindowMs: 60_000,
  cookieSecure: false,
  mockUpstream: true,
  sessionStore: 'memory',
  redisConnectTimeoutMs: 1_000,
  oidcEnabled: false,
  oidcScopes: 'openid profile email',
  oidcSuccessRedirect: '/?auth=oidc_success',
  oidcErrorRedirect: '/?auth=oidc_error',
  oidcTransactionTtlSeconds: 300,
  logger: false,
};

const apps: FastifyInstance[] = [];

afterEach(async () => {
  await Promise.all(apps.splice(0).map((app) => app.close()));
});

function cookieValue(setCookies: string | string[] | undefined, name: string): string {
  const cookies = Array.isArray(setCookies) ? setCookies : setCookies ? [setCookies] : [];
  const found = cookies.find((value) => value.startsWith(`${name}=`));
  if (!found) throw new Error(`Missing ${name} cookie in ${cookies.join('; ')}`);
  return found.split(';', 1)[0]!.split('=', 2)[1]!;
}

async function appWithCsrf(
  dependencies: Parameters<typeof buildApp>[1] = {},
  configOverride: Partial<AppConfig> = {},
): Promise<{ app: FastifyInstance; csrf: string }> {
  const app = await buildApp({ ...config, ...configOverride }, dependencies);
  apps.push(app);
  const response = await app.inject({ method: 'GET', url: '/bff/auth/session' });
  expect(response.statusCode).toBe(401);
  return { app, csrf: cookieValue(response.headers['set-cookie'], 'argus_csrf') };
}

function mutationHeaders(csrf: string, cookie = `argus_csrf=${csrf}`): Record<string, string> {
  return {
    origin: ORIGIN,
    'sec-fetch-site': 'same-origin',
    'x-csrf-token': csrf,
    cookie,
  };
}

describe('identity BFF', () => {
  it('returns a normalized anonymous response and bootstraps a CSRF cookie', async () => {
    const { app } = await appWithCsrf();
    const response = await app.inject({ method: 'GET', url: '/bff/auth/session' });
    expect(response.json()).toMatchObject({ error: { code: 'UNAUTHENTICATED' } });
    expect(response.headers['cache-control']).toBe('no-store');
    expect(response.headers['x-content-type-options']).toBe('nosniff');
  });

  it('keeps the JWT server-side while exposing only an HttpOnly session cookie', async () => {
    const { app, csrf } = await appWithCsrf();
    const login = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'analyst12345' },
    });

    expect(login.statusCode).toBe(200);
    expect(login.json()).toMatchObject({
      state: 'authenticated',
      user: { username: 'analyst', role: 'ANALYST' },
    });
    expect(JSON.stringify(login.json())).not.toContain('token');
    const setCookies = login.headers['set-cookie'];
    const serialized = Array.isArray(setCookies) ? setCookies.join('\n') : setCookies ?? '';
    expect(serialized).toMatch(/argus_session=.*HttpOnly/i);
    expect(serialized).toMatch(/SameSite=Strict/i);

    const sessionId = cookieValue(setCookies, 'argus_session');
    const session = await app.inject({
      method: 'GET',
      url: '/bff/auth/session',
      headers: { cookie: `argus_session=${sessionId}; argus_csrf=${csrf}` },
    });
    expect(session.statusCode).toBe(200);
    expect(session.json()).toMatchObject({ user: { username: 'analyst' } });
  });

  it('rejects invalid credentials with a stable error code', async () => {
    const { app, csrf } = await appWithCsrf();
    const response = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'wrong-password' },
    });
    expect(response.statusCode).toBe(401);
    expect(response.json()).toMatchObject({
      error: { code: 'INVALID_CREDENTIALS', message: 'Invalid username or password' },
    });
  });

  it('requires both an allowed origin and a matching double-submit CSRF token', async () => {
    const { app, csrf } = await appWithCsrf();
    const missingToken = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { origin: ORIGIN, cookie: `argus_csrf=${csrf}`, 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    expect(missingToken.statusCode).toBe(403);
    expect(missingToken.json()).toMatchObject({ error: { code: 'CSRF_INVALID' } });

    const foreignOrigin = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: {
        ...mutationHeaders(csrf),
        origin: 'https://attacker.example',
        'content-type': 'application/json',
      },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    expect(foreignOrigin.statusCode).toBe(403);
    expect(foreignOrigin.json()).toMatchObject({ error: { code: 'FORBIDDEN_ORIGIN' } });
  });

  it('guards investigation routes, proxies with the server-side token, and logs out', async () => {
    const { app, csrf } = await appWithCsrf();
    const anonymous = await app.inject({ method: 'GET', url: '/bff/api/investigations/unknown' });
    expect(anonymous.statusCode).toBe(401);

    const login = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    const sessionId = cookieValue(login.headers['set-cookie'], 'argus_session');
    const rotatedCsrf = cookieValue(login.headers['set-cookie'], 'argus_csrf');
    const cookie = `argus_session=${sessionId}; argus_csrf=${rotatedCsrf}`;

    const submitted = await app.inject({
      method: 'POST',
      url: '/bff/api/investigations',
      headers: { ...mutationHeaders(rotatedCsrf, cookie), 'content-type': 'application/json' },
      payload: { address: '0xc1ean000000000000000000000000000000c1ean' },
    });
    expect(submitted.statusCode).toBe(200);
    const id = submitted.json<{ investigationId: string }>().investigationId;

    const investigation = await app.inject({
      method: 'GET',
      url: `/bff/api/investigations/${id}`,
      headers: { cookie },
    });
    expect(investigation.statusCode).toBe(200);
    expect(investigation.json()).toMatchObject({ id, status: 'COMPLETED', decision: 'CLEAR' });

    const logout = await app.inject({
      method: 'POST',
      url: '/bff/auth/logout',
      headers: mutationHeaders(rotatedCsrf, cookie),
    });
    expect(logout.statusCode).toBe(204);

    const afterLogout = await app.inject({
      method: 'GET',
      url: '/bff/auth/session',
      headers: { cookie },
    });
    expect(afterLogout.statusCode).toBe(401);
  });

  it('expires a server-side session even when the browser still has its cookie', async () => {
    let now = Date.now();
    const sessions = new SessionStore(3_600, () => now);
    const { app, csrf } = await appWithCsrf({ sessions });
    const login = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    const sessionId = cookieValue(login.headers['set-cookie'], 'argus_session');
    now += 3_601_000;

    const expired = await app.inject({
      method: 'GET',
      url: '/bff/auth/session',
      headers: { cookie: `argus_session=${sessionId}; argus_csrf=${csrf}` },
    });
    expect(expired.statusCode).toBe(401);
    expect(expired.json()).toMatchObject({ error: { code: 'SESSION_EXPIRED' } });
    expect(sessions.size).toBe(0);
  });

  it('invalidates the BFF session when a protected upstream returns 401', async () => {
    const mock = new MockUpstreamClient();
    const expiredUpstream: UpstreamClient = {
      login: (...args) => mock.login(...args),
      oidcLogin: (...args) => mock.oidcLogin(...args),
      submitInvestigation: (...args) => mock.submitInvestigation(...args),
      getInvestigation: async () => {
        throw new UpstreamError(401, 'rejected', 'JWT expired');
      },
    };
    const { app, csrf } = await appWithCsrf({ upstream: expiredUpstream });
    const login = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    const sessionId = cookieValue(login.headers['set-cookie'], 'argus_session');

    const expired = await app.inject({
      method: 'GET',
      url: '/bff/api/investigations/any',
      headers: { cookie: `argus_session=${sessionId}; argus_csrf=${csrf}` },
    });
    expect(expired.statusCode).toBe(401);
    expect(expired.json()).toMatchObject({ error: { code: 'SESSION_EXPIRED' } });

    const noLongerValid = await app.inject({
      method: 'GET',
      url: '/bff/auth/session',
      headers: { cookie: `argus_session=${sessionId}; argus_csrf=${csrf}` },
    });
    expect(noLongerValid.statusCode).toBe(401);
  });

  it('normalizes an upstream timeout without leaking implementation detail', async () => {
    const timeoutUpstream: UpstreamClient = {
      login: async () => {
        throw new UpstreamError(504, 'timeout', 'internal socket detail');
      },
      oidcLogin: async () => {
        throw new UpstreamError(504, 'timeout', 'internal socket detail');
      },
      submitInvestigation: async () => undefined,
      getInvestigation: async () => undefined,
    };
    const { app, csrf } = await appWithCsrf({ upstream: timeoutUpstream });
    const response = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    expect(response.statusCode).toBe(504);
    expect(response.json()).toMatchObject({
      error: { code: 'UPSTREAM_TIMEOUT', message: 'A required service timed out. Try again.' },
    });
    expect(response.body).not.toContain('socket');
  });

  it('rate limits repeated login attempts per client', async () => {
    const { app, csrf } = await appWithCsrf({}, { loginRateLimitMax: 1 });
    const first = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'wrong-password' },
    });
    expect(first.statusCode).toBe(401);

    const second = await app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: { ...mutationHeaders(csrf), 'content-type': 'application/json' },
      payload: { username: 'analyst', password: 'wrong-password' },
    });
    expect(second.statusCode).toBe(429);
    expect(second.json()).toMatchObject({ error: { code: 'RATE_LIMITED' } });
  });

  it('completes OIDC with one-time state while keeping provider and Argus tokens server-side', async () => {
    const state = 'state-value-that-is-at-least-thirty-two-characters';
    const nonce = 'nonce-value-that-is-at-least-thirty-two-characters';
    const codeVerifier = 'pkce-verifier-that-is-at-least-thirty-two-characters';
    const oidc: OidcRelyingParty = {
      begin: async () => ({
        redirectUrl: `https://idp.example/authorize?state=${state}`,
        state,
        nonce,
        codeVerifier,
        expiresAt: Date.now() + 60_000,
      }),
      complete: async (callbackUrl, transaction) => {
        expect(callbackUrl.origin).toBe('http://localhost:3001');
        expect(callbackUrl.searchParams.get('code')).toBe('provider-code');
        expect(transaction).toMatchObject({ state, nonce, codeVerifier });
        return { idToken: 'mock-oidc-id-token' };
      },
    };
    const { app } = await appWithCsrf(
      { oidc },
      {
        oidcEnabled: true,
        oidcIssuer: 'https://idp.example/',
        oidcClientId: 'argus-client',
        oidcRedirectUri: 'http://localhost:3001/bff/auth/oidc/callback',
      },
    );

    const start = await app.inject({ method: 'GET', url: '/bff/auth/oidc/start' });
    expect(start.statusCode).toBe(302);
    expect(start.headers.location).toContain('https://idp.example/authorize');
    const serialized = Array.isArray(start.headers['set-cookie'])
      ? start.headers['set-cookie'].join('\n')
      : start.headers['set-cookie'] ?? '';
    expect(serialized).toMatch(/argus_oidc_tx=.*HttpOnly/i);
    expect(serialized).toMatch(/SameSite=Lax/i);
    expect(serialized).toMatch(/Path=\/bff\/auth\/oidc\/callback/i);
    const transactionId = cookieValue(start.headers['set-cookie'], 'argus_oidc_tx');

    const callback = await app.inject({
      method: 'GET',
      url: `/bff/auth/oidc/callback?code=provider-code&state=${encodeURIComponent(state)}`,
      headers: { cookie: `argus_oidc_tx=${transactionId}` },
    });
    expect(callback.statusCode).toBe(302);
    expect(callback.headers.location).toBe('/?auth=oidc_success');
    expect(callback.body).not.toContain('mock-oidc-id-token');
    const callbackCookies = Array.isArray(callback.headers['set-cookie'])
      ? callback.headers['set-cookie'].join('\n')
      : callback.headers['set-cookie'] ?? '';
    expect(callbackCookies).toMatch(/argus_session=.*HttpOnly/i);
    expect(callbackCookies).toMatch(/argus_session=.*SameSite=Strict/i);

    const replay = await app.inject({
      method: 'GET',
      url: `/bff/auth/oidc/callback?code=provider-code&state=${encodeURIComponent(state)}`,
      headers: { cookie: `argus_oidc_tx=${transactionId}` },
    });
    expect(replay.statusCode).toBe(302);
    expect(replay.headers.location).toBe('/?auth=oidc_error');
  });
});
