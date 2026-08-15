#!/usr/bin/env node

import {
  createHash,
  generateKeyPairSync,
  randomBytes,
  randomUUID,
  sign,
  timingSafeEqual,
} from 'node:crypto';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';

const MAX_FORM_BYTES = 16 * 1024;
const TRANSACTION_TTL_MS = 5 * 60 * 1000;

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}

function html(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function sameValue(left, right) {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

function json(response, status, body) {
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': 'application/json; charset=utf-8',
    pragma: 'no-cache',
    'x-content-type-options': 'nosniff',
  });
  response.end(JSON.stringify(body));
}

function text(response, status, body) {
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': 'text/plain; charset=utf-8',
    pragma: 'no-cache',
    'x-content-type-options': 'nosniff',
  });
  response.end(body);
}

function redirect(response, location) {
  response.writeHead(302, {
    'cache-control': 'no-store',
    location,
    pragma: 'no-cache',
    'referrer-policy': 'no-referrer',
  });
  response.end();
}

async function formBody(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > MAX_FORM_BYTES) throw new Error('request body too large');
    chunks.push(chunk);
  }
  return new URLSearchParams(Buffer.concat(chunks).toString('utf8'));
}

function compactJwt(privateKey, kid, claims) {
  const header = base64url(JSON.stringify({ alg: 'RS256', kid, typ: 'JWT' }));
  const payload = base64url(JSON.stringify(claims));
  const signingInput = `${header}.${payload}`;
  const signature = sign('RSA-SHA256', Buffer.from(signingInput), privateKey).toString('base64url');
  return `${signingInput}.${signature}`;
}

