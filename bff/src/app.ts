import { randomBytes, timingSafeEqual } from 'node:crypto';
import cookie from '@fastify/cookie';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import type { AppConfig } from './config.js';
import { AppError, UpstreamError } from './errors.js';
import { SessionStore, type ServerSession } from './session-store.js';
import { HttpUpstreamClient, MockUpstreamClient, type UpstreamClient } from './upstream.js';

const SESSION_COOKIE = 'argus_session';
const CSRF_COOKIE = 'argus_csrf';
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

interface LoginBody {
  username?: unknown;
  password?: unknown;
}

interface InvestigationParams {
  id: string;
}

export interface AppDependencies {
  upstream?: UpstreamClient;
  sessions?: SessionStore;
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
  const requestSessions = new WeakMap<FastifyRequest, ServerSession>();

  await app.register(cookie);
  await app.register(helmet);
  await app.register(rateLimit, { global: false });

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

  function currentSession(request: FastifyRequest): ServerSession | undefined {
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
    const session = currentSession(request);
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
        sessions.delete(session.id);
        clearSessionCookie(reply);
        throw new AppError(401, 'SESSION_EXPIRED', 'Your session expired. Sign in again.');
      }
      throw error;
    }
  }

  app.get('/health', async () => ({ status: 'ok' }));

  app.get('/bff/auth/session', async (request, reply) => {
    ensureCsrf(request, reply);
    const session = currentSession(request);
    if (!session) {
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
      sessions.delete(previousId);
      clearSessionCookie(reply);

      const result = await upstream.login(username, password, request.id);
      const session = sessions.create(
        result.token,
        { username: result.username, role: result.role },
        result.expiresInSeconds,
      );
      setSessionCookie(reply, session);
      ensureCsrf(request, reply, true);
      return { state: 'authenticated', user: session.user, expiresAt: new Date(session.expiresAt).toISOString() };
    },
  );

  app.post('/bff/auth/logout', { preHandler: verifyCsrf }, async (request, reply) => {
    sessions.delete(request.cookies[SESSION_COOKIE]);
    clearSessionCookie(reply);
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
