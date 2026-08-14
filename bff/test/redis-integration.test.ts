import { Redis } from 'ioredis';
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
  allowedOrigins: new Set([ORIGIN]),
  requestTimeoutMs: 500,
  sessionTtlSeconds: 3_600,
  loginRateLimitMax: 1,
  loginRateLimitWindowMs: 60_000,
  cookieSecure: false,
  mockUpstream: true,
  sessionStore: 'redis',
  redisUrl: REDIS_URL,
  sessionEncryptionKey: Buffer.alloc(32, 5),
  redisConnectTimeoutMs: 1_000,
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
    admin = new Redis(REDIS_URL, { maxRetriesPerRequest: 1 });
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
});
