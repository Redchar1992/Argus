import { randomUUID } from 'node:crypto';
import type { AppConfig } from './config.js';
import { UpstreamError } from './errors.js';
import type { AuthUser, Role } from './session-store.js';
import type { MfaMethod } from './mfa-challenge-store.js';

export interface LoginResult extends AuthUser {
  token: string;
  expiresInSeconds: number;
}

export interface MfaChallengeResult {
  state: 'mfa_required';
  challengeToken: string;
  methods: MfaMethod[];
  expiresInSeconds: number;
  username: string;
}

export type AuthenticationResult = LoginResult | MfaChallengeResult;

export function isMfaChallenge(result: AuthenticationResult): result is MfaChallengeResult {
  return 'state' in result && result.state === 'mfa_required';
}

export interface UpstreamClient {
  login(username: string, password: string, requestId: string): Promise<AuthenticationResult>;
  oidcLogin(idToken: string, nonce: string, requestId: string): Promise<AuthenticationResult>;
  verifyMfa(challengeToken: string, method: MfaMethod, code: string, requestId: string): Promise<LoginResult>;
  mfaStatus(accessToken: string, requestId: string): Promise<unknown>;
  setupTotp(accessToken: string, requestId: string): Promise<unknown>;
  confirmTotp(accessToken: string, code: string, requestId: string): Promise<unknown>;
  disableTotp(accessToken: string, code: string, requestId: string): Promise<unknown>;
  recoveryStatus(accessToken: string, requestId: string): Promise<unknown>;
  regenerateRecoveryCodes(accessToken: string, totpCode: string, requestId: string): Promise<unknown>;
  recoverAccount(username: string, recoveryCode: string, newPassword: string, requestId: string): Promise<unknown>;
  submitInvestigation(accessToken: string, body: unknown, requestId: string): Promise<unknown>;
  getInvestigation(accessToken: string, id: string, requestId: string): Promise<unknown>;
}

