import { createHash, createPublicKey, verify } from 'node:crypto';
import { afterEach, describe, expect, it } from 'vitest';
// The provider is intentionally a standalone Node script, not production BFF code.
// @ts-expect-error JavaScript demo helper has no declaration file.
import { startLocalOidcProvider } from '../scripts/local-oidc-provider.mjs';

interface RunningProvider {
  issuer: string;
  close(): Promise<void>;
}

const active: RunningProvider[] = [];

afterEach(async () => {
  await Promise.all(active.splice(0).map((provider) => provider.close()));
});

async function start(): Promise<RunningProvider> {
  const provider = await startLocalOidcProvider({
    host: '127.0.0.1',
    port: 0,
    clientId: 'argus-web',
    redirectUri: 'http://localhost:5173/bff/auth/oidc/callback',
  }) as RunningProvider;
  active.push(provider);
  return provider;
}

function decodePart(value: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(value, 'base64url').toString('utf8')) as Record<string, unknown>;
}

describe('local mock OIDC provider', () => {
  it('completes a signed Authorization Code + PKCE flow with one-time codes', async () => {
    const provider = await start();
    const discovery = await fetch(`${provider.issuer}/.well-known/openid-configuration`).then((response) => response.json());
    expect(discovery).toMatchObject({
      issuer: provider.issuer,
      response_types_supported: ['code'],
      code_challenge_methods_supported: ['S256'],
    });

    const verifier = 'local-demo-verifier-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG';
    const challenge = createHash('sha256').update(verifier).digest('base64url');
    const authorize = new URL(`${provider.issuer}/authorize`);
    authorize.search = new URLSearchParams({
      client_id: 'argus-web',
      redirect_uri: 'http://localhost:5173/bff/auth/oidc/callback',
      response_type: 'code',
      scope: 'openid profile email',
      state: 'state-123',
      nonce: 'nonce-123',
      code_challenge: challenge,
      code_challenge_method: 'S256',
    }).toString();
    const consent = await fetch(authorize);
    expect(consent.status).toBe(200);
    const consentHtml = await consent.text();
    expect(consentHtml).toContain('LOCAL MOCK IdP');
    const requestId = consentHtml.match(/name="request_id" value="([^"]+)"/)?.[1];
    expect(requestId).toBeTruthy();

    const approval = await fetch(`${provider.issuer}/authorize/approve`, {
      method: 'POST',
      redirect: 'manual',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ request_id: requestId! }),
    });
    expect(approval.status).toBe(302);
    const callback = new URL(approval.headers.get('location')!);
    expect(callback.origin + callback.pathname).toBe('http://localhost:5173/bff/auth/oidc/callback');
    expect(callback.searchParams.get('state')).toBe('state-123');
    const code = callback.searchParams.get('code')!;

    const tokenRequest = () => fetch(`${provider.issuer}/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: 'argus-web',
        redirect_uri: 'http://localhost:5173/bff/auth/oidc/callback',
        code,
        code_verifier: verifier,
      }),
    });
    const tokenResponse = await tokenRequest();
    expect(tokenResponse.status).toBe(200);
    const tokens = await tokenResponse.json() as { id_token: string };
    const [encodedHeader, encodedClaims, encodedSignature] = tokens.id_token.split('.');
    const header = decodePart(encodedHeader!);
    const claims = decodePart(encodedClaims!);
    expect(header.alg).toBe('RS256');
    expect(claims).toMatchObject({
      iss: provider.issuer,
      aud: 'argus-web',
      nonce: 'nonce-123',
      email: 'gray.demo@argus.local',
    });
    const jwks = await fetch(`${provider.issuer}/jwks`).then((response) => response.json()) as {
      keys: JsonWebKey[];
    };
    const key = jwks.keys.find((candidate) => candidate.kid === header.kid);
    expect(key).toBeDefined();
    expect(verify(
      'RSA-SHA256',
      Buffer.from(`${encodedHeader}.${encodedClaims}`),
      createPublicKey({ key: key!, format: 'jwk' }),
      Buffer.from(encodedSignature!, 'base64url'),
    )).toBe(true);

    const replay = await tokenRequest();
    expect(replay.status).toBe(400);
    await expect(replay.json()).resolves.toEqual({ error: 'invalid_grant' });
  });

  it('rejects unregistered redirect URIs before showing approval', async () => {
    const provider = await start();
    const response = await fetch(`${provider.issuer}/authorize?${new URLSearchParams({
      client_id: 'argus-web',
      redirect_uri: 'https://attacker.example/callback',
      response_type: 'code',
      scope: 'openid',
      state: 'state',
      nonce: 'nonce',
      code_challenge: 'A'.repeat(43),
      code_challenge_method: 'S256',
    })}`);
    expect(response.status).toBe(400);
  });

  it('refuses a non-loopback bind address', async () => {
    await expect(startLocalOidcProvider({ host: '0.0.0.0', port: 0 })).rejects.toThrow(/loopback/i);
  });
});
