import { execFile as execFileCallback } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import type { FastifyInstance } from 'fastify';
import { Redis } from 'ioredis';
import { buildApp } from '../src/app.js';
import { loadConfig, type AppConfig } from '../src/config.js';
import { createRuntimeDependencies } from '../src/runtime.js';

const execFile = promisify(execFileCallback);
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const ORIGIN = 'http://localhost:5173';
const PRIMARY_URL = process.env.ARGUS_DRILL_PRIMARY_URL ?? 'rediss://localhost:6391';
const REPLICA_URL = process.env.ARGUS_DRILL_REPLICA_URL ?? 'rediss://localhost:6392';
const METRICS_TOKEN = 'argus-drill-metrics-token-not-a-production-secret';

interface Region {
  app: FastifyInstance;
  config: AppConfig;
  closed: boolean;
}

interface DrillResult {
  schemaVersion: 1;
  startedAt: string;
  completedAt?: string;
  passed: boolean;
  topology: Record<string, unknown>;
  objectives: Record<string, number>;
  observations: Record<string, number>;
  checks: Array<{ name: string; passed: boolean }>;
  limitations: string[];
  error?: string;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required for the TLS drill`);
  return value;
}

function regionConfig(region: string, redisUrl: string): AppConfig {
  return loadConfig({
    ...process.env,
    NODE_ENV: 'development',
    ARGUS_REGION: region,
    BFF_SESSION_STORE: 'redis',
    BFF_REDIS_URL: redisUrl,
    BFF_MOCK_UPSTREAM: 'true',
    BFF_COOKIE_SECURE: 'false',
    BFF_PASSKEY_ENABLED: 'false',
    BFF_ALLOWED_ORIGINS: ORIGIN,
    BFF_LOGIN_RATE_LIMIT_MAX: '100',
    BFF_METRICS_ENABLED: 'true',
    BFF_METRICS_TOKEN: METRICS_TOKEN,
  });
}

async function openRegion(region: string, redisUrl: string): Promise<Region> {
  const config = regionConfig(region, redisUrl);
  const dependencies = await createRuntimeDependencies(config);
  try {
    return { app: await buildApp(config, dependencies), config, closed: false };
  } catch (error) {
    await dependencies.close?.();
    throw error;
  }
}

async function closeRegion(region: Region | undefined): Promise<void> {
  if (!region || region.closed) return;
  region.closed = true;
  await region.app.close();
}

function cookieValue(setCookies: string | string[] | undefined, name: string): string {
  const cookies = Array.isArray(setCookies) ? setCookies : setCookies ? [setCookies] : [];
  const found = cookies.find((value) => value.startsWith(`${name}=`));
  if (!found) throw new Error(`Drill response did not contain the ${name} cookie`);
  return found.split(';', 1)[0]!.split('=', 2)[1]!;
}

function check(result: DrillResult, name: string, condition: boolean): void {
  result.checks.push({ name, passed: condition });
  if (!condition) throw new Error(`Drill check failed: ${name}`);
}

async function waitUntil(description: string, operation: () => Promise<boolean>, timeoutMs = 15_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      if (await operation()) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
  }
  throw new Error(`${description} did not complete within ${timeoutMs}ms${lastError ? ' (last attempt failed)' : ''}`);
}

async function within<T>(description: string, operation: Promise<T>, timeoutMs = 5_000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<T>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`${description} exceeded ${timeoutMs}ms`)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function redisAdmin(url: string): Redis {
  return new Redis(url, {
    username: process.env.BFF_REDIS_USERNAME ?? 'default',
    password: required('BFF_REDIS_PASSWORD'),
    maxRetriesPerRequest: 1,
    enableOfflineQueue: false,
    lazyConnect: true,
    tls: {
      rejectUnauthorized: true,
      minVersion: 'TLSv1.2',
      servername: process.env.BFF_REDIS_TLS_SERVER_NAME ?? 'localhost',
      ca: readFileSync(required('BFF_REDIS_TLS_CA_FILE')),
      cert: readFileSync(required('BFF_REDIS_TLS_CERT_FILE')),
      key: readFileSync(required('BFF_REDIS_TLS_KEY_FILE')),
    },
  });
}

async function stopPrimary(): Promise<void> {
  await execFile(
    'docker',
    ['compose', '--profile', 'drill', 'stop', '-t', '1', 'redis-drill-primary'],
    { cwd: ROOT, env: process.env },
  );
}

async function writeResult(result: DrillResult): Promise<string> {
  const stamp = result.startedAt.replace(/[:.]/g, '-');
  const file = process.env.ARGUS_DRILL_RESULT_FILE
    ?? resolve(ROOT, 'infra/drills/results', `identity-drill-${stamp}.json`);
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 });
  return file;
}

async function run(): Promise<void> {
  const startedAt = new Date().toISOString();
  const result: DrillResult = {
    schemaVersion: 1,
    startedAt,
    passed: false,
    topology: {
      application: 'two independently instantiated BFF regions',
      state: 'TLS + password + client-certificate Redis primary/replica',
      failover: 'manual promotion and regional BFF restart',
    },
    objectives: {
      applicationRegionRtoMs: 5_000,
      redisFailoverRtoMs: 30_000,
      sessionRpoLost: 0,
    },
    observations: {},
    checks: [],
    limitations: [
      'Local Docker fault injection does not emulate WAN latency, DNS, quorum, or cloud control-plane delay.',
      'Redis promotion is deliberately manual; production requires a managed or quorum-controlled failover mechanism.',
      'The drill proves Session continuity and fail-closed authorization, not globally active-active Redis writes.',
    ],
  };

  let regionA: Region | undefined;
  let regionB: Region | undefined;
  let failoverB: Region | undefined;
  let replica: Redis | undefined;
  let thrown: unknown;
  try {
    regionA = await openRegion('region-a', PRIMARY_URL);
    regionB = await openRegion('region-b', PRIMARY_URL);
    replica = redisAdmin(REPLICA_URL);
    replica.on('error', () => {
      // Fault injection deliberately severs sockets; details are not drill evidence.
    });
    await replica.connect();
    await replica.ping();

    const bootstrap = await regionA.app.inject({ method: 'GET', url: '/bff/auth/session' });
    const csrf = cookieValue(bootstrap.headers['set-cookie'], 'argus_csrf');
    const login = await regionA.app.inject({
      method: 'POST',
      url: '/bff/auth/login',
      headers: {
        origin: ORIGIN,
        'sec-fetch-site': 'same-origin',
        'x-csrf-token': csrf,
        cookie: `argus_csrf=${csrf}`,
        'content-type': 'application/json',
      },
      payload: { username: 'analyst', password: 'analyst12345' },
    });
    check(result, 'Region A login succeeds without exposing a JWT',
      login.statusCode === 200 && !login.body.includes('mock-only-') && !login.body.includes('token'));
    const sessionId = cookieValue(login.headers['set-cookie'], 'argus_session');
    const rotatedCsrf = cookieValue(login.headers['set-cookie'], 'argus_csrf');
    const sessionCookie = `argus_session=${sessionId}; argus_csrf=${rotatedCsrf}`;

    const restoredOnB = await regionB.app.inject({
      method: 'GET', url: '/bff/auth/session', headers: { cookie: sessionCookie },
    });
    check(result, 'Region B restores the Session created in Region A',
      restoredOnB.statusCode === 200 && restoredOnB.json().user?.username === 'analyst');

    const redisSessionKey = `argus:bff:session:${sessionId}`;
    await waitUntil('replica Session synchronization', async () => Boolean(await replica!.get(redisSessionKey)));
    check(result, 'Replica contains the encrypted Session before fault injection', true);

    const metrics = await regionA.app.inject({
      method: 'GET', url: '/metrics', headers: { authorization: `Bearer ${METRICS_TOKEN}` },
    });
    check(result, 'Regional metrics are labelled and contain no account identifier',
      metrics.statusCode === 200 && metrics.body.includes('region="region-a"') && !metrics.body.includes('analyst'));

    const applicationFailoverStarted = performance.now();
    await closeRegion(regionA);
    const afterRegionLoss = await regionB.app.inject({
      method: 'GET', url: '/bff/auth/session', headers: { cookie: sessionCookie },
    });
    const applicationRegionRtoMs = Math.round(performance.now() - applicationFailoverStarted);
    result.observations.applicationRegionRtoMs = applicationRegionRtoMs;
    check(result, 'Region B continues authorization after Region A is stopped', afterRegionLoss.statusCode === 200);
    check(result, 'Application-region RTO stays within the local objective',
      applicationRegionRtoMs <= result.objectives.applicationRegionRtoMs!);

    const redisFailureStarted = performance.now();
    await stopPrimary();
    await waitUntil('Region B readiness failure', async () => {
      const readiness = await within('readiness probe', regionB!.app.inject({ method: 'GET', url: '/ready' }), 2_000);
      return readiness.statusCode === 503;
    });
    const deniedDuringStateOutage = await within(
      'fail-closed Session request',
      regionB.app.inject({ method: 'GET', url: '/bff/auth/session', headers: { cookie: sessionCookie } }),
      3_000,
    );
    check(result, 'State-store outage returns 503 and never authorizes from stale process memory',
      deniedDuringStateOutage.statusCode === 503
        && deniedDuringStateOutage.json().error?.code === 'IDENTITY_STORE_UNAVAILABLE'
        && !deniedDuringStateOutage.body.includes('analyst'));

    await replica.call('REPLICAOF', 'NO', 'ONE');
    await waitUntil('replica promotion', async () => {
      const info = await replica!.info('replication');
      return info.includes('role:master');
    });
    await closeRegion(regionB);
    failoverB = await openRegion('region-b', REPLICA_URL);
    const restoredAfterPromotion = await failoverB.app.inject({
      method: 'GET', url: '/bff/auth/session', headers: { cookie: sessionCookie },
    });
    const redisFailoverRtoMs = Math.round(performance.now() - redisFailureStarted);
    result.observations.redisFailoverRtoMs = redisFailoverRtoMs;
    result.observations.sessionRpoLost = restoredAfterPromotion.statusCode === 200 ? 0 : 1;
    check(result, 'Promoted TLS replica restores the pre-failure Session', restoredAfterPromotion.statusCode === 200);
    check(result, 'Observed Session RPO is zero', result.observations.sessionRpoLost === 0);
    check(result, 'Redis failover RTO stays within the local objective',
      redisFailoverRtoMs <= result.objectives.redisFailoverRtoMs!);

    const logout = await failoverB.app.inject({
      method: 'POST',
      url: '/bff/auth/logout',
      headers: {
        origin: ORIGIN,
        'sec-fetch-site': 'same-origin',
        'x-csrf-token': rotatedCsrf,
        cookie: sessionCookie,
      },
    });
    const afterLogout = await failoverB.app.inject({
      method: 'GET', url: '/bff/auth/session', headers: { cookie: sessionCookie },
    });
    check(result, 'Logout on the promoted region globally revokes the Session',
      logout.statusCode === 204 && afterLogout.statusCode === 401);

    result.passed = result.checks.every((item) => item.passed);
  } catch (error) {
    thrown = error;
    result.error = error instanceof Error ? error.message : 'Unknown drill failure';
  } finally {
    await Promise.allSettled([closeRegion(regionA), closeRegion(regionB), closeRegion(failoverB)]);
    if (replica && replica.status !== 'end') replica.disconnect();
    result.completedAt = new Date().toISOString();
    const file = await writeResult(result);
    console.log(`RESULT_FILE=${file}`);
    console.log(JSON.stringify({ passed: result.passed, observations: result.observations, checks: result.checks.length }));
  }
  if (thrown) throw thrown;
  if (!result.passed) throw new Error('Multi-region identity drill did not pass');
}

await run();
