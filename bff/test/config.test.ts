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
});
