import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { RedisSessionStore, type RedisSessionCommands } from '../src/redis-session-store.js';
import { EncryptionKeyRing } from '../src/encryption-keyring.js';

class FakeRedis implements RedisSessionCommands {
  readonly values = new Map<string, string>();
  readonly ttls = new Map<string, number>();
  readonly sets = new Map<string, Set<string>>();

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
    const removed = this.values.delete(key) || this.sets.delete(key);
    return removed ? 1 : 0;
  }

  async sadd(key: string, member: string): Promise<number> {
    const values = this.sets.get(key) ?? new Set<string>();
    const size = values.size;
    values.add(member);
    this.sets.set(key, values);
    return values.size > size ? 1 : 0;
  }

  async srem(key: string, member: string): Promise<number> {
    return this.sets.get(key)?.delete(member) ? 1 : 0;
  }

  async smembers(key: string): Promise<string[]> {
    return [...(this.sets.get(key) ?? [])];
  }

  async expire(key: string, seconds: number): Promise<number> {
    if (!this.sets.has(key) && !this.values.has(key)) return 0;
    this.ttls.set(key, seconds);
    return 1;
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

  it('revokes every indexed session for an account after recovery', async () => {
    const redis = new FakeRedis();
    const store = new RedisSessionStore(redis, KEY, 300);
    const first = await store.create('first-token', USER, 300);
    const second = await store.create('second-token', USER, 300);
    const other = await store.create('other-token', { username: 'other', role: 'ANALYST' }, 300);

    await store.deleteForUser(USER.username);
    await expect(store.get(first.id)).resolves.toBeUndefined();
    await expect(store.get(second.id)).resolves.toBeUndefined();
    await expect(store.get(other.id)).resolves.toEqual(other);
  });

  it('lazily re-encrypts an active session with the new primary key', async () => {
    const redis = new FakeRedis();
    const oldKey = Buffer.alloc(32, 1);
    const newKey = Buffer.alloc(32, 2);
    const oldRing = new EncryptionKeyRing('old', new Map([['old', oldKey]]));
    const oldStore = new RedisSessionStore(redis, oldRing, 300);
    const session = await oldStore.create('rotating-jwt', USER, 300);
    const redisKey = `argus:bff:session:${session.id}`;
    const before = JSON.parse(redis.values.get(redisKey)!) as { token: { kid?: string; ciphertext: string } };
    expect(before.token.kid).toBe('old');

    const rotatingRing = new EncryptionKeyRing('new', new Map([['old', oldKey], ['new', newKey]]));
    const events: string[] = [];
    const rotatingStore = new RedisSessionStore(redis, rotatingRing, 300, Date.now, {
      recordKeyRotation: (store) => events.push(`rotated:${store}`),
      recordRejectedRecord: (store) => events.push(`rejected:${store}`),
    });
    await expect(rotatingStore.get(session.id)).resolves.toEqual(session);
    const after = JSON.parse(redis.values.get(redisKey)!) as { token: { kid?: string; ciphertext: string } };
    expect(after.token.kid).toBe('new');
    expect(after.token.ciphertext).not.toBe(before.token.ciphertext);
    expect(events).toEqual(['rotated:session']);

    const newOnly = new RedisSessionStore(
      redis,
      new EncryptionKeyRing('new', new Map([['new', newKey]])),
      300,
    );
    await expect(newOnly.get(session.id)).resolves.toEqual(session);
  });

  it('migrates a pre-key-id envelope and fails closed if its retired key is removed too soon', async () => {
    const redis = new FakeRedis();
    const legacyStore = new RedisSessionStore(redis, KEY, 300);
    const session = await legacyStore.create('legacy-jwt', USER, 300);
    const redisKey = `argus:bff:session:${session.id}`;
    const value = JSON.parse(redis.values.get(redisKey)!) as { token: { kid?: string } };
    delete value.token.kid;
    redis.values.set(redisKey, JSON.stringify(value));

    const newKey = Buffer.alloc(32, 4);
    const rotating = new RedisSessionStore(
      redis,
      new EncryptionKeyRing('new', new Map([['legacy-v1', KEY], ['new', newKey]])),
      300,
    );
    await expect(rotating.get(session.id)).resolves.toEqual(session);
    expect((JSON.parse(redis.values.get(redisKey)!) as { token: { kid: string } }).token.kid).toBe('new');

    const stranded = await legacyStore.create('stranded-jwt', USER, 300);
    const strandedKey = `argus:bff:session:${stranded.id}`;
    const strandedValue = JSON.parse(redis.values.get(strandedKey)!) as { token: { kid?: string } };
    delete strandedValue.token.kid;
    redis.values.set(strandedKey, JSON.stringify(strandedValue));
    const withoutLegacy = new RedisSessionStore(
      redis,
      new EncryptionKeyRing('new', new Map([['new', newKey]])),
      300,
    );
    await expect(withoutLegacy.get(stranded.id)).resolves.toBeUndefined();
    expect(redis.values.has(strandedKey)).toBe(false);
  });
});
