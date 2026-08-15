import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  MemoryMfaChallengeStore,
  RedisMfaChallengeStore,
  type RedisMfaCommands,
} from '../src/mfa-challenge-store.js';

class FakeRedis implements RedisMfaCommands {
  readonly values = new Map<string, string>();
  readonly ttls = new Map<string, number>();
  async get(key: string): Promise<string | null> { return this.values.get(key) ?? null; }
  async setex(key: string, seconds: number, value: string): Promise<'OK'> {
    this.values.set(key, value); this.ttls.set(key, seconds); return 'OK';
  }
  async del(key: string): Promise<number> {
    this.ttls.delete(key); return this.values.delete(key) ? 1 : 0;
  }
}

const challenge = (expiresAt: number) => ({
  challengeToken: 'backend-challenge-token-that-stays-server-side',
  methods: ['TOTP'] as const,
  username: 'analyst',
  expiresAt,
});

describe('MFA challenge stores', () => {
  it('expires and deletes the memory challenge', async () => {
    let now = 1_000;
    const store = new MemoryMfaChallengeStore(() => now);
    const id = await store.create(challenge(now + 100));
    await expect(store.get(id)).resolves.toEqual(challenge(now + 100));
    now += 101;
    await expect(store.get(id)).resolves.toBeUndefined();
  });

  it('encrypts the backend token, authenticates its id, and deletes it after success', async () => {
    const now = 10_000;
    const redis = new FakeRedis();
    const store = new RedisMfaChallengeStore(redis, Buffer.alloc(32, 2), 300, () => now);
    const value = challenge(now + 60_000);
    const id = await store.create(value);
    const key = `argus:bff:mfa:${id}`;
    const raw = redis.values.get(key)!;
    expect(raw).not.toContain(value.challengeToken);
    expect(redis.ttls.get(key)).toBe(60);

    const copiedId = randomBytes(32).toString('base64url');
    redis.values.set(`argus:bff:mfa:${copiedId}`, raw);
    await expect(store.get(copiedId)).resolves.toBeUndefined();
    await expect(store.get(id)).resolves.toEqual(value);
    await store.delete(id);
    await expect(store.get(id)).resolves.toBeUndefined();
  });
});
