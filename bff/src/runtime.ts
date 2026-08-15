import { Redis } from 'ioredis';
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

export async function createRuntimeDependencies(config: AppConfig): Promise<AppDependencies> {
  const oidc = config.oidcEnabled ? await OpenIdClientRelyingParty.discover(config) : undefined;
  if (config.sessionStore === 'memory') {
    return {
      mfaChallenges: new MemoryMfaChallengeStore(),
      ...(oidc ? { oidc, oidcTransactions: new MemoryOidcTransactionStore() } : {}),
    };
  }
  if (!config.redisUrl || !config.sessionEncryptionKey) {
    throw new Error('Redis session configuration is incomplete');
  }

  const redis = new Redis(config.redisUrl, {
    connectionName: 'argus-identity-bff',
    lazyConnect: true,
    connectTimeout: config.redisConnectTimeoutMs,
    maxRetriesPerRequest: 1,
    enableOfflineQueue: false,
  });
  redis.on('error', (error: Error) => {
    // Do not include configuration or commands: URLs can carry credentials and
    // Session values contain encrypted security material.
    console.error(`Redis connection error: ${error.message}`);
  });

  try {
    await redis.connect();
    await redis.ping();
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

  return {
    sessions: new RedisSessionStore(
      commands,
      config.sessionEncryptionKey,
      config.sessionTtlSeconds,
    ),
    rateLimitRedis: redis,
    mfaChallenges: new RedisMfaChallengeStore(
      mfaCommands,
      config.sessionEncryptionKey,
      config.mfaChallengeTtlSeconds,
    ),
    ...(oidc
      ? {
          oidc,
          oidcTransactions: new RedisOidcTransactionStore(
            oidcCommands,
            config.sessionEncryptionKey,
            config.oidcTransactionTtlSeconds,
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
