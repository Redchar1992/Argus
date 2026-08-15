import { randomBytes, timingSafeEqual } from 'node:crypto';
import cookie from '@fastify/cookie';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import type { AppConfig } from './config.js';
import { AppError, UpstreamError } from './errors.js';
import {
  MemoryMfaChallengeStore,
  type MfaChallengeRepository,
  type MfaMethod,
} from './mfa-challenge-store.js';
import type { OidcRelyingParty } from './oidc.js';
import {
  MemoryOidcTransactionStore,
  type OidcTransactionRepository,
} from './oidc-transaction-store.js';
import { SessionStore, type ServerSession, type SessionRepository } from './session-store.js';
import {
  HttpUpstreamClient,
  isMfaChallenge,
  MockUpstreamClient,
  type AuthenticationResult,
  type LoginResult,
  type UpstreamClient,
} from './upstream.js';

const SESSION_COOKIE = 'argus_session';
const CSRF_COOKIE = 'argus_csrf';
const OIDC_TRANSACTION_COOKIE = 'argus_oidc_tx';
const MFA_CHALLENGE_COOKIE = 'argus_mfa_tx';
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

interface LoginBody {
  username?: unknown;
  password?: unknown;
}

interface MfaVerifyBody {
  method?: unknown;
  code?: unknown;
}

interface TotpCodeBody {
  code?: unknown;
}

interface InvestigationParams {
  id: string;
}

export interface AppDependencies {
  upstream?: UpstreamClient;
  sessions?: SessionRepository;
  rateLimitRedis?: unknown;
  oidc?: OidcRelyingParty;
  oidcTransactions?: OidcTransactionRepository;
  mfaChallenges?: MfaChallengeRepository;
  close?: () => Promise<void>;
}

function token(): string {
  return randomBytes(32).toString('base64url');
}

