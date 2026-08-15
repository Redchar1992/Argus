import {
  browserSupportsWebAuthn,
  startAuthentication,
  startRegistration,
  type PublicKeyCredentialCreationOptionsJSON,
  type PublicKeyCredentialRequestOptionsJSON,
} from '@simplewebauthn/browser';
import type { AuthSession } from '../auth/authMachine';
import { ApiError, bffRequest, ensureCsrfCookie } from './bff';

export interface PasskeyView {
  credentialId: string;
  label: string;
  transports: string[];
  deviceType: 'singleDevice' | 'multiDevice';
  backedUp: boolean;
  createdAt: string;
  lastUsedAt: string | null;
}

interface OptionsEnvelope<T> {
  options: T;
}

interface SessionEnvelope {
  state: 'authenticated';
  user: AuthSession['user'];
  expiresAt: string;
}

export function passkeysSupported(): boolean {
  return browserSupportsWebAuthn();
}

function requireSupport(): void {
  if (!passkeysSupported()) {
    throw new ApiError(400, 'PASSKEY_UNSUPPORTED', 'This browser does not support passkeys.');
  }
}

export async function authenticateWithPasskey(): Promise<AuthSession> {
  requireSupport();
  await ensureCsrfCookie();
  const { options } = await bffRequest<OptionsEnvelope<PublicKeyCredentialRequestOptionsJSON>>(
    '/bff/auth/passkeys/authentication/options',
    { method: 'POST', csrf: true },
  );
  const response = await startAuthentication({ optionsJSON: options });
  const result = await bffRequest<SessionEnvelope>('/bff/auth/passkeys/authentication/verify', {
    method: 'POST',
    csrf: true,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ response }),
  });
  if (result.state !== 'authenticated' || !result.user || !result.expiresAt) {
    throw new ApiError(502, 'INVALID_RESPONSE', 'The identity service returned an invalid response.');
  }
  return { user: result.user, expiresAt: result.expiresAt };
}

export async function registerPasskey(label: string): Promise<PasskeyView> {
  requireSupport();
  await ensureCsrfCookie();
  const { options } = await bffRequest<OptionsEnvelope<PublicKeyCredentialCreationOptionsJSON>>(
    '/bff/auth/passkeys/registration/options',
    { method: 'POST', csrf: true, notifySessionExpiry: true },
  );
  const response = await startRegistration({ optionsJSON: options });
  return bffRequest<PasskeyView>('/bff/auth/passkeys/registration/verify', {
    method: 'POST',
    csrf: true,
    notifySessionExpiry: true,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ response, label: label.trim() || undefined }),
  });
}

export async function listPasskeys(): Promise<PasskeyView[]> {
  return bffRequest<PasskeyView[]>('/bff/auth/passkeys', { notifySessionExpiry: true });
}

export async function deletePasskey(credentialId: string): Promise<void> {
  await ensureCsrfCookie();
  await bffRequest<void>(`/bff/auth/passkeys/${encodeURIComponent(credentialId)}`, {
    method: 'DELETE',
    csrf: true,
    notifySessionExpiry: true,
  });
}
