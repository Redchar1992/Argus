import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';

describe('production configuration safeguards', () => {
  it('refuses insecure cookies in production', () => {
    expect(() => loadConfig({ NODE_ENV: 'production', BFF_COOKIE_SECURE: 'false' })).toThrow(
      'BFF_COOKIE_SECURE must be true in production',
    );
  });

  it('refuses the deterministic mock upstream in production', () => {
    expect(() => loadConfig({ NODE_ENV: 'production', BFF_MOCK_UPSTREAM: 'true' })).toThrow(
      'BFF_MOCK_UPSTREAM cannot be enabled in production',
    );
  });

  it('requires the shared Redis session store in production', () => {
    expect(() =>
      loadConfig({
        NODE_ENV: 'production',
        BFF_SESSION_STORE: 'memory',
      }),
    ).toThrow('BFF_SESSION_STORE must be redis in production');
  });

  it('requires a Redis URL and an encryption key for shared sessions', () => {
    expect(() => loadConfig({ BFF_SESSION_STORE: 'redis' })).toThrow(
      'BFF_REDIS_URL is required when BFF_SESSION_STORE=redis',
    );
    expect(() => loadConfig({ BFF_SESSION_STORE: 'redis', BFF_REDIS_URL: 'redis://127.0.0.1:6379' })).toThrow(
      'BFF_SESSION_ENCRYPTION_KEY is required when BFF_SESSION_STORE=redis',
    );
  });

  it('rejects malformed session-encryption keys', () => {
    expect(() =>
      loadConfig({
        BFF_SESSION_STORE: 'redis',
        BFF_REDIS_URL: 'redis://127.0.0.1:6379',
        BFF_SESSION_ENCRYPTION_KEY: 'not-a-32-byte-base64-key',
      }),
    ).toThrow('BFF_SESSION_ENCRYPTION_KEY must be a base64-encoded 32-byte key');
  });

  it('accepts a complete production Redis configuration', () => {
    const config = loadConfig({
      NODE_ENV: 'production',
      BFF_REDIS_URL: 'rediss://redis.internal:6379',
      BFF_SESSION_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString('base64'),
    });
    expect(config.sessionStore).toBe('redis');
    expect(config.cookieSecure).toBe(true);
    expect(config.sessionEncryptionKey).toHaveLength(32);
  });
});