function constantTimeEqual(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

export async function buildApp(config: AppConfig, dependencies: AppDependencies = {}): Promise<FastifyInstance> {
  const app = Fastify({ logger: config.logger, bodyLimit: 32 * 1024 });
  const sessions = dependencies.sessions ?? new SessionStore(config.sessionTtlSeconds);
  const upstream =
    dependencies.upstream ?? (config.mockUpstream ? new MockUpstreamClient() : new HttpUpstreamClient(config));
  const oidc = dependencies.oidc;
  const oidcTransactions = dependencies.oidcTransactions ?? new MemoryOidcTransactionStore();
  const mfaChallenges = dependencies.mfaChallenges ?? new MemoryMfaChallengeStore();
  if (config.oidcEnabled && !oidc) throw new Error('OIDC is enabled but no relying party was configured');
  const requestSessions = new WeakMap<FastifyRequest, ServerSession>();

  await app.register(cookie);
  await app.register(helmet);
  await app.register(rateLimit, {
    global: false,
    ...(dependencies.rateLimitRedis ? { redis: dependencies.rateLimitRedis } : {}),
    nameSpace: 'argus:bff:login-rate:',
    // Authentication must fail closed when the shared limiter is unavailable.
    skipOnError: false,
  });

  if (dependencies.close) {
    app.addHook('onClose', dependencies.close);
  }

  app.addHook('onSend', async (request, reply, payload) => {
    if (request.url.startsWith('/bff/')) {
      reply.header('cache-control', 'no-store');
      reply.header('pragma', 'no-cache');
    }
    return payload;
  });

  function setCsrfCookie(reply: FastifyReply, value: string): void {
    reply.setCookie(CSRF_COOKIE, value, {
      httpOnly: false,
      secure: config.cookieSecure,
      sameSite: 'strict',
      path: '/',
      maxAge: config.sessionTtlSeconds,
    });
  }

  function ensureCsrf(request: FastifyRequest, reply: FastifyReply, rotate = false): string {
    const existing = request.cookies[CSRF_COOKIE];
    if (!rotate && existing && TOKEN_PATTERN.test(existing)) return existing;
    const value = token();
    setCsrfCookie(reply, value);
    return value;
  }

  function setSessionCookie(reply: FastifyReply, session: ServerSession): void {
    reply.setCookie(SESSION_COOKIE, session.id, {
      httpOnly: true,
      secure: config.cookieSecure,
      sameSite: 'strict',
      path: '/bff',
      maxAge: Math.max(1, Math.floor((session.expiresAt - Date.now()) / 1_000)),
    });
  }

  function clearSessionCookie(reply: FastifyReply): void {
    reply.clearCookie(SESSION_COOKIE, {
      httpOnly: true,
      secure: config.cookieSecure,
      sameSite: 'strict',
      path: '/bff',
    });
  }

  function setOidcTransactionCookie(reply: FastifyReply, id: string): void {
    reply.setCookie(OIDC_TRANSACTION_COOKIE, id, {
      httpOnly: true,
      secure: config.cookieSecure,
      sameSite: 'lax',
      path: '/bff/auth/oidc/callback',
      maxAge: config.oidcTransactionTtlSeconds,
    });
  }

  function clearOidcTransactionCookie(reply: FastifyReply): void {
    reply.clearCookie(OIDC_TRANSACTION_COOKIE, {
      httpOnly: true,
      secure: config.cookieSecure,
      sameSite: 'lax',
      path: '/bff/auth/oidc/callback',
    });
  }

  function setMfaChallengeCookie(reply: FastifyReply, id: string, maxAge: number): void {
    reply.setCookie(MFA_CHALLENGE_COOKIE, id, {
      httpOnly: true,
      secure: config.cookieSecure,
      sameSite: 'strict',
      path: '/bff/auth',
      maxAge,
    });
  }

  function clearMfaChallengeCookie(reply: FastifyReply): void {
    reply.clearCookie(MFA_CHALLENGE_COOKIE, {
      httpOnly: true,
      secure: config.cookieSecure,
      sameSite: 'strict',
      path: '/bff/auth',
    });
  }

  async function currentSession(request: FastifyRequest): Promise<ServerSession | undefined> {
    return sessions.get(request.cookies[SESSION_COOKIE]);
  }

  async function verifyCsrf(request: FastifyRequest): Promise<void> {
    const origin = request.headers.origin?.replace(/\/$/, '');
    const fetchSite = request.headers['sec-fetch-site'];
    if (!origin || !config.allowedOrigins.has(origin) || (fetchSite && fetchSite !== 'same-origin')) {
      throw new AppError(403, 'FORBIDDEN_ORIGIN', 'The request origin is not allowed');
    }

    const cookieToken = request.cookies[CSRF_COOKIE];
    const header = request.headers['x-csrf-token'];
    const headerToken = Array.isArray(header) ? header[0] : header;
    if (
      !cookieToken ||
      !headerToken ||
      !TOKEN_PATTERN.test(cookieToken) ||
      !TOKEN_PATTERN.test(headerToken) ||
      !constantTimeEqual(cookieToken, headerToken)
    ) {
      throw new AppError(403, 'CSRF_INVALID', 'The CSRF token is missing or invalid');
    }
  }

  async function requireSession(request: FastifyRequest, reply: FastifyReply): Promise<void> {
    const session = await currentSession(request);
    if (!session) {
      const hadCookie = Boolean(request.cookies[SESSION_COOKIE]);
      clearSessionCookie(reply);
      throw new AppError(
        401,
        hadCookie ? 'SESSION_EXPIRED' : 'UNAUTHENTICATED',
        hadCookie ? 'Your session expired. Sign in again.' : 'Sign in to continue.',
      );
    }
    requestSessions.set(request, session);
  }

  async function proxy<T>(
    request: FastifyRequest,
    reply: FastifyReply,
    action: (session: ServerSession) => Promise<T>,
  ): Promise<T> {
    const session = requestSessions.get(request);
    if (!session) throw new AppError(500, 'INTERNAL_ERROR', 'Session guard was not applied');
    try {
      return await action(session);
    } catch (error) {
      if (error instanceof UpstreamError && error.status === 401) {
        await sessions.delete(session.id);
        clearSessionCookie(reply);
        throw new AppError(401, 'SESSION_EXPIRED', 'Your session expired. Sign in again.');
      }
      throw error;
    }
  }

  async function beginMfaChallenge(
    request: FastifyRequest,
    reply: FastifyReply,
    result: Extract<AuthenticationResult, { state: 'mfa_required' }>,
  ): Promise<{ state: 'mfa_required'; methods: MfaMethod[]; username: string; expiresAt: string }> {
    const previous = request.cookies[MFA_CHALLENGE_COOKIE];
    await mfaChallenges.delete(previous);
    clearMfaChallengeCookie(reply);
    const ttlSeconds = Math.min(config.mfaChallengeTtlSeconds, result.expiresInSeconds);
    const expiresAt = Date.now() + ttlSeconds * 1_000;
    const id = await mfaChallenges.create({
      challengeToken: result.challengeToken,
      methods: result.methods,
      username: result.username,
      expiresAt,
    });
    setMfaChallengeCookie(reply, id, ttlSeconds);
    return { state: 'mfa_required', methods: result.methods, username: result.username,
      expiresAt: new Date(expiresAt).toISOString() };
  }

  async function establishSession(
    request: FastifyRequest,
    reply: FastifyReply,
    result: LoginResult,
  ): Promise<{ state: 'authenticated'; user: ServerSession['user']; expiresAt: string }> {
    await sessions.delete(request.cookies[SESSION_COOKIE]);
    clearSessionCookie(reply);
    await mfaChallenges.delete(request.cookies[MFA_CHALLENGE_COOKIE]);
    clearMfaChallengeCookie(reply);
    const session = await sessions.create(
      result.token,
      { username: result.username, role: result.role },
      result.expiresInSeconds,
    );
    setSessionCookie(reply, session);
    ensureCsrf(request, reply, true);
    return { state: 'authenticated', user: session.user, expiresAt: new Date(session.expiresAt).toISOString() };
  }

  app.get('/health', async () => ({
    status: 'ok',
    sessionStore: config.sessionStore,
    oidc: config.oidcEnabled ? 'enabled' : 'disabled',
  }));

  if (config.oidcEnabled && oidc) {
    app.get('/bff/auth/oidc/start', async (_request, reply) => {
      const authorization = await oidc.begin();
      const transactionId = await oidcTransactions.create({
        state: authorization.state,
        nonce: authorization.nonce,
        codeVerifier: authorization.codeVerifier,
        expiresAt: authorization.expiresAt,
      });
      setOidcTransactionCookie(reply, transactionId);
      return reply.code(302).redirect(authorization.redirectUrl);
    });

    app.get('/bff/auth/oidc/callback', async (request, reply) => {
      const transactionId = request.cookies[OIDC_TRANSACTION_COOKIE];
      clearOidcTransactionCookie(reply);
      const transaction = await oidcTransactions.consume(transactionId);
      if (!transaction) return reply.code(302).redirect(config.oidcErrorRedirect);

      try {
        const callbackUrl = new URL(request.url, config.oidcRedirectUri);
        const result = await oidc.complete(callbackUrl, transaction);
        const login = await upstream.oidcLogin(result.idToken, transaction.nonce, request.id);
        if (isMfaChallenge(login)) {
          await sessions.delete(request.cookies[SESSION_COOKIE]);
          clearSessionCookie(reply);
          await beginMfaChallenge(request, reply, login);
          return reply.code(302).redirect(config.mfaRequiredRedirect);
        }
        await establishSession(request, reply, login);
        return reply.code(302).redirect(config.oidcSuccessRedirect);
      } catch {
        return reply.code(302).redirect(config.oidcErrorRedirect);
      }
    });
  }

  app.get('/bff/auth/session', async (request, reply) => {
    ensureCsrf(request, reply);
    const session = await currentSession(request);
    if (!session) {
      const challenge = await mfaChallenges.get(request.cookies[MFA_CHALLENGE_COOKIE]);
      if (challenge) {
        return {
          state: 'mfa_required',
          methods: challenge.methods,
          username: challenge.username,
          expiresAt: new Date(challenge.expiresAt).toISOString(),
        };
      }
      const hadCookie = Boolean(request.cookies[SESSION_COOKIE]);
      clearSessionCookie(reply);
      throw new AppError(
        401,
        hadCookie ? 'SESSION_EXPIRED' : 'UNAUTHENTICATED',
        hadCookie ? 'Your session expired. Sign in again.' : 'Sign in to continue.',
      );
    }
    return { state: 'authenticated', user: session.user, expiresAt: new Date(session.expiresAt).toISOString() };
  });

  app.post<{ Body: LoginBody }>(
    '/bff/auth/login',
    {
      preHandler: verifyCsrf,
      config: { rateLimit: { max: config.loginRateLimitMax, timeWindow: config.loginRateLimitWindowMs } },
    },
    async (request, reply) => {
      const username = typeof request.body?.username === 'string' ? request.body.username.trim() : '';
      const password = typeof request.body?.password === 'string' ? request.body.password : '';
      if (!username || username.length > 64 || !password || password.length > 128) {
        throw new AppError(400, 'BAD_REQUEST', 'Enter a valid username and password');
      }

      const previousId = request.cookies[SESSION_COOKIE];
      await sessions.delete(previousId);
      clearSessionCookie(reply);

      const result = await upstream.login(username, password, request.id);
      if (isMfaChallenge(result)) return beginMfaChallenge(request, reply, result);
      return establishSession(request, reply, result);
    },
  );

  app.get('/bff/auth/mfa/challenge', async (request, reply) => {
    ensureCsrf(request, reply);
    const challenge = await mfaChallenges.get(request.cookies[MFA_CHALLENGE_COOKIE]);
    if (!challenge) {
      clearMfaChallengeCookie(reply);
      throw new AppError(401, 'MFA_CHALLENGE_EXPIRED', 'The verification challenge expired. Sign in again.');
    }
    return {
      state: 'mfa_required',
      methods: challenge.methods,
      username: challenge.username,
      expiresAt: new Date(challenge.expiresAt).toISOString(),
    };
  });

  app.post<{ Body: MfaVerifyBody }>(
    '/bff/auth/mfa/verify',
    {
      preHandler: verifyCsrf,
      config: { rateLimit: { max: config.loginRateLimitMax, timeWindow: config.loginRateLimitWindowMs } },
    },
    async (request, reply) => {
      const method = request.body?.method;
      const code = typeof request.body?.code === 'string' ? request.body.code.trim().toUpperCase() : '';
      if ((method !== 'TOTP' && method !== 'RECOVERY_CODE') || !/^[A-Z0-9-]{6,32}$/.test(code)) {
        throw new AppError(400, 'BAD_REQUEST', 'Enter a valid verification code');
      }
      const challengeId = request.cookies[MFA_CHALLENGE_COOKIE];
      const challenge = await mfaChallenges.get(challengeId);
      if (!challenge || !challenge.methods.includes(method)) {
        await mfaChallenges.delete(challengeId);
        clearMfaChallengeCookie(reply);
        throw new AppError(401, 'MFA_CHALLENGE_EXPIRED', 'The verification challenge expired. Sign in again.');
      }
      try {
        const result = await upstream.verifyMfa(challenge.challengeToken, method, code, request.id);
        await mfaChallenges.delete(challengeId);
        return establishSession(request, reply, result);
      } catch (error) {
        if (error instanceof UpstreamError && error.status === 401) {
          throw new AppError(401, 'INVALID_MFA_CODE', 'The verification code is invalid or expired');
        }
        throw error;
      }
    },
  );

  app.get('/bff/auth/mfa', { preHandler: requireSession }, async (request, reply) =>
    proxy(request, reply, (session) => upstream.mfaStatus(session.accessToken, request.id)));

  app.post('/bff/auth/mfa/totp/setup', { preHandler: [verifyCsrf, requireSession] }, async (request, reply) =>
    proxy(request, reply, (session) => upstream.setupTotp(session.accessToken, request.id)));

  app.post<{ Body: TotpCodeBody }>(
    '/bff/auth/mfa/totp/confirm',
    { preHandler: [verifyCsrf, requireSession] },
    async (request, reply) => {
      const code = typeof request.body?.code === 'string' ? request.body.code.trim() : '';
      if (!/^\d{6}$/.test(code)) throw new AppError(400, 'BAD_REQUEST', 'Enter a six-digit code');
      return proxy(request, reply, (session) => upstream.confirmTotp(session.accessToken, code, request.id));
    },
  );

  app.post<{ Body: TotpCodeBody }>(
    '/bff/auth/mfa/totp/disable',
    { preHandler: [verifyCsrf, requireSession] },
    async (request, reply) => {
      const code = typeof request.body?.code === 'string' ? request.body.code.trim() : '';
      if (!/^\d{6}$/.test(code)) throw new AppError(400, 'BAD_REQUEST', 'Enter a six-digit code');
      return proxy(request, reply, (session) => upstream.disableTotp(session.accessToken, code, request.id));
    },
  );

  app.post('/bff/auth/logout', { preHandler: verifyCsrf }, async (request, reply) => {
    await sessions.delete(request.cookies[SESSION_COOKIE]);
    await mfaChallenges.delete(request.cookies[MFA_CHALLENGE_COOKIE]);
    clearSessionCookie(reply);
    clearMfaChallengeCookie(reply);
    ensureCsrf(request, reply, true);
    return reply.code(204).send();
  });

  app.post(
    '/bff/api/investigations',
    { preHandler: [verifyCsrf, requireSession] },
    async (request, reply) => proxy(request, reply, (session) => upstream.submitInvestigation(session.accessToken, request.body, request.id)),
  );

  app.get<{ Params: InvestigationParams }>(
    '/bff/api/investigations/:id',
    { preHandler: requireSession },
    async (request, reply) =>
      proxy(request, reply, (session) => upstream.getInvestigation(session.accessToken, request.params.id, request.id)),
  );

  app.setNotFoundHandler(async () => {
    throw new AppError(404, 'UPSTREAM_REJECTED', 'Route not found');
  });

  app.setErrorHandler(async (error, request, reply) => {
    if (error instanceof AppError) {
      return reply.code(error.status).send({ error: { code: error.code, message: error.message }, requestId: request.id });
    }
    if (error instanceof UpstreamError) {
      if (error.kind === 'timeout') {
        return reply.code(504).send({
          error: { code: 'UPSTREAM_TIMEOUT', message: 'A required service timed out. Try again.' },
          requestId: request.id,
        });
      }
      if (error.status === 401) {
        return reply.code(401).send({
          error: { code: 'INVALID_CREDENTIALS', message: 'Invalid username or password' },
          requestId: request.id,
        });
      }
      if (error.kind === 'unavailable' || error.status >= 500) {
        return reply.code(502).send({
          error: { code: 'UPSTREAM_UNAVAILABLE', message: 'A required service is unavailable. Try again.' },
          requestId: request.id,
        });
      }
      return reply.code(error.status).send({
        error: { code: 'UPSTREAM_REJECTED', message: 'The request could not be completed' },
        requestId: request.id,
      });
    }

    if (typeof error === 'object' && error !== null && 'statusCode' in error && error.statusCode === 429) {
      return reply.code(429).send({
        error: { code: 'RATE_LIMITED', message: 'Too many sign-in attempts. Try again later.' },
        requestId: request.id,
      });
    }

    if (typeof error === 'object' && error !== null && 'statusCode' in error && error.statusCode === 400) {
      return reply.code(400).send({
        error: { code: 'BAD_REQUEST', message: 'The request body is invalid' },
        requestId: request.id,
      });
    }

    request.log.error({ err: error }, 'Unhandled BFF error');
    return reply.code(500).send({
      error: { code: 'INTERNAL_ERROR', message: 'An unexpected error occurred' },
      requestId: request.id,
    });
  });

  await app.ready();
  return app;
}
