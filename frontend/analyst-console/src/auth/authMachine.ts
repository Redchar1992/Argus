export type Role = 'ANALYST' | 'ADMIN';

export interface AuthUser {
  username: string;
  role: Role;
}

export interface AuthSession {
  user: AuthUser;
  expiresAt: string;
}

export type MfaMethod = 'TOTP' | 'RECOVERY_CODE';

export interface MfaChallenge {
  username: string;
  methods: MfaMethod[];
  expiresAt: string;
}

/**
 * Authentication is a discriminated union, not a collection of booleans. That
 * makes impossible states (for example "authenticated and expired")
 * unrepresentable and gives every transition one explicit owner.
 */
export type AuthState =
  | { status: 'checking' }
  | { status: 'anonymous'; message?: string }
  | { status: 'authenticating'; username: string }
  | { status: 'authenticating_passkey' }
  | { status: 'mfa_required'; challenge: MfaChallenge; message?: string }
  | { status: 'verifying_mfa'; challenge: MfaChallenge }
  | { status: 'authenticated'; session: AuthSession }
  | { status: 'signingOut'; session: AuthSession }
  | { status: 'expired'; message: string }
  | { status: 'error'; message: string; username?: string };

export type AuthEvent =
  | { type: 'CHECK_SESSION' }
  | { type: 'SESSION_FOUND'; session: AuthSession }
  | { type: 'NO_SESSION'; message?: string }
  | { type: 'LOGIN_STARTED'; username: string }
  | { type: 'PASSKEY_LOGIN_STARTED' }
  | { type: 'LOGIN_SUCCEEDED'; session: AuthSession }
  | { type: 'MFA_REQUIRED'; challenge: MfaChallenge }
  | { type: 'MFA_VERIFY_STARTED' }
  | { type: 'MFA_FAILED'; message: string }
  | { type: 'MFA_CANCELLED' }
  | { type: 'LOGIN_FAILED'; message: string; username: string }
  | { type: 'PASSKEY_LOGIN_FAILED'; message: string }
  | { type: 'SESSION_EXPIRED'; message: string }
  | { type: 'LOGOUT_STARTED' }
  | { type: 'LOGOUT_FINISHED' }
  | { type: 'SESSION_CHECK_FAILED'; message: string };

export const initialAuthState: AuthState = { status: 'checking' };

export function authReducer(state: AuthState, event: AuthEvent): AuthState {
  switch (event.type) {
    case 'CHECK_SESSION':
      return { status: 'checking' };
    case 'SESSION_FOUND':
    case 'LOGIN_SUCCEEDED':
      return { status: 'authenticated', session: event.session };
    case 'NO_SESSION':
      return { status: 'anonymous', ...(event.message ? { message: event.message } : {}) };
    case 'LOGIN_STARTED':
      return { status: 'authenticating', username: event.username };
    case 'PASSKEY_LOGIN_STARTED':
      return { status: 'authenticating_passkey' };
    case 'MFA_REQUIRED':
      return { status: 'mfa_required', challenge: event.challenge };
    case 'MFA_VERIFY_STARTED':
      return state.status === 'mfa_required' ? { status: 'verifying_mfa', challenge: state.challenge } : state;
    case 'MFA_FAILED':
      return state.status === 'verifying_mfa'
        ? { status: 'mfa_required', challenge: state.challenge, message: event.message }
        : state;
    case 'MFA_CANCELLED':
      return { status: 'anonymous' };
    case 'LOGIN_FAILED':
      return { status: 'error', message: event.message, username: event.username };
    case 'PASSKEY_LOGIN_FAILED':
      return { status: 'error', message: event.message };
    case 'SESSION_EXPIRED':
      return { status: 'expired', message: event.message };
    case 'LOGOUT_STARTED':
      return state.status === 'authenticated' ? { status: 'signingOut', session: state.session } : state;
    case 'LOGOUT_FINISHED':
      return { status: 'anonymous', message: 'You have signed out.' };
    case 'SESSION_CHECK_FAILED':
      return { status: 'error', message: event.message };
  }
}
