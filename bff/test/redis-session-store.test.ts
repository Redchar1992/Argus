import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { RedisSessionStore, type RedisSessionCommands } from '../src/redis-session-store.js';

class FakeRedis implements RedisSessionCommands {
  readonly values = new Map<string, string>();
  readonly ttls = new Map<string, number>();

  async get(key: string): Promise<string | null> {
    return this.values.get(key) ?? null;
  }

  async setex(key: string, seconds: number, value: string): Promise<'OK'> {
    this.values.set(key, value);
    this.ttls.set(key, seconds);
    return 'OK';
  }

  async del(key: string): Promise<number> {
    this.ttls.delete(key);
    return this.values.delete(key) ? 1 : 0;
  }
}

const KEY = Buffer.alloc(32, 9);
const USER = { username: 'analyst', role: 'ANALYST' } as const;

describe('RedisSessionStore', () => {
  it('stores only encrypted bearer material and restores the session', async () => {
    const redis = new FakeRedis();
    const store = new RedisSessionStore(redis, KEY, 300, () => 1_000);
    const session = await store.create('jwt-secret-value', USER, 600);

    const key = `argus:bff:session:${session.id}`;
    const raw = redis.values.get(key);
    expect(raw).toBeDefined();
    expect(raw).not.toContain('jwt-secret-value');
    expect(redis.ttls.get(key)).toBe(300);
    await expect(store.get(session.id)).resolves.toEqual(session);
  });

  it('binds encrypted token material to its opaque session id', async () => {
    const redis = new FakeRedis();
    const store = new RedisSessionStore(redis, KEY, 300);
    const session = await store.create('jwt-secret-value', USER, 300);
    const originalKey = `argus:bff:session:${session.id}`;
    const copiedId = randomBytes(32).toString('base64url');
    const copiedKey = `argus:bff:session:${copiedId}`;
    redis.values.set(copiedKey, redis.values.get(originalKey)!);

    await expect(store.get(copiedId)).resolves.toBeUndefined();
    expect(redis.values.has(copiedKey)).toBe(false);
  });

  it('fails closed and deletes corrupt or expired records', async () => {
    let now = 10_000;
    const redis = new FakeRedis();
    const store = new RedisSessionStore(redis, KEY, 60, () => now);
    const session = await store.create('jwt-secret-value', USER, 60);
    const key = `argus:bff:session:${session.id}`;

    redis.values.set(key, '{not-json');
    await expect(store.get(session.id)).resolves.toBeUndefined();
    expect(redis.values.has(key)).toBe(false);

    const expiring = await store.create('another-token', USER, 1);
    now += 1_001;
    await expect(store.get(expiring.id)).resolves.toBeUndefined();
    expect(redis.values.has(`argus:bff:session:${expiring.id}`)).toBe(false);
  });

  it('removes a session on logout', async () => {
    const redis = new FakeRedis();
    const store = new RedisSessionStore(redis, KEY, 300);
    const session = await store.create('jwt-secret-value', USER, 300);

    await store.delete(session.id);
    await expect(store.get(session.id)).resolves.toBeUndefined();
  });
});
