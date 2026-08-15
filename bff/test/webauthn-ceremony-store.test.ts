import { describe, expect, it } from 'vitest';
import {
  MemoryWebAuthnCeremonyStore,
  RedisWebAuthnCeremonyStore,
  type RedisWebAuthnCommands,
} from '../src/webauthn-ceremony-store.js';

class FakeRedis implements RedisWebAuthnCommands {
  readonly values = new Map<string, string>();

  async setex(key: string, _seconds: number, value: string): Promise<void> {
    this.values.set(key, value);
  }

  async getdel(key: string): Promise<string | null> {
    const value = this.values.get(key) ?? null;
    this.values.delete(key);
    return value;
  }
}

describe('WebAuthn ceremony stores', () => {
  it('encrypts Redis values and consumes each ceremony exactly once', async () => {
    const redis = new FakeRedis();
    const store = new RedisWebAuthnCeremonyStore(redis, Buffer.alloc(32, 5), 300);
    const ceremony = {
      kind: 'registration' as const,
      challenge: 'a'.repeat(43),
      username: 'analyst',
      expiresAt: Date.now() + 60_000,
    };
    const id = await store.create(ceremony);
    const raw = [...redis.values.values()][0]!;
    expect(raw).not.toContain(ceremony.challenge);
    expect(raw).not.toContain(ceremony.username);
    await expect(store.consume(id)).resolves.toEqual(ceremony);
    await expect(store.consume(id)).resolves.toBeUndefined();
  });

  it('fails closed when the encrypted envelope is tampered with', async () => {
    const redis = new FakeRedis();
    const store = new RedisWebAuthnCeremonyStore(redis, Buffer.alloc(32, 7), 300);
    const id = await store.create({
      kind: 'authentication',
      challenge: 'b'.repeat(43),
      expiresAt: Date.now() + 60_000,
    });
    const key = [...redis.values.keys()][0]!;
    const envelope = JSON.parse(redis.values.get(key)!) as Record<string, string>;
    envelope.ciphertext = `${envelope.ciphertext![0] === 'A' ? 'B' : 'A'}${envelope.ciphertext!.slice(1)}`;
    redis.values.set(key, JSON.stringify(envelope));
    await expect(store.consume(id)).resolves.toBeUndefined();
  });

  it('rejects expired process-local ceremonies', async () => {
    let now = 1_000;
    const store = new MemoryWebAuthnCeremonyStore(Buffer.alloc(32, 9), () => now);
    const id = await store.create({
      kind: 'authentication',
      challenge: 'c'.repeat(43),
      expiresAt: 2_000,
    });
    now = 2_001;
    await expect(store.consume(id)).resolves.toBeUndefined();
  });
});
