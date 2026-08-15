import { Redis } from 'ioredis';
import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import type { AppConfig } from '../src/config.js';
import { createRuntimeDependencies } from '../src/runtime.js';

const REDIS_URL = process.env.BFF_TEST_REDIS_URL ?? '';
const ORIGIN = 'http://localhost:5173';
const config: AppConfig = {
  host: '127.0.0.1',
  port: 3001,
  authBaseUrl: 'http://127.0.0.1:8081',
  investigationBaseUrl: 'http://127.0.0.1:8082',
  authMtlsEnabled: false,
  allowedOrigins: new Set([ORIGIN]),
  requestTimeoutMs: 500,
  sessionTtlSeconds: 3_600,
  loginRateLimitMax: 1,
  loginRateLimitWindowMs: 60_000,
  cookieSecure: false,
  mockUpstream: true,
  sessionStore: 'redis',
  redisUrl: REDIS_URL,
  ...(process.env.BFF_REDIS_USERNAME ? { redisUsername: process.env.BFF_REDIS_USERNAME } : {}),
  ...(process.env.BFF_REDIS_PASSWORD ? { redisPassword: process.env.BFF_REDIS_PASSWORD } : {}),
  ...(process.env.BFF_REDIS_TLS_CA_FILE ? { redisTlsCaFile: process.env.BFF_REDIS_TLS_CA_FILE } : {}),
  ...(process.env.BFF_REDIS_TLS_CERT_FILE ? { redisTlsCertFile: process.env.BFF_REDIS_TLS_CERT_FILE } : {}),
  ...(process.env.BFF_REDIS_TLS_KEY_FILE ? { redisTlsKeyFile: process.env.BFF_REDIS_TLS_KEY_FILE } : {}),
  ...(process.env.BFF_REDIS_TLS_SERVER_NAME ? { redisTlsServerName: process.env.BFF_REDIS_TLS_SERVER_NAME } : {}),
  encryptionPrimaryKeyId: 'test-v1',
  encryptionKeys: new Map([['test-v1', Buffer.alloc(32, 5)]]),
  redisConnectTimeoutMs: 1_000,
  oidcEnabled: false,
  oidcScopes: 'openid profile email',
  oidcSuccessRedirect: '/?auth=oidc_success',
  oidcErrorRedirect: '/?auth=oidc_error',
  oidcTransactionTtlSeconds: 300,
  mfaChallengeTtlSeconds: 300,
  mfaRequiredRedirect: '/?auth=mfa_required',
  passkeyEnabled: true,
  webauthnRpId: 'localhost',
  webauthnRpName: 'Argus',
  webauthnOrigin: ORIGIN,
  webauthnCeremonyTtlSeconds: 300,
  internalBffSecret: 'argus-dev-internal-bff-secret-change-me',
  region: 'redis-test',
  metricsEnabled: true,
  logger: false,
};

function cookieValue(setCookies: string | string[] | undefined, name: string): string {
  const cookies = Array.isArray(setCookies) ? setCookies : setCookies ? [setCookies] : [];
  const found = cookies.find((value) => value.startsWith(`${name}=`));
  if (!found) throw new Error(`Missing ${name} cookie`);
  return found.split(';', 1)[0]!.split('=', 2)[1]!;
}

const redisDescribe = REDIS_URL ? describe : describe.skip;

