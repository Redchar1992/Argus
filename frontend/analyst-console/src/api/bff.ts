import type { AuthSession } from '../auth/authMachine';

interface ErrorEnvelope {
  error?: {
    code?: string;
    message?: string;
  };
  requestId?: string;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

function readCookie(name: string): string | undefined {
  const prefix = `${encodeURIComponent(name)}=`;
  return document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
    ?.slice(prefix.length);
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

interface RequestOptions extends RequestInit {
  csrf?: boolean;
  notifySessionExpiry?: boolean;
}

export async function bffRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { csrf = false, notifySessionExpiry = false, headers, ...init } = options;
  const requestHeaders = new Headers(headers);
  if (csrf) {
    const csrfToken = readCookie('argus_csrf');
    if (!csrfToken) throw new ApiError(403, 'CSRF_MISSING', 'Security token is missing. Refresh and try again.');
    requestHeaders.set('x-csrf-token', csrfToken);
  }

  const response = await fetch(path, {
    ...init,
    headers: requestHeaders,
    credentials: 'same-origin',
  });
  const body = await parseBody(response);
  if (!response.ok) {
    const envelope = (body ?? {}) as ErrorEnvelope;
    const error = new ApiError(
      response.status,
      envelope.error?.code ?? 'REQUEST_FAILED',
      envelope.error?.message ?? `Request failed (${response.status})`,
      envelope.requestId,
    );
    if (notifySessionExpiry && response.status === 401) {
      window.dispatchEvent(new CustomEvent('argus:session-expired', { detail: error.message }));
    }
    throw error;
  }
  return body as T;
}

async function ensureCsrfCookie(): Promise<void> {
  if (readCookie('argus_csrf')) return;
  try {
    await bffRequest<AuthSession>('/bff/auth/session');
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
  }
  if (!readCookie('argus_csrf')) {
    throw new ApiError(403, 'CSRF_MISSING', 'Security token could not be initialized. Refresh and try again.');
  }
}

export async function getSession(): Promise<AuthSession> {
  const response = await bffRequest<{ state: 'authenticated'; user: AuthSession['user']; expiresAt: string }>(
    '/bff/auth/session',
  );
  return { user: response.user, expiresAt: response.expiresAt };
}

export async function login(username: string, password: string): Promise<AuthSession> {
  await ensureCsrfCookie();
  const response = await bffRequest<{ state: 'authenticated'; user: AuthSession['user']; expiresAt: string }>(
    '/bff/auth/login',
    {
      method: 'POST',
      csrf: true,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, password }),
    },
  );
  return { user: response.user, expiresAt: response.expiresAt };
}

export async function logout(): Promise<void> {
  await ensureCsrfCookie();
  await bffRequest<void>('/bff/auth/logout', { method: 'POST', csrf: true });
}
