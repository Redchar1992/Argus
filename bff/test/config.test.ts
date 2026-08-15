import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';

const SECURE_PRODUCTION = {
  NODE_ENV: 'production',
  ARGUS_AUTH_URL: 'https://auth.internal:8443',
  BFF_AUTH_MTLS_ENABLED: 'true',
  BFF_AUTH_TLS_CA_FILE: '/run/secrets/auth-ca.pem',
  BFF_AUTH_TLS_CERT_FILE: '/run/secrets/bff-client.pem',
  BFF_AUTH_TLS_KEY_FILE: '/run/secrets/bff-client-key.pem',
  BFF_REDIS_URL: 'rediss://redis.internal:6379',
  BFF_REDIS_PASSWORD: 'production-redis-password',
  BFF_ENCRYPTION_PRIMARY_KEY_ID: 'prod-v2',
  BFF_ENCRYPTION_KEYS: `prod-v2:${Buffer.alloc(32, 7).toString('base64')}`,
  BFF_METRICS_TOKEN: 'production-metrics-token-1234567890',
};

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
      'BFF_ENCRYPTION_KEYS is required when BFF_SESSION_STORE=redis',
    );
  });

  it('rejects malformed session-encryption keys', () => {
    expect(() =>
      loadConfig({
        BFF_SESSION_STORE: 'redis',
        BFF_REDIS_URL: 'redis://127.0.0.1:6379',
        BFF_SESSION_ENCRYPTION_KEY: 'not-a-32-byte-base64-key',
      }),
    ).toThrow('BFF_SESSION_ENCRYPTION_KEY must contain base64-encoded 32-byte keys');
  });

  it('validates a versioned encryption key ring for online rotation', () => {
    const oldKey = Buffer.alloc(32, 1).toString('base64');
    const newKey = Buffer.alloc(32, 2).toString('base64');
    const config = loadConfig({
      BFF_SESSION_STORE: 'redis',
      BFF_REDIS_URL: 'redis://127.0.0.1:6379',
      BFF_ENCRYPTION_PRIMARY_KEY_ID: 'new-v2',
      BFF_ENCRYPTION_KEYS: `old-v1:${oldKey},new-v2:${newKey}`,
    });
    expect(config.encryptionPrimaryKeyId).toBe('new-v2');
    expect([...config.encryptionKeys!.keys()]).toEqual(['old-v1', 'new-v2']);
    expect(() => loadConfig({
      BFF_SESSION_STORE: 'redis',
      BFF_REDIS_URL: 'redis://127.0.0.1:6379',
      BFF_ENCRYPTION_PRIMARY_KEY_ID: 'missing',
      BFF_ENCRYPTION_KEYS: `old-v1:${oldKey}`,
    })).toThrow('BFF_ENCRYPTION_PRIMARY_KEY_ID must reference');
  });

  it('accepts a complete production Redis configuration', () => {
    const config = loadConfig({
      ...SECURE_PRODUCTION,
      BFF_WEBAUTHN_RP_ID: 'argus.example',
      BFF_WEBAUTHN_ORIGIN: 'https://argus.example',
      ARGUS_INTERNAL_BFF_SECRET: 'production-bff-workload-secret-1234567890',
    });
    expect(config.sessionStore).toBe('redis');
    expect(config.cookieSecure).toBe(true);
    expect(config.encryptionPrimaryKeyId).toBe('prod-v2');
    expect(config.encryptionKeys?.get('prod-v2')).toHaveLength(32);
    expect(config.webauthnOrigin).toBe('https://argus.example');
    expect(config.authMtlsEnabled).toBe(true);
    expect(config.redisPassword).toBe('production-redis-password');
    expect(config.metricsToken).toBe('production-metrics-token-1234567890');
  });

  it('requires mTLS to auth-service and authenticated rediss in production', () => {
    const withoutMtls = {
      NODE_ENV: 'production',
      BFF_REDIS_URL: 'rediss://redis.internal:6379',
      BFF_REDIS_PASSWORD: 'redis-secret',
      BFF_ENCRYPTION_PRIMARY_KEY_ID: 'prod-v2',
      BFF_ENCRYPTION_KEYS: `prod-v2:${Buffer.alloc(32, 7).toString('base64')}`,
      BFF_PASSKEY_ENABLED: 'false',
    };
    expect(() => loadConfig(withoutMtls)).toThrow('Production requires authenticated TLS for the auth service');
    expect(() => loadConfig({ ...SECURE_PRODUCTION, BFF_REDIS_URL: 'redis://redis.internal:6379' })).toThrow(
      'BFF_REDIS_URL must use rediss in production',
    );
    expect(() => loadConfig({ ...SECURE_PRODUCTION, BFF_REDIS_PASSWORD: undefined })).toThrow(
      'BFF_REDIS_PASSWORD is required for production Redis authentication',
    );
    expect(() => loadConfig({ BFF_AUTH_MTLS_ENABLED: 'true', ARGUS_AUTH_URL: 'https://auth.internal' })).toThrow(
      'BFF auth mTLS requires CA, client certificate, and client key files',
    );
  });

  it('requires an exact HTTPS WebAuthn origin and a rotated workload secret in production', () => {
    const production = {
      ...SECURE_PRODUCTION,
      BFF_WEBAUTHN_RP_ID: 'argus.example',
    };
    expect(() => loadConfig({ ...production, BFF_WEBAUTHN_ORIGIN: 'http://argus.example' })).toThrow(
      'BFF_WEBAUTHN_ORIGIN must use https in production',
    );
    expect(() => loadConfig({ ...production, BFF_WEBAUTHN_ORIGIN: 'https://argus.example' })).toThrow(
      'ARGUS_INTERNAL_BFF_SECRET must be changed in production',
    );
    expect(() => loadConfig({ BFF_WEBAUTHN_RP_ID: 'https://argus.example/path' })).toThrow(
      'BFF_WEBAUTHN_RP_ID must be a hostname',
    );
    expect(() => loadConfig({
      BFF_WEBAUTHN_RP_ID: 'other.example',
      BFF_WEBAUTHN_ORIGIN: 'https://login.argus.example',
    })).toThrow('BFF_WEBAUTHN_RP_ID must equal or be a domain suffix');
  });

  it('requires complete OIDC settings and HTTPS in production', () => {
    expect(() => loadConfig({ BFF_OIDC_ENABLED: 'true' })).toThrow(
      'BFF_OIDC_ISSUER, BFF_OIDC_CLIENT_ID, and BFF_OIDC_REDIRECT_URI are required',
    );
    expect(() => loadConfig({
      ...SECURE_PRODUCTION,
      BFF_OIDC_ENABLED: 'true',
      BFF_OIDC_ISSUER: 'http://idp.internal',
      BFF_OIDC_CLIENT_ID: 'argus',
      BFF_OIDC_REDIRECT_URI: 'https://argus.example/bff/auth/oidc/callback',
    })).toThrow('OIDC issuer and redirect URI must use https in production');
  });

  it('requires protected metrics in production and validates the region label', () => {
    expect(() => loadConfig({ ...SECURE_PRODUCTION, BFF_PASSKEY_ENABLED: 'false', BFF_METRICS_TOKEN: undefined })).toThrow(
      'BFF_METRICS_TOKEN is required when production metrics are enabled',
    );
    expect(() => loadConfig({ BFF_METRICS_TOKEN: 'short' })).toThrow(
      'BFF_METRICS_TOKEN must be between 32 and 256 characters',
    );
    expect(() => loadConfig({ ARGUS_REGION: 'invalid region!' })).toThrow('ARGUS_REGION must use');
    expect(loadConfig({ BFF_METRICS_ENABLED: 'false' }).metricsEnabled).toBe(false);
  });

  it('rejects an OIDC scope without openid and external post-login redirects', () => {
    const oidc = {
      BFF_OIDC_ENABLED: 'true',
      BFF_OIDC_ISSUER: 'https://idp.example',
      BFF_OIDC_CLIENT_ID: 'argus',
      BFF_OIDC_REDIRECT_URI: 'http://localhost:3001/bff/auth/oidc/callback',
    };
    expect(() => loadConfig({ ...oidc, BFF_OIDC_SCOPES: 'profile email' })).toThrow(
      'BFF_OIDC_SCOPES must include openid',
    );
    expect(() => loadConfig({ ...oidc, BFF_OIDC_SUCCESS_REDIRECT: 'https://attacker.example' })).toThrow(
      'BFF_OIDC_SUCCESS_REDIRECT must be a same-origin absolute path',
    );
  });
});