redisDescribe('Redis-backed BFF integration', () => {
  let admin: Redis;
  const apps: FastifyInstance[] = [];

  beforeAll(async () => {
    const secure = new URL(REDIS_URL).protocol === 'rediss:';
    admin = new Redis(REDIS_URL, {
      maxRetriesPerRequest: 1,
      ...(process.env.BFF_REDIS_USERNAME ? { username: process.env.BFF_REDIS_USERNAME } : {}),
      ...(process.env.BFF_REDIS_PASSWORD ? { password: process.env.BFF_REDIS_PASSWORD } : {}),
      ...(secure ? { tls: {
        rejectUnauthorized: true,
        servername: process.env.BFF_REDIS_TLS_SERVER_NAME ?? new URL(REDIS_URL).hostname,
        ...(process.env.BFF_REDIS_TLS_CA_FILE
          ? { ca: readFileSync(process.env.BFF_REDIS_TLS_CA_FILE) } : {}),
        ...(process.env.BFF_REDIS_TLS_CERT_FILE
          ? { cert: readFileSync(process.env.BFF_REDIS_TLS_CERT_FILE) } : {}),
        ...(process.env.BFF_REDIS_TLS_KEY_FILE
          ? { key: readFileSync(process.env.BFF_REDIS_TLS_KEY_FILE) } : {}),
      } } : {}),
    });
    await admin.flushdb();
  });

  afterAll(async () => {
    await Promise.all(apps.map((app) => app.close()));
    await admin.flushdb();
    await admin.quit();
  });

  it('shares encrypted sessions, logout, and login limits across BFF instances', async () => {
    const appA = await buildApp(config, await createRuntimeDependencies(config));
    const appB = await buildApp(config, await createRuntimeDependencies(config));
    apps.push(appA, appB);

    const metrics = await appA.inject({ method: 'GET', url: '/metrics' });
    expect(metrics.statusCode).toBe(200);
    expect(metrics.body).toContain('argus_bff_dependency_up');
    expect(metrics.body).toContain('dependency="redis"');
    expect(metrics.body).toContain('region="redis-test"');
    if (process.env.BFF_REDIS_TLS_CERT_FILE) {
      expect(metrics.body).toContain('argus_bff_tls_certificate_expiry_timestamp_seconds');
      expect(metrics.body).toContain('certificate="redis_client"');
    }

    const bootstrap = await appA.inject({ method: 'GET', url: '/bff/auth/session' });
    const csrf = cookieValue(bootstrap.headers['set-cookie'], 'argus_csrf');
    const headers = {
      origin: ORIGIN,
      'sec-fetch-site': 'same-origin',
      'x-csrf-token': csrf,
      cookie: `argus_csrf=${csrf}`,
      'content-type': 'application/json',
    };
    const login = await appA.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers,
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    expect(login.statusCode).toBe(200);
    const sessionId = cookieValue(login.headers['set-cookie'], 'argus_session');
    const rotatedCsrf = cookieValue(login.headers['set-cookie'], 'argus_csrf');
    const cookie = `argus_session=${sessionId}; argus_csrf=${rotatedCsrf}`;

    const restoredOnB = await appB.inject({
      method: 'GET',
      url: '/bff/auth/session',
      headers: { cookie },
    });
    expect(restoredOnB.statusCode).toBe(200);
    expect(restoredOnB.json()).toMatchObject({ user: { username: 'analyst', role: 'ANALYST' } });

    const stored = await admin.get(`argus:bff:session:${sessionId}`);
    expect(stored).toBeTruthy();
    expect(stored).not.toContain('mock-only-');
    expect(await admin.ttl(`argus:bff:session:${sessionId}`)).toBeGreaterThan(0);

    const limitedOnB = await appB.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: {
        ...headers,
        'x-csrf-token': rotatedCsrf,
        cookie,
      },
      payload: { username: 'analyst', password: 'wrong-password' },
    });
    expect(limitedOnB.statusCode).toBe(429);
    expect(limitedOnB.json()).toMatchObject({ error: { code: 'RATE_LIMITED' } });

    const logoutOnB = await appB.inject({
      method: 'POST',
      url: '/bff/auth/logout',
      headers: {
        origin: ORIGIN,
        'sec-fetch-site': 'same-origin',
        'x-csrf-token': rotatedCsrf,
        cookie,
      },
    });
    expect(logoutOnB.statusCode).toBe(204);

    const goneOnA = await appA.inject({
      method: 'GET',
      url: '/bff/auth/session',
      headers: { cookie },
    });
    expect(goneOnA.statusCode).toBe(401);
  });

  it('revokes every account session across replicas after recovery', async () => {
    const runtimeA = await createRuntimeDependencies(config);
    const runtimeB = await createRuntimeDependencies(config);
    try {
      const first = await runtimeA.sessions!.create(
        'first-recovery-token', { username: 'recover-me', role: 'ANALYST' }, 3_600,
      );
      const second = await runtimeB.sessions!.create(
        'second-recovery-token', { username: 'recover-me', role: 'ANALYST' }, 3_600,
      );
      const other = await runtimeA.sessions!.create(
        'unrelated-token', { username: 'other-user', role: 'ANALYST' }, 3_600,
      );

      await expect(runtimeB.sessions!.get(first.id)).resolves.toEqual(first);
      await runtimeA.sessions!.deleteForUser('recover-me');
      await expect(runtimeA.sessions!.get(first.id)).resolves.toBeUndefined();
      await expect(runtimeB.sessions!.get(second.id)).resolves.toBeUndefined();
      await expect(runtimeB.sessions!.get(other.id)).resolves.toEqual(other);
    } finally {
      await runtimeA.close?.();
      await runtimeB.close?.();
    }
  });

  it('shares encrypted one-time WebAuthn ceremonies across replicas', async () => {
    const runtimeA = await createRuntimeDependencies(config);
    const runtimeB = await createRuntimeDependencies(config);
    try {
      const ceremony = {
        kind: 'authentication' as const,
        challenge: 'z'.repeat(43),
        expiresAt: Date.now() + 60_000,
      };
      const id = await runtimeA.webauthnCeremonies!.create(ceremony);
      const stored = await admin.get(`argus:bff:webauthn:${id}`);
      expect(stored).toBeTruthy();
      expect(stored).not.toContain(ceremony.challenge);
      await expect(runtimeB.webauthnCeremonies!.consume(id)).resolves.toEqual(ceremony);
      await expect(runtimeA.webauthnCeremonies!.consume(id)).resolves.toBeUndefined();
    } finally {
      await runtimeA.close?.();
      await runtimeB.close?.();
    }
  });

  it('rotates active Session ciphertext across replicas without signing the user out', async () => {
    const oldKey = Buffer.alloc(32, 21);
    const newKey = Buffer.alloc(32, 22);
    const oldRuntime = await createRuntimeDependencies({
      ...config,
      encryptionPrimaryKeyId: 'old-v1',
      encryptionKeys: new Map([['old-v1', oldKey]]),
    });
    let rotatingRuntime: Awaited<ReturnType<typeof createRuntimeDependencies>> | undefined;
    let newOnlyRuntime: Awaited<ReturnType<typeof createRuntimeDependencies>> | undefined;
    try {
      const session = await oldRuntime.sessions!.create(
        'rotation-integration-token', { username: 'rotate-me', role: 'ANALYST' }, 3_600,
      );
      rotatingRuntime = await createRuntimeDependencies({
        ...config,
        encryptionPrimaryKeyId: 'new-v2',
        encryptionKeys: new Map([['old-v1', oldKey], ['new-v2', newKey]]),
      });
      await expect(rotatingRuntime.sessions!.get(session.id)).resolves.toEqual(session);
      const raw = JSON.parse((await admin.get(`argus:bff:session:${session.id}`))!) as { token: { kid: string } };
      expect(raw.token.kid).toBe('new-v2');

      newOnlyRuntime = await createRuntimeDependencies({
        ...config,
        encryptionPrimaryKeyId: 'new-v2',
        encryptionKeys: new Map([['new-v2', newKey]]),
      });
      await expect(newOnlyRuntime.sessions!.get(session.id)).resolves.toEqual(session);
    } finally {
      await oldRuntime.close?.();
      await rotatingRuntime?.close?.();
      await newOnlyRuntime?.close?.();
    }
  });
});
