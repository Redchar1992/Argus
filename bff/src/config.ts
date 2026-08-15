export interface AppConfig {
  host: string;
  port: number;
  authBaseUrl: string;
  investigationBaseUrl: string;
  authMtlsEnabled: boolean;
  authTlsCaFile?: string;
  authTlsCertFile?: string;
  authTlsKeyFile?: string;
  authTlsServerName?: string;
  allowedOrigins: ReadonlySet<string>;
  requestTimeoutMs: number;
  sessionTtlSeconds: number;
  loginRateLimitMax: number;
  loginRateLimitWindowMs: number;
  cookieSecure: boolean;
  mockUpstream: boolean;
  sessionStore: 'memory' | 'redis';
  redisUrl?: string;
  redisUsername?: string;
  redisPassword?: string;
  redisTlsCaFile?: string;
  redisTlsCertFile?: string;
  redisTlsKeyFile?: string;
  redisTlsServerName?: string;
  encryptionPrimaryKeyId?: string;
  encryptionKeys?: ReadonlyMap<string, Buffer>;
  redisConnectTimeoutMs: number;
  oidcEnabled: boolean;
  oidcIssuer?: string;
  oidcClientId?: string;
  oidcClientSecret?: string;
  oidcRedirectUri?: string;
  oidcScopes: string;
  oidcSuccessRedirect: string;
  oidcErrorRedirect: string;
  oidcTransactionTtlSeconds: number;
  mfaChallengeTtlSeconds: number;
  mfaRequiredRedirect: string;
  passkeyEnabled: boolean;
  webauthnRpId: string;
  webauthnRpName: string;
  webauthnOrigin: string;
  webauthnCeremonyTtlSeconds: number;
  internalBffSecret: string;
  region: string;
  metricsEnabled: boolean;
  metricsToken?: string;
  logger: boolean;
}

function positiveInteger(value: string | undefined, fallback: number, name: string): number {
  if (value === undefined || value === '') return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function booleanValue(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined || value === '') return fallback;
  return value.toLowerCase() === 'true';
}

function originSet(value: string | undefined): ReadonlySet<string> {
  const configured = value ?? 'http://localhost:5173,http://127.0.0.1:5173';
  const origins = configured
    .split(',')
    .map((origin) => origin.trim().replace(/\/$/, ''))
    .filter(Boolean);
  if (origins.length === 0) throw new Error('BFF_ALLOWED_ORIGINS must contain at least one origin');
  for (const origin of origins) {
    const parsed = new URL(origin);
    if (parsed.origin !== origin) {
      throw new Error(`BFF_ALLOWED_ORIGINS entry must be an origin without a path: ${origin}`);
    }
  }
  return new Set(origins);
}

function sessionStoreValue(value: string | undefined, production: boolean): 'memory' | 'redis' {
  const store = value ?? (production ? 'redis' : 'memory');
  if (store !== 'memory' && store !== 'redis') {
    throw new Error('BFF_SESSION_STORE must be memory or redis');
  }
  return store;
}

function decodeEncryptionKey(value: string | undefined, name: string): Buffer | undefined {
  if (!value) return undefined;
  const normalized = value.trim();
  const decoded = Buffer.from(normalized, 'base64');
  const canonical = decoded.toString('base64').replace(/=+$/, '');
  if (decoded.length !== 32 || canonical !== normalized.replace(/=+$/, '')) {
    throw new Error(`${name} must contain base64-encoded 32-byte keys`);
  }
  return decoded;
}

