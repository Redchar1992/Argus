import { useState, type FormEvent } from 'react';
import type { AuthState, MfaMethod } from '../auth/authMachine';
import { useI18n } from '../i18n';

interface LoginPageProps {
  state: Extract<AuthState, {
    status: 'anonymous' | 'authenticating' | 'expired' | 'error' | 'mfa_required' | 'verifying_mfa'
  }>;
  onLogin: (username: string, password: string) => Promise<void>;
  onVerifyMfa: (method: MfaMethod, code: string) => Promise<void>;
  onCancelMfa: () => void;
  onRetrySession: () => Promise<void>;
}

export function LoginPage({ state, onLogin, onVerifyMfa, onCancelMfa, onRetrySession }: LoginPageProps) {
  const { lang, setLang, t } = useI18n();
  const [username, setUsername] = useState(state.status === 'error' ? state.username ?? '' : '');
  const [password, setPassword] = useState('');
  const challenge = state.status === 'mfa_required' || state.status === 'verifying_mfa'
    ? state.challenge
    : undefined;
  const [method, setMethod] = useState<MfaMethod>(challenge?.methods[0] ?? 'TOTP');
  const [verificationCode, setVerificationCode] = useState('');
  const authenticating = state.status === 'authenticating';
  const verifying = state.status === 'verifying_mfa';
  const message = state.status === 'expired' || state.status === 'error' || state.status === 'anonymous'
    ? state.message
    : state.status === 'mfa_required' ? state.message
    : undefined;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || !password) return;
    void onLogin(username, password);
  };

  const submitMfa = (event: FormEvent) => {
    event.preventDefault();
    if (!verificationCode.trim()) return;
    void onVerifyMfa(method, verificationCode);
  };

  return (
    <main className="auth-shell">
      <section className="auth-card" aria-labelledby="login-title">
        <div className="auth-card-top">
          <div>
            <div className="logo">Argus</div>
            <div className="tagline">{t('tagline')}</div>
          </div>
          <button className="lang-toggle" onClick={() => setLang(lang === 'en' ? 'zh' : 'en')}>
            {lang === 'en' ? '繁中' : 'EN'}
          </button>
        </div>

        <div className="auth-lock" aria-hidden>◎</div>
        <h1 id="login-title">{challenge ? t('auth.mfaTitle') : t('auth.title')}</h1>
        <p className="auth-intro">
          {challenge ? `${t('auth.mfaIntro')} ${challenge.username}.` : t('auth.intro')}
        </p>

        {message && <div className={`alert ${state.status === 'anonymous' ? 'info' : 'error'}`} role="alert">{message}</div>}

        {challenge ? (
          <form className="auth-form" onSubmit={submitMfa}>
            {challenge.methods.length > 1 && (
              <>
                <label htmlFor="mfa-method">{t('auth.mfaMethod')}</label>
                <select
                  id="mfa-method"
                  className="input"
                  value={method}
                  disabled={verifying}
                  onChange={(event) => setMethod(event.target.value as MfaMethod)}
                >
                  {challenge.methods.map((available) => (
                    <option key={available} value={available}>
                      {available === 'TOTP' ? t('auth.totp') : t('auth.recoveryCode')}
                    </option>
                  ))}
                </select>
              </>
            )}
            <label htmlFor="verification-code">
              {method === 'TOTP' ? t('auth.totpCode') : t('auth.recoveryCode')}
            </label>
            <input
              id="verification-code"
              name="verification-code"
              className="input"
              autoComplete="one-time-code"
              inputMode={method === 'TOTP' ? 'numeric' : 'text'}
              value={verificationCode}
              disabled={verifying}
              onChange={(event) => setVerificationCode(event.target.value)}
              autoFocus
            />
            <button className="btn auth-submit" type="submit" disabled={verifying || !verificationCode.trim()}>
              {verifying && <span className="spinner" />}
              {verifying ? t('auth.verifying') : t('auth.verify')}
            </button>
            <button className="link-button" type="button" disabled={verifying} onClick={onCancelMfa}>
              {t('auth.useAnotherAccount')}
            </button>
          </form>
        ) : (
        <form className="auth-form" onSubmit={submit}>
          <label htmlFor="username">{t('auth.username')}</label>
          <input
            id="username"
            name="username"
            className="input"
            autoComplete="username"
            value={username}
            disabled={authenticating}
            onChange={(event) => setUsername(event.target.value)}
          />

          <label htmlFor="password">{t('auth.password')}</label>
          <input
            id="password"
            name="password"
            className="input"
            type="password"
            autoComplete="current-password"
            value={password}
            disabled={authenticating}
            onChange={(event) => setPassword(event.target.value)}
          />

          <button className="btn auth-submit" type="submit" disabled={authenticating || !username.trim() || !password}>
            {authenticating && <span className="spinner" />}
            {authenticating ? t('auth.signingIn') : t('auth.signIn')}
          </button>
        </form>

        )}

        {!challenge && <div className="auth-demo">
          <strong>{t('auth.demo')}</strong>
          <code>analyst / analyst12345</code>
          <span>{t('auth.demoOnly')}</span>
        </div>}

        {state.status === 'error' && state.message.toLowerCase().includes('unavailable') && (
          <button className="link-button" onClick={() => void onRetrySession()}>{t('auth.retry')}</button>
        )}

        <p className="auth-security">{t('auth.security')}</p>
      </section>
    </main>
  );
}
