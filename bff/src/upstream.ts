import { randomUUID } from 'node:crypto';
import type { AppConfig } from './config.js';
import { UpstreamError } from './errors.js';
import type { AuthUser, Role } from './session-store.js';
import type { MfaMethod } from './mfa-challenge-store.js';
import type {
  PasskeyMaterial,
  PasskeyRegistrationContext,
  PasskeyView,
  VerifiedPasskeyAuthentication,
  VerifiedPasskeyRegistration,
} from './passkeys.js';

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
  passkeyRegistrationContext(accessToken: string, requestId: string): Promise<PasskeyRegistrationContext>;
  registerPasskey(
    accessToken: string,
    material: VerifiedPasskeyRegistration & { label?: string },
    requestId: string,
  ): Promise<PasskeyView>;
  listPasskeys(accessToken: string, requestId: string): Promise<PasskeyView[]>;
  deletePasskey(accessToken: string, credentialId: string, requestId: string): Promise<void>;
  passkeyMaterial(credentialId: string, requestId: string): Promise<PasskeyMaterial>;
  completePasskeyAuthentication(
    credentialId: string,
    expectedCounter: number,
    verification: VerifiedPasskeyAuthentication,
    requestId: string,
  ): Promise<LoginResult>;
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

  async passkeyRegistrationContext(accessToken: string, requestId: string): Promise<PasskeyRegistrationContext> {
    const data = await this.request(`${this.config.authBaseUrl}/api/auth/passkeys/context`, {
      method: 'GET', headers: this.internalAuthHeaders(accessToken, requestId, false),
    }, 'api');
    return this.parsePasskeyRegistrationContext(data);
  }

  async registerPasskey(
    accessToken: string,
    material: VerifiedPasskeyRegistration & { label?: string },
    requestId: string,
  ): Promise<PasskeyView> {
    const data = await this.request(`${this.config.authBaseUrl}/api/auth/passkeys`, {
      method: 'POST',
      headers: this.internalAuthHeaders(accessToken, requestId, true),
      body: JSON.stringify(material),
    }, 'api');
    return this.parsePasskeyView(data);
  }

  async listPasskeys(accessToken: string, requestId: string): Promise<PasskeyView[]> {
    const data = await this.request(`${this.config.authBaseUrl}/api/auth/passkeys`, {
      method: 'GET', headers: this.authHeaders(accessToken, requestId, false),
    }, 'api');
    if (!Array.isArray(data)) {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey inventory');
    }
    return data.map((item) => this.parsePasskeyView(item));
  }

  async deletePasskey(accessToken: string, credentialId: string, requestId: string): Promise<void> {
    await this.request(`${this.config.authBaseUrl}/api/auth/passkeys/${encodeURIComponent(credentialId)}`, {
      method: 'DELETE', headers: this.authHeaders(accessToken, requestId, false),
    }, 'api');
  }

  async passkeyMaterial(credentialId: string, requestId: string): Promise<PasskeyMaterial> {
    const data = await this.request(
      `${this.config.authBaseUrl}/api/auth/internal/passkeys/${encodeURIComponent(credentialId)}`,
      { method: 'GET', headers: this.internalHeaders(requestId, false) },
      'login',
    );
    return this.parsePasskeyMaterial(data);
  }

  async completePasskeyAuthentication(
    credentialId: string,
    expectedCounter: number,
    verification: VerifiedPasskeyAuthentication,
    requestId: string,
  ): Promise<LoginResult> {
    const data = await this.request(`${this.config.authBaseUrl}/api/auth/internal/passkeys/complete`, {
      method: 'POST',
      headers: this.internalHeaders(requestId, true),
      body: JSON.stringify({
        credentialId,
        expectedCounter,
        newCounter: verification.newCounter,
        deviceType: verification.deviceType,
        backedUp: verification.backedUp,
      }),
    }, 'login');
    const result = this.parseAuthenticationResult(data);
    if (isMfaChallenge(result)) {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned a nested MFA challenge');
    }
    return result;
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

  private parsePasskeyRegistrationContext(data: unknown): PasskeyRegistrationContext {
    if (!data || typeof data !== 'object') {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey context');
    }
    const value = data as Record<string, unknown>;
    if (!Number.isSafeInteger(value.userId) || Number(value.userId) <= 0
      || typeof value.username !== 'string' || !Array.isArray(value.credentials)) {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey context');
    }
    return {
      userId: Number(value.userId),
      username: value.username,
      credentials: value.credentials.map((credential) => this.parsePasskeyMaterial(credential)),
    };
  }

  private parsePasskeyMaterial(data: unknown): PasskeyMaterial {
    if (!data || typeof data !== 'object') {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey material');
    }
    const value = data as Record<string, unknown>;
    const transports = value.transports;
    if (typeof value.credentialId !== 'string' || typeof value.publicKey !== 'string'
      || !Number.isSafeInteger(value.counter) || Number(value.counter) < 0
      || !Array.isArray(transports) || !transports.every((transport) => typeof transport === 'string')
      || typeof value.username !== 'string'
      || (value.deviceType !== 'singleDevice' && value.deviceType !== 'multiDevice')
      || typeof value.backedUp !== 'boolean') {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey material');
    }
    return {
      credentialId: value.credentialId,
      publicKey: value.publicKey,
      counter: Number(value.counter),
      transports,
      username: value.username,
      deviceType: value.deviceType,
      backedUp: value.backedUp,
    };
  }

  private parsePasskeyView(data: unknown): PasskeyView {
    if (!data || typeof data !== 'object') {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey inventory');
    }
    const value = data as Record<string, unknown>;
    const transports = value.transports;
    if (typeof value.credentialId !== 'string' || typeof value.label !== 'string'
      || !Array.isArray(transports) || !transports.every((transport) => typeof transport === 'string')
      || (value.deviceType !== 'singleDevice' && value.deviceType !== 'multiDevice')
      || typeof value.backedUp !== 'boolean' || typeof value.createdAt !== 'string'
      || (value.lastUsedAt !== null && typeof value.lastUsedAt !== 'string')) {
      throw new UpstreamError(502, 'unavailable', 'Authentication service returned invalid passkey inventory');
    }
    return {
      credentialId: value.credentialId,
      label: value.label,
      transports,
      deviceType: value.deviceType,
      backedUp: value.backedUp,
      createdAt: value.createdAt,
      lastUsedAt: value.lastUsedAt,
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

  private internalHeaders(requestId: string, json: boolean): Record<string, string> {
    return {
      accept: 'application/json',
      ...(json ? { 'content-type': 'application/json' } : {}),
      'x-request-id': requestId,
      'x-argus-bff-secret': this.config.internalBffSecret,
    };
  }

  private internalAuthHeaders(accessToken: string, requestId: string, json: boolean): Record<string, string> {
    return { ...this.authHeaders(accessToken, requestId, json), 'x-argus-bff-secret': this.config.internalBffSecret };
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
  private readonly passkeys = new Map<string, PasskeyMaterial>();
  private readonly passkeyLabels = new Map<string, string>();

  async login(username: string, password: string, _requestId?: string): Promise<AuthenticationResult> {
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

  async oidcLogin(idToken: string, nonce: string, _requestId?: string): Promise<AuthenticationResult> {
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

  async verifyMfa(
    _challengeToken: string,
    _method: MfaMethod,
    _code: string,
    _requestId?: string,
  ): Promise<LoginResult> {
    throw new UpstreamError(401, 'rejected', 'No mock MFA challenge');
  }

  async mfaStatus(_accessToken: string, _requestId?: string): Promise<unknown> {
    return { enabled: false, enrolledAt: null };
  }

  async setupTotp(_accessToken: string, _requestId?: string): Promise<unknown> {
    return { secret: 'MOCKONLY', provisioningUri: 'otpauth://totp/mock', expiresAt: new Date(0).toISOString() };
  }

  async confirmTotp(_accessToken: string, _code: string, _requestId?: string): Promise<unknown> {
    return { enabled: true, enrolledAt: new Date(0).toISOString() };
  }

  async disableTotp(_accessToken: string, _code: string, _requestId?: string): Promise<unknown> {
    return { enabled: false, enrolledAt: null };
  }

  async recoveryStatus(_accessToken: string, _requestId?: string): Promise<unknown> {
    return { remaining: 0 };
  }

  async regenerateRecoveryCodes(_accessToken: string, _totpCode: string, _requestId?: string): Promise<unknown> {
    return { recoveryCodes: [], remaining: 0, generatedAt: new Date(0).toISOString() };
  }

  async recoverAccount(
    _username: string,
    _recoveryCode: string,
    _newPassword: string,
    _requestId?: string,
  ): Promise<unknown> {
    return { state: 'recovered', message: 'Password reset. Sign in with your new password.' };
  }

  async passkeyRegistrationContext(_accessToken: string, _requestId?: string): Promise<PasskeyRegistrationContext> {
    return { userId: 1, username: 'analyst', credentials: [...this.passkeys.values()] };
  }

  async registerPasskey(
    _accessToken: string,
    material: VerifiedPasskeyRegistration & { label?: string },
    _requestId?: string,
  ): Promise<PasskeyView> {
    this.passkeys.set(material.credentialId, {
      ...material,
      username: 'analyst',
    });
    this.passkeyLabels.set(material.credentialId, material.label ?? 'Passkey');
    return {
      credentialId: material.credentialId,
      label: material.label ?? 'Passkey',
      transports: material.transports,
      deviceType: material.deviceType,
      backedUp: material.backedUp,
      createdAt: new Date().toISOString(),
      lastUsedAt: null,
    };
  }

  async listPasskeys(_accessToken: string, _requestId?: string): Promise<PasskeyView[]> {
    return [...this.passkeys.values()].map((value) => ({
      credentialId: value.credentialId,
      transports: value.transports,
      deviceType: value.deviceType,
      backedUp: value.backedUp,
      label: this.passkeyLabels.get(value.credentialId) ?? 'Passkey',
      createdAt: new Date(0).toISOString(),
      lastUsedAt: null,
    }));
  }

  async deletePasskey(_accessToken: string, credentialId: string, _requestId?: string): Promise<void> {
    if (!this.passkeys.delete(credentialId)) throw new UpstreamError(404, 'rejected', 'Passkey not found');
    this.passkeyLabels.delete(credentialId);
  }

  async passkeyMaterial(credentialId: string, _requestId?: string): Promise<PasskeyMaterial> {
    const material = this.passkeys.get(credentialId);
    if (!material) throw new UpstreamError(401, 'rejected', 'Invalid passkey');
    return material;
  }

  async completePasskeyAuthentication(
    credentialId: string,
    expectedCounter: number,
    verification: VerifiedPasskeyAuthentication,
    _requestId?: string,
  ): Promise<LoginResult> {
    const material = await this.passkeyMaterial(credentialId);
    if (material.counter !== expectedCounter) throw new UpstreamError(401, 'rejected', 'Invalid passkey');
    this.passkeys.set(credentialId, { ...material, counter: verification.newCounter });
    return {
      token: `mock-only-${randomUUID()}`,
      expiresInSeconds: 3_600,
      username: material.username,
      role: 'ANALYST',
    };
  }

  async submitInvestigation(_accessToken: string, body: unknown, _requestId?: string): Promise<unknown> {
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

  async getInvestigation(_accessToken: string, id: string, _requestId?: string): Promise<unknown> {
    const investigation = this.investigations.get(id);
    if (!investigation) throw new UpstreamError(404, 'rejected', 'Investigation not found');
    return investigation;
  }
}