function authorizationPage(requestId, clientId, redirectUri, identity) {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>Argus local mock identity provider</title>
  <style>
    :root { color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, sans-serif; }
    body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #0c0a14; color: #e6e9ef; padding: 24px; }
    main { width: min(520px, 100%); border: 1px solid #3b3150; border-radius: 18px; padding: 28px; background: linear-gradient(180deg,#18141f,#14111d); box-shadow: 0 24px 80px #0008; }
    .badge { display: inline-block; color: #ffcf70; border: 1px solid #6c5224; background: #2d230f; border-radius: 999px; padding: 4px 10px; font-size: 12px; font-weight: 800; letter-spacing: .04em; }
    h1 { margin: 18px 0 8px; font-size: 26px; }
    p { color: #9aa3b4; line-height: 1.6; }
    .profile { border: 1px solid #2a2336; border-radius: 12px; padding: 14px; margin: 20px 0; background: #0f0c17; }
    .profile strong, .profile span { display: block; }
    .profile span, code { color: #c4b5fd; }
    button { width: 100%; border: 0; border-radius: 10px; padding: 12px 16px; cursor: pointer; font-weight: 800; background: linear-gradient(135deg,#8b5cf6,#c4b5fd); color: #0c0a14; }
    small { display: block; color: #7f899a; margin-top: 16px; line-height: 1.5; }
  </style>
</head>
<body>
  <main>
    <span class="badge">LOCAL MOCK IdP · NOT A REAL IDENTITY</span>
    <h1>Continue to Argus</h1>
    <p>This page replaces an external enterprise identity provider for a repeatable local demo. The OIDC code flow, PKCE, state, nonce, JWKS signature, issuer and audience validation remain real.</p>
    <div class="profile">
      <strong>${html(identity.name)}</strong>
      <span>${html(identity.email)}</span>
    </div>
    <form method="post" action="/authorize/approve">
      <input type="hidden" name="request_id" value="${html(requestId)}">
      <button type="submit">Continue as ${html(identity.name)}</button>
    </form>
    <small>Client <code>${html(clientId)}</code> · callback <code>${html(redirectUri)}</code><br>No password, KYC, corporate directory or external account is checked by this mock.</small>
  </main>
</body>
</html>`;
}

function validateAuthorization(url, options) {
  const value = Object.fromEntries(url.searchParams);
  const scopes = (value.scope ?? '').split(/\s+/).filter(Boolean);
  if (
    value.client_id !== options.clientId
    || value.redirect_uri !== options.redirectUri
    || value.response_type !== 'code'
    || !scopes.includes('openid')
    || !value.state
    || value.state.length > 256
    || !value.nonce
    || value.nonce.length > 256
    || value.code_challenge_method !== 'S256'
    || !/^[A-Za-z0-9_-]{43,128}$/.test(value.code_challenge ?? '')
  ) {
    return undefined;
  }
  return {
    clientId: value.client_id,
    redirectUri: value.redirect_uri,
    state: value.state,
    nonce: value.nonce,
    codeChallenge: value.code_challenge,
    expiresAt: Date.now() + TRANSACTION_TTL_MS,
  };
}

/**
 * Starts a loopback-only OIDC provider used exclusively by the executable local demo.
 * It intentionally models no real directory, KYC proof, password, consent policy, or MFA.
 */
export async function startLocalOidcProvider(options = {}) {
  const host = options.host ?? '127.0.0.1';
  const port = options.port ?? 9091;
  if (!['127.0.0.1', '::1', 'localhost'].includes(host)) {
    throw new Error('The local mock OIDC provider may bind only to a loopback host');
  }
  const clientId = options.clientId ?? 'argus-web';
  const redirectUri = options.redirectUri ?? 'http://localhost:5173/bff/auth/oidc/callback';
  const identity = options.identity ?? {
    sub: 'argus-local-gray-demo',
    name: 'Gray Demo',
    email: 'gray.demo@argus.local',
  };
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const kid = `local-${randomBytes(8).toString('hex')}`;
  const jwk = { ...publicKey.export({ format: 'jwk' }), alg: 'RS256', kid, use: 'sig' };
  const pendingAuthorizations = new Map();
  const codes = new Map();
  let issuer = options.issuer;

  const server = createServer(async (request, response) => {
    try {
      const base = issuer ?? `http://localhost:${port}`;
      const url = new URL(request.url ?? '/', base);

      for (const [id, transaction] of [...pendingAuthorizations, ...codes]) {
        if (transaction.expiresAt <= Date.now()) {
          pendingAuthorizations.delete(id);
          codes.delete(id);
        }
      }

      if (request.method === 'GET' && url.pathname === '/health') {
        return json(response, 200, { status: 'ok', provider: 'local-mock', issuer: base });
      }
      if (request.method === 'GET' && url.pathname === '/.well-known/openid-configuration') {
        return json(response, 200, {
          issuer: base,
          authorization_endpoint: `${base}/authorize`,
          token_endpoint: `${base}/token`,
          jwks_uri: `${base}/jwks`,
          response_types_supported: ['code'],
          grant_types_supported: ['authorization_code'],
          subject_types_supported: ['public'],
          id_token_signing_alg_values_supported: ['RS256'],
          token_endpoint_auth_methods_supported: ['none'],
          scopes_supported: ['openid', 'profile', 'email'],
          claims_supported: ['iss', 'sub', 'aud', 'exp', 'iat', 'nonce', 'name', 'email'],
          code_challenge_methods_supported: ['S256'],
        });
      }
      if (request.method === 'GET' && url.pathname === '/jwks') {
        return json(response, 200, { keys: [jwk] });
      }
      if (request.method === 'GET' && url.pathname === '/authorize') {
        const authorization = validateAuthorization(url, { clientId, redirectUri });
        if (!authorization) return text(response, 400, 'Invalid local OIDC authorization request');
        const requestId = randomUUID();
        pendingAuthorizations.set(requestId, authorization);
        const body = authorizationPage(requestId, clientId, redirectUri, identity);
        response.writeHead(200, {
          'cache-control': 'no-store',
          // The handler validates the exact registered callback and one-time
          // request ID. form-action is omitted because Chromium treats some
          // localhost IPv4/IPv6 transitions as cross-origin and blocks even an
          // explicit same-host directive in local development.
          'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
          'content-type': 'text/html; charset=utf-8',
          pragma: 'no-cache',
          'referrer-policy': 'no-referrer',
          'x-content-type-options': 'nosniff',
          'x-frame-options': 'DENY',
        });
        return response.end(body);
      }
      if (request.method === 'POST' && url.pathname === '/authorize/approve') {
        const form = await formBody(request);
        const requestId = form.get('request_id') ?? '';
        const authorization = pendingAuthorizations.get(requestId);
        pendingAuthorizations.delete(requestId);
        if (!authorization || authorization.expiresAt <= Date.now()) {
          return text(response, 400, 'The local authorization request expired');
        }
        const code = randomBytes(32).toString('base64url');
        codes.set(code, authorization);
        const callback = new URL(authorization.redirectUri);
        callback.searchParams.set('code', code);
        callback.searchParams.set('state', authorization.state);
        return redirect(response, callback.toString());
      }
      if (request.method === 'POST' && url.pathname === '/token') {
        const form = await formBody(request);
        const code = form.get('code') ?? '';
        const authorization = codes.get(code);
        codes.delete(code);
        const verifier = form.get('code_verifier') ?? '';
        const challenge = createHash('sha256').update(verifier).digest('base64url');
        if (
          !authorization
          || authorization.expiresAt <= Date.now()
          || form.get('grant_type') !== 'authorization_code'
          || form.get('client_id') !== clientId
          || form.get('redirect_uri') !== redirectUri
          || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)
          || !sameValue(challenge, authorization.codeChallenge)
        ) {
          return json(response, 400, { error: 'invalid_grant' });
        }
        const issuedAt = Math.floor(Date.now() / 1000);
        const idToken = compactJwt(privateKey, kid, {
          iss: base,
          sub: identity.sub,
          aud: clientId,
          exp: issuedAt + 300,
          iat: issuedAt,
          nonce: authorization.nonce,
          name: identity.name,
          email: identity.email,
        });
        return json(response, 200, {
          access_token: randomBytes(32).toString('base64url'),
          token_type: 'Bearer',
          expires_in: 300,
          id_token: idToken,
        });
      }
      return text(response, 404, 'Not found');
    } catch {
      return json(response, 500, { error: 'server_error' });
    }
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      resolve();
    });
  });
  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  issuer ??= `http://localhost:${actualPort}`;

  return {
    issuer,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
  };
}

async function main() {
  const provider = await startLocalOidcProvider({
    host: process.env.ARGUS_MOCK_OIDC_HOST ?? '127.0.0.1',
    port: Number(process.env.ARGUS_MOCK_OIDC_PORT ?? '9091'),
    issuer: process.env.ARGUS_MOCK_OIDC_ISSUER,
    clientId: process.env.ARGUS_MOCK_OIDC_CLIENT_ID ?? 'argus-web',
    redirectUri: process.env.ARGUS_MOCK_OIDC_REDIRECT_URI
      ?? 'http://localhost:5173/bff/auth/oidc/callback',
  });
  console.log(`Argus LOCAL MOCK OIDC provider listening at ${provider.issuer}`);
  console.log('No external identity, KYC record, password, directory, or provider MFA is checked.');
  const shutdown = async () => {
    await provider.close();
    process.exit(0);
  };
  process.on('SIGINT', () => void shutdown());
  process.on('SIGTERM', () => void shutdown());
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : 'Local OIDC provider failed');
    process.exit(1);
  });
}
