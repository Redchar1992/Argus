import { createContext, useCallback, useContext, useEffect, useReducer, useRef, type ReactNode } from 'react';
import {
  ApiError,
  getSession,
  login as loginRequest,
  logout as logoutRequest,
  recoverAccount as recoverAccountRequest,
  verifyMfa,
} from '../api/bff';
import { authReducer, initialAuthState, type AuthState, type MfaMethod } from './authMachine';
import { authenticateWithPasskey } from '../api/passkeys';

interface AuthContextValue {
  state: AuthState;
  login: (username: string, password: string) => Promise<void>;
  loginWithPasskey: () => Promise<void>;
  verifyMfa: (method: MfaMethod, code: string) => Promise<void>;
  cancelMfa: () => void;
  recoverAccount: (username: string, recoveryCode: string, newPassword: string) => Promise<void>;
  logout: () => Promise<void>;
  retrySession: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function friendlyMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof TypeError) return 'The identity service is unavailable. Try again.';
  if (error instanceof Error && error.message) return error.message;
  return 'The identity service is unavailable. Try again.';
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(authReducer, initialAuthState);
  const bootstrapped = useRef(false);

  const retrySession = useCallback(async () => {
    dispatch({ type: 'CHECK_SESSION' });
    try {
      const result = await getSession();
      if (result.state === 'mfa_required') dispatch({ type: 'MFA_REQUIRED', challenge: result.challenge });
      else dispatch({ type: 'SESSION_FOUND', session: result.session });
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        if (error.code === 'SESSION_EXPIRED') {
          dispatch({ type: 'SESSION_EXPIRED', message: error.message });
        } else {
          dispatch({ type: 'NO_SESSION' });
        }
        return;
      }
      dispatch({ type: 'SESSION_CHECK_FAILED', message: friendlyMessage(error) });
    }
  }, []);

  useEffect(() => {
    if (!bootstrapped.current) {
      bootstrapped.current = true;
      void retrySession();
    }
  }, [retrySession]);

  useEffect(() => {
    const expire = (event: Event) => {
      const message = event instanceof CustomEvent && typeof event.detail === 'string'
        ? event.detail
        : 'Your session expired. Sign in again.';
      dispatch({ type: 'SESSION_EXPIRED', message });
    };
    window.addEventListener('argus:session-expired', expire);
    return () => window.removeEventListener('argus:session-expired', expire);
  }, []);

  useEffect(() => {
    if (state.status !== 'authenticated') return;
    const expiresAt = Date.parse(state.session.expiresAt);
    let timer: number | undefined;
    const schedule = () => {
      const remaining = expiresAt - Date.now();
      if (!Number.isFinite(expiresAt) || remaining <= 0) {
        dispatch({ type: 'SESSION_EXPIRED', message: 'Your session expired. Sign in again.' });
        return;
      }
      // Browser timers clamp very large delays. Re-check at each clamp instead
      // of leaving protected data mounted past the server-declared deadline.
      timer = window.setTimeout(schedule, Math.min(remaining, 2_147_483_647));
    };
    schedule();
    return () => {
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [state]);

  useEffect(() => {
    if (state.status !== 'mfa_required') return;
    const remaining = Date.parse(state.challenge.expiresAt) - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) {
      dispatch({ type: 'MFA_CANCELLED' });
      return;
    }
    const timer = window.setTimeout(() => dispatch({ type: 'MFA_CANCELLED' }), remaining);
    return () => window.clearTimeout(timer);
  }, [state]);

  const login = useCallback(async (username: string, password: string) => {
    const normalizedUsername = username.trim();
    dispatch({ type: 'LOGIN_STARTED', username: normalizedUsername });
    try {
      const result = await loginRequest(normalizedUsername, password);
      if (result.state === 'mfa_required') dispatch({ type: 'MFA_REQUIRED', challenge: result.challenge });
      else dispatch({ type: 'LOGIN_SUCCEEDED', session: result.session });
    } catch (error) {
      dispatch({ type: 'LOGIN_FAILED', message: friendlyMessage(error), username: normalizedUsername });
    }
  }, []);

  const loginWithPasskey = useCallback(async () => {
    dispatch({ type: 'PASSKEY_LOGIN_STARTED' });
    try {
      const session = await authenticateWithPasskey();
      dispatch({ type: 'LOGIN_SUCCEEDED', session });
    } catch (error) {
      dispatch({ type: 'PASSKEY_LOGIN_FAILED', message: friendlyMessage(error) });
    }
  }, []);

  const submitMfa = useCallback(async (method: MfaMethod, code: string) => {
    dispatch({ type: 'MFA_VERIFY_STARTED' });
    try {
      const session = await verifyMfa(method, code);
      dispatch({ type: 'LOGIN_SUCCEEDED', session });
    } catch (error) {
      dispatch({ type: 'MFA_FAILED', message: friendlyMessage(error) });
    }
  }, []);

  const cancelMfa = useCallback(() => dispatch({ type: 'MFA_CANCELLED' }), []);

  const recoverAccount = useCallback(async (username: string, recoveryCode: string, newPassword: string) => {
    const result = await recoverAccountRequest(username.trim(), recoveryCode, newPassword);
    dispatch({ type: 'NO_SESSION', message: result.message });
  }, []);

  const logout = useCallback(async () => {
    dispatch({ type: 'LOGOUT_STARTED' });
    try {
      await logoutRequest();
      dispatch({ type: 'LOGOUT_FINISHED' });
    } catch (error) {
      // Fail closed in the UI: do not continue showing protected data if the
      // server cannot confirm logout. A refresh will reconcile the real cookie.
      dispatch({ type: 'NO_SESSION', message: friendlyMessage(error) });
    }
  }, []);

  return <AuthContext.Provider value={{
    state,
    login,
    loginWithPasskey,
    verifyMfa: submitMfa,
    cancelMfa,
    recoverAccount,
    logout,
    retrySession,
  }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used within AuthProvider');
  return value;
}
