import { randomBytes } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  MemoryOidcTransactionStore,
  RedisOidcTransactionStore,
  type RedisOidcCommands,
} from '../src/oidc-transaction-store.js';

class FakeRedis implements RedisOidcCommands {
  readonly values = new Map<string, string>();
  readonly ttls = new Map<string, number>();

  async setex(key: string, seconds: number, value: string): Promise<'OK'> {
    this.values.set(key, value);
    this.ttls.set(key, seconds);
    return 'OK';
  }

  async getdel(key: string): Promise<string | null> {
    const value = this.values.get(key) ?? null;
    this.values.delete(key);
    this.ttls.delete(key);
    return value;
  }
}

const transaction = (expiresAt: number) => ({
  state: 'state-value-that-is-at-least-thirty-two-characters',
  nonce: 'nonce-value-that-is-at-least-thirty-two-characters',
  codeVerifier: 'pkce-verifier-that-is-at-least-thirty-two-characters',
  expiresAt,
});

describe('OIDC transaction stores', () => {
  it('consumes an in-memory transaction exactly once and rejects expiry', async () => {
    let now = 1_000;
    const store = new MemoryOidcTransactionStore(() => now);
    const id = await store.create(transaction(now + 100));
    await expect(store.consume(id)).resolves.toEqual(transaction(now + 100));
    await expect(store.consume(id)).resolves.toBeUndefined();

    const expired = await store.create(transaction(now + 100));
    now += 101;
    await expect(store.consume(expired)).resolves.toBeUndefined();
  });

  it('encrypts Redis state/nonce/PKCE material and binds it to its opaque id', async () => {
    const now = 10_000;
    const redis = new FakeRedis();
    const store = new RedisOidcTransactionStore(redis, Buffer.alloc(32, 4), 300, () => now);
    const value = transaction(now + 60_000);
    const id = await store.create(value);
    const key = `argus:bff:oidc:${id}`;
    const raw = redis.values.get(key)!;
    expect(raw).not.toContain(value.state);
    expect(raw).not.toContain(value.nonce);
    expect(raw).not.toContain(value.codeVerifier);
    expect(redis.ttls.get(key)).toBe(300);

    const copiedId = randomBytes(32).toString('base64url');
    redis.values.set(`argus:bff:oidc:${copiedId}`, raw);
    await expect(store.consume(copiedId)).resolves.toBeUndefined();
    await expect(store.consume(id)).resolves.toEqual(value);
    await expect(store.consume(id)).resolves.toBeUndefined();
  });

  it('fails closed on a corrupt encrypted Redis transaction', async () => {
    const now = 20_000;
    const redis = new FakeRedis();
    const store = new RedisOidcTransactionStore(redis, Buffer.alloc(32, 8), 300, () => now);
    const id = await store.create(transaction(now + 60_000));
    redis.values.set(`argus:bff:oidc:${id}`, '{"version":1,"iv":"tampered"}');
    await expect(store.consume(id)).resolves.toBeUndefined();
  });
});