async function parseResponse(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

export class HttpUpstreamClient implements UpstreamClient {
  constructor(private readonly config: AppConfig) {}

  async login(username: string, password: string, requestId: string): Promise<AuthenticationResult> {
    const data = await this.request(
      `${this.config.authBaseUrl}/api/auth/login`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-request-id': requestId },
        body: JSON.stringify({ username, password }),
      },
      'login',
    );
    return this.parseAuthenticationResult(data);
  }

  async oidcLogin(idToken: string, nonce: string, requestId: string): Promise<AuthenticationResult> {
    const data = await this.request(
      `${this.config.authBaseUrl}/api/auth/oidc/login`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-request-id': requestId },
        body: JSON.stringify({ idToken, nonce }),
      },
      'login',
    );
    return this.parseAuthenticationResult(data);
  }

  async verifyMfa(challengeToken: string, method: MfaMethod, code: string, requestId: string): Promise<LoginResult> {
    const data = await this.request(
      `${this.config.authBaseUrl}/api/auth/mfa/verify`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-request-id': requestId },
        body: JSON.stringify({ challengeToken, method, code }),
      },
      'login',
    );
    const result = this.parseAuthenticationResult(data);
    if (isMfaChallenge(result)) {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned a nested MFA challenge');
    }
    return result;
  }

  async mfaStatus(accessToken: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/mfa`, {
      method: 'GET', headers: this.authHeaders(accessToken, requestId, false),
    }, 'api');
  }

  async setupTotp(accessToken: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/mfa/totp/setup`, {
      method: 'POST', headers: this.authHeaders(accessToken, requestId, true), body: '{}',
    }, 'api');
  }

  async confirmTotp(accessToken: string, code: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/mfa/totp/confirm`, {
      method: 'POST', headers: this.authHeaders(accessToken, requestId, true), body: JSON.stringify({ code }),
    }, 'api');
  }

  async disableTotp(accessToken: string, code: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/mfa/totp/disable`, {
      method: 'POST', headers: this.authHeaders(accessToken, requestId, true), body: JSON.stringify({ code }),
    }, 'api');
  }

  async recoveryStatus(accessToken: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/recovery`, {
      method: 'GET', headers: this.authHeaders(accessToken, requestId, false),
    }, 'api');
  }

  async regenerateRecoveryCodes(accessToken: string, totpCode: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/recovery/codes/regenerate`, {
      method: 'POST', headers: this.authHeaders(accessToken, requestId, true), body: JSON.stringify({ code: totpCode }),
    }, 'api');
  }

  async recoverAccount(username: string, recoveryCode: string, newPassword: string, requestId: string): Promise<unknown> {
    return this.request(`${this.config.authBaseUrl}/api/auth/recovery/complete`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-request-id': requestId },
      body: JSON.stringify({ username, recoveryCode, newPassword }),
    }, 'login');
  }

  private parseAuthenticationResult(data: unknown): AuthenticationResult {
    if (!data || typeof data !== 'object') {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned an invalid response');
    }
    const candidate = data as Record<string, unknown>;
    if (candidate.state === 'mfa_required') {
      const methods = candidate.methods;
      if (
        typeof candidate.challengeToken !== 'string' ||
        candidate.challengeToken.length < 32 ||
        candidate.challengeToken.length > 256 ||
        !Array.isArray(methods) ||
        methods.length === 0 ||
        !methods.every((method) => method === 'TOTP' || method === 'RECOVERY_CODE') ||
        typeof candidate.expiresInSeconds !== 'number' ||
        !Number.isFinite(candidate.expiresInSeconds) ||
        candidate.expiresInSeconds <= 0 ||
        typeof candidate.username !== 'string'
      ) {
        throw new UpstreamError(502, 'unavailable', 'Authentication service returned an invalid MFA challenge');
      }
      return {
        state: 'mfa_required',
        challengeToken: candidate.challengeToken,
        methods: methods as MfaMethod[],
        expiresInSeconds: candidate.expiresInSeconds,
        username: candidate.username,
      };
    }
    const role = candidate.role;
    if (
      typeof candidate.token !== 'string' ||
      typeof candidate.expiresInSeconds !== 'number' ||
      !Number.isFinite(candidate.expiresInSeconds) ||
      candidate.expiresInSeconds <= 0 ||
      typeof candidate.username !== 'string' ||
      (role !== 'ANALYST' && role !== 'ADMIN')
    ) {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned an invalid response');
    }
    return {
      token: candidate.token,
      expiresInSeconds: candidate.expiresInSeconds,
      username: candidate.username,
      role,
    };
  }

  async submitInvestigation(accessToken: string, body: unknown, requestId: string): Promise<unknown> {
    return this.request(
      `${this.config.investigationBaseUrl}/api/investigations`,
      {
        method: 'POST',
        headers: this.authHeaders(accessToken, requestId, true),
        body: JSON.stringify(body),
      },
      'api',
    );
  }

  async getInvestigation(accessToken: string, id: string, requestId: string): Promise<unknown> {
    return this.request(
      `${this.config.investigationBaseUrl}/api/investigations/${encodeURIComponent(id)}`,
      { method: 'GET', headers: this.authHeaders(accessToken, requestId, false) },
      'api',
    );
  }

  private authHeaders(accessToken: string, requestId: string, json: boolean): Record<string, string> {
    return {
      authorization: `Bearer ${accessToken}`,
      accept: 'application/json',
      ...(json ? { 'content-type': 'application/json' } : {}),
      'x-request-id': requestId,
    };
  }

  private async request(url: string, init: RequestInit, purpose: 'login' | 'api'): Promise<unknown> {
    let response: Response;
    try {
      response = await fetch(url, {
        ...init,
        signal: AbortSignal.timeout(this.config.requestTimeoutMs),
      });
    } catch (error) {
      if (error instanceof Error && (error.name === 'AbortError' || error.name === 'TimeoutError')) {
        throw new UpstreamError(504, 'timeout', 'The upstream service timed out');
      }
      throw new UpstreamError(502, 'unavailable', 'The upstream service is unavailable');
    }

    const data = await parseResponse(response);
    if (!response.ok) {
      if (purpose === 'login' && response.status === 401) {
        throw new UpstreamError(401, 'rejected', 'Invalid username or password');
      }
      throw new UpstreamError(response.status, 'rejected', 'The upstream service rejected the request');
    }
    return data;
  }
}

