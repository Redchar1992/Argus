export type ErrorCode =
  | 'BAD_REQUEST'
  | 'CSRF_INVALID'
  | 'FORBIDDEN_ORIGIN'
  | 'INVALID_CREDENTIALS'
  | 'INVALID_MFA_CODE'
  | 'INVALID_RECOVERY_CODE'
  | 'MFA_CHALLENGE_EXPIRED'
  | 'PASSKEY_CEREMONY_EXPIRED'
  | 'PASSKEY_REGISTRATION_FAILED'
  | 'PASSKEY_AUTHENTICATION_FAILED'
  | 'RATE_LIMITED'
  | 'UNAUTHENTICATED'
  | 'SESSION_EXPIRED'
  | 'IDENTITY_STORE_UNAVAILABLE'
  | 'UPSTREAM_REJECTED'
  | 'UPSTREAM_TIMEOUT'
  | 'UPSTREAM_UNAVAILABLE'
  | 'INTERNAL_ERROR';

export class AppError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: ErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'AppError';
  }
}

export class UpstreamError extends Error {
  constructor(
    public readonly status: number,
    public readonly kind: 'rejected' | 'timeout' | 'unavailable',
    message: string,
  ) {
    super(message);
    this.name = 'UpstreamError';
  }
}
