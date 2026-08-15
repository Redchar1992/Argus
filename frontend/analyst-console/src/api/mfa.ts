import { bffRequest, ensureCsrfCookie } from './bff';

export interface MfaStatus {
  enabled: boolean;
  enrolledAt: string | null;
}

export interface TotpSetup {
  secret: string;
  provisioningUri: string;
  expiresAt: string;
}

export interface MfaEnrollment extends MfaStatus {
  recoveryCodes: string[];
}

export interface RecoveryStatus {
  remaining: number;
}

export interface RecoveryCodes extends RecoveryStatus {
  recoveryCodes: string[];
  generatedAt: string;
}

async function postWithCode<T>(path: string, code: string): Promise<T> {
  await ensureCsrfCookie();
  return bffRequest<T>(path, {
    method: 'POST',
    csrf: true,
    notifySessionExpiry: true,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code }),
  });
}

export function getMfaStatus(): Promise<MfaStatus> {
  return bffRequest<MfaStatus>('/bff/auth/mfa', { notifySessionExpiry: true });
}

export function getRecoveryStatus(): Promise<RecoveryStatus> {
  return bffRequest<RecoveryStatus>('/bff/auth/recovery', { notifySessionExpiry: true });
}

export async function setupTotp(): Promise<TotpSetup> {
  await ensureCsrfCookie();
  return bffRequest<TotpSetup>('/bff/auth/mfa/totp/setup', {
    method: 'POST',
    csrf: true,
    notifySessionExpiry: true,
  });
}

export function confirmTotp(code: string): Promise<MfaEnrollment> {
  return postWithCode('/bff/auth/mfa/totp/confirm', code);
}

export function disableTotp(code: string): Promise<MfaStatus> {
  return postWithCode('/bff/auth/mfa/totp/disable', code);
}

export function regenerateRecoveryCodes(code: string): Promise<RecoveryCodes> {
  return postWithCode('/bff/auth/recovery/codes/regenerate', code);
}
