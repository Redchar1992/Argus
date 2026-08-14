import { createContext, useCallback, useContext, useEffect, useReducer, useRef, type ReactNode } from 'react';
import { ApiError, getSession, login as loginRequest, logout as logoutRequest } from '../api/bff';
import { authReducer, initialAuthState, type AuthState } from './authMachine';

interface AuthContextValue {
  state: AuthState;
  login: (username: string, password: string) => Promise<void>;
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
      const session = await getSession();
      dispatch({ type: 'SESSION_FOUND', session });
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

  const login = useCallback(async (username: string, password: string) => {
    const normalizedUsername = username.trim();
    dispatch({ type: 'LOGIN_STARTED', username: normalizedUsername });
    try {
      const session = await loginRequest(normalizedUsername, password);
      dispatch({ type: 'LOGIN_SUCCEEDED', session });
    } catch (error) {
      dispatch({ type: 'LOGIN_FAILED', message: friendlyMessage(error), username: normalizedUsername });
    }
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

  return <AuthContext.Provider value={{ state, login, logout, retrySession }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used within AuthProvider');
  return value;
}