function encryptionConfiguration(env: NodeJS.ProcessEnv, production: boolean): {
  primaryKeyId?: string;
  keys?: ReadonlyMap<string, Buffer>;
} {
  const configured = env.BFF_ENCRYPTION_KEYS?.trim();
  const legacy = env.BFF_SESSION_ENCRYPTION_KEY?.trim();
  if (configured && legacy) {
    throw new Error('Configure BFF_ENCRYPTION_KEYS or legacy BFF_SESSION_ENCRYPTION_KEY, not both');
  }
  if (configured) {
    const primaryKeyId = env.BFF_ENCRYPTION_PRIMARY_KEY_ID?.trim() || '';
    const keys = new Map<string, Buffer>();
    for (const entry of configured.split(',')) {
      const separator = entry.indexOf(':');
      const keyId = separator > 0 ? entry.slice(0, separator).trim() : '';
      const encoded = separator > 0 ? entry.slice(separator + 1).trim() : '';
      if (!/^[A-Za-z0-9_-]{1,32}$/.test(keyId) || keys.has(keyId) || !encoded) {
        throw new Error('BFF_ENCRYPTION_KEYS must use unique kid:base64 entries');
      }
      keys.set(keyId, decodeEncryptionKey(encoded, 'BFF_ENCRYPTION_KEYS')!);
    }
    if (keys.size > 8) throw new Error('BFF_ENCRYPTION_KEYS supports at most 8 retained keys');
    if (!primaryKeyId || !keys.has(primaryKeyId)) {
      throw new Error('BFF_ENCRYPTION_PRIMARY_KEY_ID must reference BFF_ENCRYPTION_KEYS');
    }
    return { primaryKeyId, keys };
  }
  if (legacy) {
    if (production) {
      throw new Error('Production requires the versioned BFF_ENCRYPTION_KEYS key ring');
    }
    return {
      primaryKeyId: 'legacy-v1',
      keys: new Map([['legacy-v1', decodeEncryptionKey(legacy, 'BFF_SESSION_ENCRYPTION_KEY')!]]),
    };
  }
  return {};
}

function absoluteUrl(value: string | undefined, name: string): string | undefined {
  if (!value) return undefined;
  const parsed = new URL(value);
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error(`${name} must use http or https`);
  }
  return parsed.toString();
}

function serviceBaseUrl(value: string | undefined, fallback: string, name: string): string {
  const candidate = value?.trim() || fallback;
  const parsed = new URL(candidate);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password
    || parsed.search || parsed.hash || (parsed.pathname !== '/' && parsed.pathname !== '')) {
    throw new Error(`${name} must be an http or https base origin without credentials or a path`);
  }
  return parsed.origin;
}

function optionalPath(value: string | undefined): string | undefined {
  return value?.trim() || undefined;
}

function localRedirect(value: string | undefined, fallback: string, name: string): string {
  const redirect = value ?? fallback;
  if (!redirect.startsWith('/') || redirect.startsWith('//') || redirect.includes('\\')) {
    throw new Error(`${name} must be a same-origin absolute path`);
  }
  return redirect;
}

function origin(value: string | undefined, fallback: string, name: string): string {
  const candidate = value ?? fallback;
  const parsed = new URL(candidate);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.origin !== candidate.replace(/\/$/, '')) {
    throw new Error(`${name} must be an http or https origin without a path`);
  }
  return parsed.origin;
}

function rpId(value: string | undefined): string {
  const candidate = value?.trim() || 'localhost';
  if (
    candidate.length > 253 ||
    candidate.includes('://') ||
    candidate.includes('/') ||
    candidate.includes(':') ||
    !/^(localhost|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*)$/i.test(candidate)
  ) {
    throw new Error('BFF_WEBAUTHN_RP_ID must be a hostname without a scheme, port, or path');
  }
  return candidate.toLowerCase();
}

