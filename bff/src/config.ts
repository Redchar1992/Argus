export interface AppConfig {
  host: string;
  port: number;
  authBaseUrl: string;
  investigationBaseUrl: string;
  allowedOrigins: ReadonlySet<string>;
  requestTimeoutMs: number;
  sessionTtlSeconds: number;
  loginRateLimitMax: number;
  loginRateLimitWindowMs: number;
  cookieSecure: boolean;
  mockUpstream: boolean;
  sessionStore: 'memory' | 'redis';
  redisUrl?: string;
  sessionEncryptionKey?: Buffer;
  redisConnectTimeoutMs: number;
  logger: boolean;
}

function positiveInteger(value: string | undefined, fallback: number, name: string): number {
  if (value === undefined || value === '') return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function booleanValue(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined || value === '') return fallback;
  return value.toLowerCase() === 'true';
}

function originSet(value: string | undefined): ReadonlySet<string> {
  const configured = value ?? 'http://localhost:5173,http://127.0.0.1:5173';
  const origins = configured
    .split(',')
    .map((origin) => origin.trim().replace(/\/$/, ''))
    .filter(Boolean);
  if (origins.length === 0) throw new Error('BFF_ALLOWED_ORIGINS must contain at least one origin');
  for (const origin of origins) {
    const parsed = new URL(origin);
    if (parsed.origin !== origin) {
      throw new Error(`BFF_ALLOWED_ORIGINS entry must be an origin without a path: ${origin}`);
    }
  }
  return new Set(origins);
}

function sessionStoreValue(value: string | undefined, production: boolean): 'memory' | 'redis' {
  const store = value ?? (production ? 'redis' : 'memory');
  if (store !== 'memory' && store !== 'redis') {
    throw new Error('BFF_SESSION_STORE must be memory or redis');
  }
  return store;
}

function encryptionKey(value: string | undefined): Buffer | undefined {
  if (!value) return undefined;
  const normalized = value.trim();
  const decoded = Buffer.from(normalized, 'base64');
  const canonical = decoded.toString('base64').replace(/=+$/, '');
  if (decoded.length !== 32 || canonical !== normalized.replace(/=+$/, '')) {
    throw new Error('BFF_SESSION_ENCRYPTION_KEY must be a base64-encoded 32-byte key');
  }
  return decoded;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const production = env.NODE_ENV === 'production';
  const mockUpstream = booleanValue(env.BFF_MOCK_UPSTREAM, false);
  const cookieSecure = booleanValue(env.BFF_COOKIE_SECURE, production);
  const sessionStore = sessionStoreValue(env.BFF_SESSION_STORE, production);
  const redisUrl = env.BFF_REDIS_URL?.trim() || undefined;
  const sessionEncryptionKey = encryptionKey(env.BFF_SESSION_ENCRYPTION_KEY);
  if (production && mockUpstream) {
    throw new Error('BFF_MOCK_UPSTREAM cannot be enabled in production');
  }
  if (production && !cookieSecure) {
    throw new Error('BFF_COOKIE_SECURE must be true in production');
  }
  if (production && sessionStore !== 'redis') {
    throw new Error('BFF_SESSION_STORE must be redis in production');
  }
  if (sessionStore === 'redis' && !redisUrl) {
    throw new Error('BFF_REDIS_URL is required when BFF_SESSION_STORE=redis');
  }
  if (sessionStore === 'redis' && !sessionEncryptionKey) {
    throw new Error('BFF_SESSION_ENCRYPTION_KEY is required when BFF_SESSION_STORE=redis');
  }

  return {
    host: env.BFF_HOST ?? '127.0.0.1',
    port: positiveInteger(env.BFF_PORT, 3001, 'BFF_PORT'),
    authBaseUrl: (env.ARGUS_AUTH_URL ?? 'http://127.0.0.1:8081').replace(/\/$/, ''),
    investigationBaseUrl: (env.ARGUS_INVESTIGATION_URL ?? 'http://127.0.0.1:8082').replace(/\/$/, ''),
    allowedOrigins: originSet(env.BFF_ALLOWED_ORIGINS),
    requestTimeoutMs: positiveInteger(env.BFF_REQUEST_TIMEOUT_MS, 5_000, 'BFF_REQUEST_TIMEOUT_MS'),
    sessionTtlSeconds: positiveInteger(env.BFF_SESSION_TTL_SECONDS, 3_600, 'BFF_SESSION_TTL_SECONDS'),
    loginRateLimitMax: positiveInteger(env.BFF_LOGIN_RATE_LIMIT_MAX, 10, 'BFF_LOGIN_RATE_LIMIT_MAX'),
    loginRateLimitWindowMs: positiveInteger(env.BFF_LOGIN_RATE_LIMIT_WINDOW_MS, 60_000, 'BFF_LOGIN_RATE_LIMIT_WINDOW_MS'),
    cookieSecure,
    mockUpstream,
    sessionStore,
    ...(redisUrl ? { redisUrl } : {}),
    ...(sessionEncryptionKey ? { sessionEncryptionKey } : {}),
    redisConnectTimeoutMs: positiveInteger(env.BFF_REDIS_CONNECT_TIMEOUT_MS, 1_000, 'BFF_REDIS_CONNECT_TIMEOUT_MS'),
    logger: booleanValue(env.BFF_LOGGER, production),
  };
}
