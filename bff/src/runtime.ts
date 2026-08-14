import { Redis } from 'ioredis';
import type { AppDependencies } from './app.js';
import type { AppConfig } from './config.js';
import { RedisSessionStore, type RedisSessionCommands } from './redis-session-store.js';

export async function createRuntimeDependencies(config: AppConfig): Promise<AppDependencies> {
  if (config.sessionStore === 'memory') return {};
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
  };

  return {
    sessions: new RedisSessionStore(
      commands,
      config.sessionEncryptionKey,
      config.sessionTtlSeconds,
    ),
    rateLimitRedis: redis,
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
