import { Redis } from 'ioredis';
import { readFileSync, statSync } from 'node:fs';
import type { ConnectionOptions } from 'node:tls';
import { EncryptionKeyRing } from './encryption-keyring.js';
import { IdentityMetrics } from './metrics.js';
import type { AppDependencies } from './app.js';
import type { AppConfig } from './config.js';
import {
  MemoryMfaChallengeStore,
  RedisMfaChallengeStore,
  type RedisMfaCommands,
} from './mfa-challenge-store.js';
import { OpenIdClientRelyingParty } from './oidc.js';
import {
  MemoryOidcTransactionStore,
  RedisOidcTransactionStore,
  type RedisOidcCommands,
} from './oidc-transaction-store.js';
import { RedisSessionStore, type RedisSessionCommands } from './redis-session-store.js';
import {
  RedisWebAuthnCeremonyStore,
  type RedisWebAuthnCommands,
} from './webauthn-ceremony-store.js';

export async function createRuntimeDependencies(config: AppConfig): Promise<AppDependencies> {
  const metrics = new IdentityMetrics(config.region);
  if (config.authTlsCertFile) metrics.observeCertificate('auth_client', config.authTlsCertFile);
  if (config.authTlsCaFile) metrics.observeCertificate('auth_ca', config.authTlsCaFile);
  if (config.redisTlsCertFile) metrics.observeCertificate('redis_client', config.redisTlsCertFile);
  if (config.redisTlsCaFile) metrics.observeCertificate('redis_ca', config.redisTlsCaFile);
  const oidc = config.oidcEnabled ? await OpenIdClientRelyingParty.discover(config) : undefined;
  if (config.sessionStore === 'memory') {
    return {
      metrics,
      readiness: async () => undefined,
      mfaChallenges: new MemoryMfaChallengeStore(),
      ...(oidc ? { oidc, oidcTransactions: new MemoryOidcTransactionStore() } : {}),
    };
  }
  if (!config.redisUrl || !config.encryptionPrimaryKeyId || !config.encryptionKeys) {
    throw new Error('Redis session configuration is incomplete');
  }
  const encryption = new EncryptionKeyRing(config.encryptionPrimaryKeyId, config.encryptionKeys);

  let tls: ConnectionOptions | undefined;
  if (new URL(config.redisUrl).protocol === 'rediss:') {
    if (config.redisTlsKeyFile && (statSync(config.redisTlsKeyFile).mode & 0o077) !== 0) {
      throw new Error('BFF Redis TLS private key must not be readable by group or other users');
    }
    tls = {
      rejectUnauthorized: true,
      minVersion: 'TLSv1.2',
      servername: config.redisTlsServerName ?? new URL(config.redisUrl).hostname,
      ...(config.redisTlsCaFile ? { ca: readFileSync(config.redisTlsCaFile) } : {}),
      ...(config.redisTlsCertFile ? { cert: readFileSync(config.redisTlsCertFile) } : {}),
      ...(config.redisTlsKeyFile ? { key: readFileSync(config.redisTlsKeyFile) } : {}),
    };
  }

  const redis = new Redis(config.redisUrl, {
    connectionName: 'argus-identity-bff',
    lazyConnect: true,
    connectTimeout: config.redisConnectTimeoutMs,
    maxRetriesPerRequest: 1,
    enableOfflineQueue: false,
    ...(config.redisUsername ? { username: config.redisUsername } : {}),
    ...(config.redisPassword ? { password: config.redisPassword } : {}),
    ...(tls ? { tls } : {}),
  });
  redis.on('error', (error: Error) => {
    // Do not include configuration or commands: URLs can carry credentials and
    // Session values contain encrypted security material.
    console.error(`Redis connection error: ${error.message}`);
    metrics.setDependency('redis', false);
    metrics.recordDependencyError('redis');
  });
  redis.on('ready', () => metrics.setDependency('redis', true));
  redis.on('close', () => metrics.setDependency('redis', false));

  try {
    await redis.connect();
    await redis.ping();
    metrics.setDependency('redis', true);
  } catch {
    redis.disconnect();
    throw new Error('Redis is unavailable; refusing to start the identity BFF');
  }

  const commands: RedisSessionCommands = {
    get: (key) => redis.get(key),
    setex: (key, seconds, value) => redis.setex(key, seconds, value),
    del: (key) => redis.del(key),
    sadd: (key, member) => redis.sadd(key, member),
    srem: (key, member) => redis.srem(key, member),
    smembers: (key) => redis.smembers(key),
    expire: (key, seconds) => redis.expire(key, seconds),
  };
  const oidcCommands: RedisOidcCommands = {
    setex: (key, seconds, value) => redis.setex(key, seconds, value),
    getdel: (key) => redis.getdel(key),
  };
  const mfaCommands: RedisMfaCommands = {
    get: (key) => redis.get(key),
    setex: (key, seconds, value) => redis.setex(key, seconds, value),
    del: (key) => redis.del(key),
  };
  const webauthnCommands: RedisWebAuthnCommands = {
    setex: (key, seconds, value) => redis.setex(key, seconds, value),
    getdel: (key) => redis.getdel(key),
  };

  return {
    metrics,
    readiness: async () => {
      await redis.ping();
      metrics.setDependency('redis', true);
    },
    sessions: new RedisSessionStore(
      commands,
      encryption,
      config.sessionTtlSeconds,
      Date.now,
      metrics,
    ),
    rateLimitRedis: redis,
    mfaChallenges: new RedisMfaChallengeStore(
      mfaCommands,
      encryption,
      config.mfaChallengeTtlSeconds,
      Date.now,
      metrics,
    ),
    webauthnCeremonies: new RedisWebAuthnCeremonyStore(
      webauthnCommands,
      encryption,
      config.webauthnCeremonyTtlSeconds,
      Date.now,
      metrics,
    ),
    ...(oidc
      ? {
          oidc,
          oidcTransactions: new RedisOidcTransactionStore(
            oidcCommands,
            encryption,
            config.oidcTransactionTtlSeconds,
            Date.now,
            metrics,
          ),
        }
      : {}),
    close: async () => {
      if (redis.status === 'end') return;
      if (redis.status === 'ready') {
        await redis.quit();
      } else {
        redis.disconnect();
      }
    },
  };
}