/** Deterministic upstream used only by Playwright/local UI demonstrations. */
export class MockUpstreamClient implements UpstreamClient {
  private readonly investigations = new Map<string, Record<string, unknown>>();

  async login(username: string, password: string): Promise<LoginResult> {
    const credentials: Record<string, { password: string; role: Role }> = {
      analyst: { password: 'analyst12345', role: 'ANALYST' },
      admin: { password: 'admin12345', role: 'ADMIN' },
    };
    const user = credentials[username];
    if (!user || user.password !== password) {
      throw new UpstreamError(401, 'rejected', 'Invalid username or password');
    }
    return {
      token: `mock-only-${randomUUID()}`,
      expiresInSeconds: 3_600,
      username,
      role: user.role,
    };
  }

  async oidcLogin(idToken: string, nonce: string): Promise<LoginResult> {
    if (idToken !== 'mock-oidc-id-token' || !nonce) {
      throw new UpstreamError(401, 'rejected', 'Invalid OIDC identity');
    }
    return {
      token: `mock-only-${randomUUID()}`,
      expiresInSeconds: 3_600,
      username: 'oidc.analyst',
      role: 'ANALYST',
    };
  }

  async verifyMfa(): Promise<LoginResult> {
    throw new UpstreamError(401, 'rejected', 'No mock MFA challenge');
  }

  async mfaStatus(): Promise<unknown> {
    return { enabled: false, enrolledAt: null };
  }

  async setupTotp(): Promise<unknown> {
    return { secret: 'MOCKONLY', provisioningUri: 'otpauth://totp/mock', expiresAt: new Date(0).toISOString() };
  }

  async confirmTotp(): Promise<unknown> {
    return { enabled: true, enrolledAt: new Date(0).toISOString() };
  }

  async disableTotp(): Promise<unknown> {
    return { enabled: false, enrolledAt: null };
  }

  async recoveryStatus(): Promise<unknown> {
    return { remaining: 0 };
  }

  async regenerateRecoveryCodes(): Promise<unknown> {
    return { recoveryCodes: [], remaining: 0, generatedAt: new Date(0).toISOString() };
  }

  async recoverAccount(): Promise<unknown> {
    return { state: 'recovered', message: 'Password reset. Sign in with your new password.' };
  }

  async submitInvestigation(_accessToken: string, body: unknown): Promise<unknown> {
    const address =
      body && typeof body === 'object' && typeof (body as Record<string, unknown>).address === 'string'
        ? ((body as Record<string, unknown>).address as string)
        : '';
    if (!address) throw new UpstreamError(400, 'rejected', 'A wallet address is required');
    const id = `e2e_${randomUUID()}`;
    this.investigations.set(id, {
      id,
      subjectAddress: address,
      status: 'COMPLETED',
      llmProvider: 'playwright-mock',
      maxSteps: 4,
      decision: 'CLEAR',
      riskScore: 0,
      riskBand: 'MINIMAL',
      riskFactors: [],
      summary: 'Deterministic browser-test result.',
      error: null,
      requestedBy: 'analyst',
      createdAt: new Date(0).toISOString(),
      completedAt: new Date(0).toISOString(),
      steps: [
        {
          index: 1,
          phase: 'FINISH',
          thought: 'The deterministic test upstream returned a completed investigation.',
          toolName: null,
          toolArgs: null,
          observation: null,
          note: null,
          timestamp: new Date(0).toISOString(),
          durationMs: 1,
        },
      ],
    });
    return { investigationId: id, status: 'COMPLETED' };
  }

  async getInvestigation(_accessToken: string, id: string): Promise<unknown> {
    const investigation = this.investigations.get(id);
    if (!investigation) throw new UpstreamError(404, 'rejected', 'Investigation not found');
    return investigation;
  }
}
