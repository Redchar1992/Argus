import { useState, type FormEvent } from 'react';
import type { AuthState } from '../auth/authMachine';
import { useI18n } from '../i18n';

interface LoginPageProps {
  state: Extract<AuthState, { status: 'anonymous' | 'authenticating' | 'expired' | 'error' }>;
  onLogin: (username: string, password: string) => Promise<void>;
  onRetrySession: () => Promise<void>;
}

export function LoginPage({ state, onLogin, onRetrySession }: LoginPageProps) {
  const { lang, setLang, t } = useI18n();
  const [username, setUsername] = useState(state.status === 'error' ? state.username ?? '' : '');
  const [password, setPassword] = useState('');
  const authenticating = state.status === 'authenticating';
  const message = state.status === 'expired' || state.status === 'error' || state.status === 'anonymous'
    ? state.message
    : undefined;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || !password) return;
    void onLogin(username, password);
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
        <h1 id="login-title">{t('auth.title')}</h1>
        <p className="auth-intro">{t('auth.intro')}</p>

        {message && <div className={`alert ${state.status === 'anonymous' ? 'info' : 'error'}`} role="alert">{message}</div>}

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

        <div className="auth-demo">
          <strong>{t('auth.demo')}</strong>
          <code>analyst / analyst12345</code>
          <span>{t('auth.demoOnly')}</span>
        </div>

        {state.status === 'error' && state.message.toLowerCase().includes('unavailable') && (
          <button className="link-button" onClick={() => void onRetrySession()}>{t('auth.retry')}</button>
        )}

        <p className="auth-security">{t('auth.security')}</p>
      </section>
    </main>
  );
}
