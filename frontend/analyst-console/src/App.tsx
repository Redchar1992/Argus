import { AuthProvider, useAuth } from './auth/AuthContext';
import { LangProvider } from './i18n';
import { InvestigatePage } from './pages/InvestigatePage';
import { LoginPage } from './pages/LoginPage';

function AuthenticatedApp() {
  const { state, login, verifyMfa, cancelMfa, recoverAccount, logout, retrySession } = useAuth();

  if (state.status === 'checking') {
    return (
      <main className="auth-shell" aria-live="polite">
        <div className="auth-loading"><span className="spinner" /> Checking your secure session…</div>
      </main>
    );
  }

  if (state.status === 'authenticated' || state.status === 'signingOut') {
    return (
      <InvestigatePage
        user={state.session.user}
        signingOut={state.status === 'signingOut'}
        onLogout={logout}
      />
    );
  }

  return <LoginPage
    state={state}
    onLogin={login}
    onVerifyMfa={verifyMfa}
    onCancelMfa={cancelMfa}
    onRecoverAccount={recoverAccount}
    onRetrySession={retrySession}
  />;
}

export default function App() {
  return (
    <LangProvider>
      <AuthProvider>
        <AuthenticatedApp />
      </AuthProvider>
    </LangProvider>
  );
}