function regionValue(value: string | undefined): string {
  const region = value?.trim() || 'local';
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(region)) {
    throw new Error('ARGUS_REGION must use 1-32 letters, digits, underscores, or hyphens');
  }
  return region;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const production = env.NODE_ENV === 'production';
  const mockUpstream = booleanValue(env.BFF_MOCK_UPSTREAM, false);
  const cookieSecure = booleanValue(env.BFF_COOKIE_SECURE, production);
  const sessionStore = sessionStoreValue(env.BFF_SESSION_STORE, production);
  const redisUrl = env.BFF_REDIS_URL?.trim() || undefined;
  const redisUsername = env.BFF_REDIS_USERNAME?.trim() || undefined;
  const redisPassword = env.BFF_REDIS_PASSWORD || undefined;
  const redisTlsCaFile = optionalPath(env.BFF_REDIS_TLS_CA_FILE);
  const redisTlsCertFile = optionalPath(env.BFF_REDIS_TLS_CERT_FILE);
  const redisTlsKeyFile = optionalPath(env.BFF_REDIS_TLS_KEY_FILE);
  const redisTlsServerName = env.BFF_REDIS_TLS_SERVER_NAME?.trim() || undefined;
  const encryption = encryptionConfiguration(env, production);
  const authBaseUrl = serviceBaseUrl(env.ARGUS_AUTH_URL, 'http://127.0.0.1:8081', 'ARGUS_AUTH_URL');
  const investigationBaseUrl = serviceBaseUrl(
    env.ARGUS_INVESTIGATION_URL,
    'http://127.0.0.1:8082',
    'ARGUS_INVESTIGATION_URL',
  );
  const authMtlsEnabled = booleanValue(env.BFF_AUTH_MTLS_ENABLED, false);
  const authTlsCaFile = optionalPath(env.BFF_AUTH_TLS_CA_FILE);
  const authTlsCertFile = optionalPath(env.BFF_AUTH_TLS_CERT_FILE);
  const authTlsKeyFile = optionalPath(env.BFF_AUTH_TLS_KEY_FILE);
  const authTlsServerName = env.BFF_AUTH_TLS_SERVER_NAME?.trim() || new URL(authBaseUrl).hostname;
  const oidcEnabled = booleanValue(env.BFF_OIDC_ENABLED, false);
  const oidcIssuer = absoluteUrl(env.BFF_OIDC_ISSUER?.trim(), 'BFF_OIDC_ISSUER');
  const oidcClientId = env.BFF_OIDC_CLIENT_ID?.trim() || undefined;
  const oidcClientSecret = env.BFF_OIDC_CLIENT_SECRET?.trim() || undefined;
  const oidcRedirectUri = absoluteUrl(env.BFF_OIDC_REDIRECT_URI?.trim(), 'BFF_OIDC_REDIRECT_URI');
  const passkeyEnabled = booleanValue(env.BFF_PASSKEY_ENABLED, true);
  const webauthnRpId = rpId(env.BFF_WEBAUTHN_RP_ID);
  const webauthnRpName = env.BFF_WEBAUTHN_RP_NAME?.trim() || 'Argus';
  const webauthnOrigin = origin(env.BFF_WEBAUTHN_ORIGIN, 'http://localhost:5173', 'BFF_WEBAUTHN_ORIGIN');
  const internalBffSecret = env.ARGUS_INTERNAL_BFF_SECRET?.trim()
    || 'argus-dev-internal-bff-secret-change-me';
  const metricsEnabled = booleanValue(env.BFF_METRICS_ENABLED, true);
  const metricsToken = env.BFF_METRICS_TOKEN?.trim() || undefined;
  if (production && mockUpstream) {
    throw new Error('BFF_MOCK_UPSTREAM cannot be enabled in production');
  }
  if (production && !cookieSecure) {
    throw new Error('BFF_COOKIE_SECURE must be true in production');
  }
  if (production && sessionStore !== 'redis') {
    throw new Error('BFF_SESSION_STORE must be redis in production');
  }
  if (sessionStore === 'redis' && !redisUrl) {
    throw new Error('BFF_REDIS_URL is required when BFF_SESSION_STORE=redis');
  }
  if (sessionStore === 'redis' && (!encryption.primaryKeyId || !encryption.keys)) {
    throw new Error('BFF_ENCRYPTION_KEYS is required when BFF_SESSION_STORE=redis');
  }
  if ((redisTlsCertFile && !redisTlsKeyFile) || (!redisTlsCertFile && redisTlsKeyFile)) {
    throw new Error('BFF_REDIS_TLS_CERT_FILE and BFF_REDIS_TLS_KEY_FILE must be configured together');
  }
  if (authMtlsEnabled && (!authTlsCaFile || !authTlsCertFile || !authTlsKeyFile)) {
    throw new Error('BFF auth mTLS requires CA, client certificate, and client key files');
  }
  if (authMtlsEnabled && new URL(authBaseUrl).protocol !== 'https:') {
    throw new Error('ARGUS_AUTH_URL must use https when BFF_AUTH_MTLS_ENABLED=true');
  }
  if (production && (!authMtlsEnabled || new URL(authBaseUrl).protocol !== 'https:')) {
    throw new Error('Production requires authenticated TLS for the auth service');
  }
  if (production && sessionStore === 'redis') {
    if (new URL(redisUrl!).protocol !== 'rediss:') {
      throw new Error('BFF_REDIS_URL must use rediss in production');
    }
    if (!redisPassword && !new URL(redisUrl!).password) {
      throw new Error('BFF_REDIS_PASSWORD is required for production Redis authentication');
    }
  }
  if (oidcEnabled && (!oidcIssuer || !oidcClientId || !oidcRedirectUri)) {
    throw new Error('BFF_OIDC_ISSUER, BFF_OIDC_CLIENT_ID, and BFF_OIDC_REDIRECT_URI are required when OIDC is enabled');
  }
  if (production && oidcEnabled) {
    const issuer = new URL(oidcIssuer!);
    const redirect = new URL(oidcRedirectUri!);
    if (issuer.protocol !== 'https:' || redirect.protocol !== 'https:') {
      throw new Error('OIDC issuer and redirect URI must use https in production');
    }
  }
  if (passkeyEnabled && (webauthnRpName.length > 80 || internalBffSecret.length < 32)) {
    throw new Error('BFF_WEBAUTHN_RP_NAME must be at most 80 characters and ARGUS_INTERNAL_BFF_SECRET at least 32');
  }
  const webauthnHost = new URL(webauthnOrigin).hostname.toLowerCase();
  if (passkeyEnabled && webauthnHost !== webauthnRpId && !webauthnHost.endsWith(`.${webauthnRpId}`)) {
    throw new Error('BFF_WEBAUTHN_RP_ID must equal or be a domain suffix of the WebAuthn origin hostname');
  }
  if (production && passkeyEnabled) {
    if (new URL(webauthnOrigin).protocol !== 'https:') {
      throw new Error('BFF_WEBAUTHN_ORIGIN must use https in production');
    }
    if (internalBffSecret === 'argus-dev-internal-bff-secret-change-me') {
      throw new Error('ARGUS_INTERNAL_BFF_SECRET must be changed in production');
    }
  }
  if (metricsToken && (metricsToken.length < 32 || metricsToken.length > 256)) {
    throw new Error('BFF_METRICS_TOKEN must be between 32 and 256 characters');
  }
  if (production && metricsEnabled && !metricsToken) {
    throw new Error('BFF_METRICS_TOKEN is required when production metrics are enabled');
  }
  const oidcScopes = (env.BFF_OIDC_SCOPES ?? 'openid profile email').trim();
  if (oidcEnabled && !oidcScopes.split(/\s+/).includes('openid')) {
    throw new Error('BFF_OIDC_SCOPES must include openid');
  }

  return {
    host: env.BFF_HOST ?? '127.0.0.1',
    port: positiveInteger(env.BFF_PORT, 3001, 'BFF_PORT'),
    authBaseUrl,
    investigationBaseUrl,
    authMtlsEnabled,
    ...(authTlsCaFile ? { authTlsCaFile } : {}),
    ...(authTlsCertFile ? { authTlsCertFile } : {}),
    ...(authTlsKeyFile ? { authTlsKeyFile } : {}),
    ...(authTlsServerName ? { authTlsServerName } : {}),
    allowedOrigins: originSet(env.BFF_ALLOWED_ORIGINS),
    requestTimeoutMs: positiveInteger(env.BFF_REQUEST_TIMEOUT_MS, 5_000, 'BFF_REQUEST_TIMEOUT_MS'),
    sessionTtlSeconds: positiveInteger(env.BFF_SESSION_TTL_SECONDS, 3_600, 'BFF_SESSION_TTL_SECONDS'),
    loginRateLimitMax: positiveInteger(env.BFF_LOGIN_RATE_LIMIT_MAX, 10, 'BFF_LOGIN_RATE_LIMIT_MAX'),
    loginRateLimitWindowMs: positiveInteger(env.BFF_LOGIN_RATE_LIMIT_WINDOW_MS, 60_000, 'BFF_LOGIN_RATE_LIMIT_WINDOW_MS'),
    cookieSecure,
    mockUpstream,
    sessionStore,
    ...(redisUrl ? { redisUrl } : {}),
    ...(redisUsername ? { redisUsername } : {}),
    ...(redisPassword ? { redisPassword } : {}),
    ...(redisTlsCaFile ? { redisTlsCaFile } : {}),
    ...(redisTlsCertFile ? { redisTlsCertFile } : {}),
    ...(redisTlsKeyFile ? { redisTlsKeyFile } : {}),
    ...(redisTlsServerName ? { redisTlsServerName } : {}),
    ...(encryption.primaryKeyId ? { encryptionPrimaryKeyId: encryption.primaryKeyId } : {}),
    ...(encryption.keys ? { encryptionKeys: encryption.keys } : {}),
    redisConnectTimeoutMs: positiveInteger(env.BFF_REDIS_CONNECT_TIMEOUT_MS, 1_000, 'BFF_REDIS_CONNECT_TIMEOUT_MS'),
    oidcEnabled,
    ...(oidcIssuer ? { oidcIssuer } : {}),
    ...(oidcClientId ? { oidcClientId } : {}),
    ...(oidcClientSecret ? { oidcClientSecret } : {}),
    ...(oidcRedirectUri ? { oidcRedirectUri } : {}),
    oidcScopes,
    oidcSuccessRedirect: localRedirect(env.BFF_OIDC_SUCCESS_REDIRECT, '/?auth=oidc_success', 'BFF_OIDC_SUCCESS_REDIRECT'),
    oidcErrorRedirect: localRedirect(env.BFF_OIDC_ERROR_REDIRECT, '/?auth=oidc_error', 'BFF_OIDC_ERROR_REDIRECT'),
    oidcTransactionTtlSeconds: positiveInteger(
      env.BFF_OIDC_TRANSACTION_TTL_SECONDS,
      300,
      'BFF_OIDC_TRANSACTION_TTL_SECONDS',
    ),
    mfaChallengeTtlSeconds: positiveInteger(
      env.BFF_MFA_CHALLENGE_TTL_SECONDS,
      300,
      'BFF_MFA_CHALLENGE_TTL_SECONDS',
    ),
    mfaRequiredRedirect: localRedirect(
      env.BFF_MFA_REQUIRED_REDIRECT,
      '/?auth=mfa_required',
      'BFF_MFA_REQUIRED_REDIRECT',
    ),
    passkeyEnabled,
    webauthnRpId,
    webauthnRpName,
    webauthnOrigin,
    webauthnCeremonyTtlSeconds: positiveInteger(
      env.BFF_WEBAUTHN_CEREMONY_TTL_SECONDS,
      300,
      'BFF_WEBAUTHN_CEREMONY_TTL_SECONDS',
    ),
    internalBffSecret,
    region: regionValue(env.ARGUS_REGION),
    metricsEnabled,
    ...(metricsToken ? { metricsToken } : {}),
    logger: booleanValue(env.BFF_LOGGER, production),
  };
}
