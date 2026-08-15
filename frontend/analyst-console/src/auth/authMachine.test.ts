import { describe, expect, it } from 'vitest';
import { authReducer, initialAuthState, type AuthSession } from './authMachine';

const session: AuthSession = {
  user: { username: 'analyst', role: 'ANALYST' },
  expiresAt: '2026-08-15T12:00:00.000Z',
};

describe('authReducer', () => {
  it('models the successful login lifecycle with explicit states', () => {
    const authenticating = authReducer(initialAuthState, { type: 'LOGIN_STARTED', username: 'analyst' });
    expect(authenticating).toEqual({ status: 'authenticating', username: 'analyst' });

    const authenticated = authReducer(authenticating, { type: 'LOGIN_SUCCEEDED', session });
    expect(authenticated).toEqual({ status: 'authenticated', session });

    const signingOut = authReducer(authenticated, { type: 'LOGOUT_STARTED' });
    expect(signingOut).toEqual({ status: 'signingOut', session });
    expect(authReducer(signingOut, { type: 'LOGOUT_FINISHED' })).toEqual({
      status: 'anonymous',
      message: 'You have signed out.',
    });
  });

  it('keeps invalid credentials separate from an expired session', () => {
    expect(
      authReducer({ status: 'authenticating', username: 'analyst' }, {
        type: 'LOGIN_FAILED',
        username: 'analyst',
        message: 'Invalid username or password',
      }),
    ).toEqual({ status: 'error', username: 'analyst', message: 'Invalid username or password' });

    expect(
      authReducer({ status: 'authenticated', session }, {
        type: 'SESSION_EXPIRED',
        message: 'Your session expired. Sign in again.',
      }),
    ).toEqual({ status: 'expired', message: 'Your session expired. Sign in again.' });
  });

  it('does not create a signing-out state for an anonymous user', () => {
    const anonymous = { status: 'anonymous' } as const;
    expect(authReducer(anonymous, { type: 'LOGOUT_STARTED' })).toBe(anonymous);
  });

  it('keeps the second factor as an explicit pre-authenticated state', () => {
    const challenge = {
      username: 'analyst',
      methods: ['TOTP'] as const,
      expiresAt: '2026-08-15T11:00:00.000Z',
    };
    const required = authReducer({ status: 'authenticating', username: 'analyst' }, {
      type: 'MFA_REQUIRED',
      challenge: { ...challenge, methods: [...challenge.methods] },
    });
    expect(required).toEqual({ status: 'mfa_required', challenge });
    const verifying = authReducer(required, { type: 'MFA_VERIFY_STARTED' });
    expect(verifying).toEqual({ status: 'verifying_mfa', challenge });
    expect(authReducer(verifying, { type: 'MFA_FAILED', message: 'Invalid code' })).toEqual({
      status: 'mfa_required', challenge, message: 'Invalid code',
    });
  });
});
