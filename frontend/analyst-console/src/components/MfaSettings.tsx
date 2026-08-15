import { useState } from 'react';
import {
  confirmTotp,
  disableTotp,
  getMfaStatus,
  getRecoveryStatus,
  regenerateRecoveryCodes,
  setupTotp,
  type MfaStatus,
  type TotpSetup,
} from '../api/mfa';
import { useI18n } from '../i18n';

const LOCAL_DEMO = import.meta.env.VITE_LOCAL_DEMO === 'true';

export function MfaSettings() {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<MfaStatus>();
  const [remaining, setRemaining] = useState(0);
  const [setup, setSetup] = useState<TotpSetup>();
  const [code, setCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(undefined);
    setNotice(undefined);
    try {
      await action();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('mfa.failed'));
    } finally {
      setBusy(false);
    }
  };

  const load = async () => {
    const [mfa, recovery] = await Promise.all([getMfaStatus(), getRecoveryStatus()]);
    setStatus(mfa);
    setRemaining(recovery.remaining);
  };

  const toggle = () => {
    const next = !open;
    setOpen(next);
    setError(undefined);
    setNotice(undefined);
    if (next) void run(load);
  };

  const begin = () => void run(async () => {
    setSetup(await setupTotp());
    setRecoveryCodes([]);
    setCode('');
  });

  const confirm = () => void run(async () => {
    const enrollment = await confirmTotp(code);
    setStatus({ enabled: enrollment.enabled, enrolledAt: enrollment.enrolledAt });
    setRemaining(enrollment.recoveryCodes.length);
    setRecoveryCodes(enrollment.recoveryCodes);
    setSetup(undefined);
    setCode('');
    setNotice(t('mfa.enabledNotice'));
  });

  const regenerate = () => void run(async () => {
    const response = await regenerateRecoveryCodes(code);
    setRemaining(response.remaining);
    setRecoveryCodes(response.recoveryCodes);
    setCode('');
    setNotice(t('mfa.regeneratedNotice'));
  });

  const disable = () => void run(async () => {
    const response = await disableTotp(code);
    setStatus(response);
    setSetup(undefined);
    setCode('');
    setRecoveryCodes([]);
    setNotice(t('mfa.disabledNotice'));
  });

  const copyCodes = () => void run(async () => {
    if (!navigator.clipboard) throw new Error(t('mfa.copyUnavailable'));
    await navigator.clipboard.writeText(recoveryCodes.join('\n'));
    setNotice(t('mfa.copied'));
  });

  return (
    <section className="security-settings" aria-labelledby={open ? 'mfa-settings-title' : undefined}>
      <button className="secondary-btn" type="button" onClick={toggle} aria-expanded={open}>
        {open ? t('mfa.close') : t('mfa.manage')}
      </button>
      {open && (
        <div className="panel security-panel">
          <div>
            <h2 id="mfa-settings-title">{t('mfa.title')}</h2>
            <p>{t('mfa.intro')}</p>
          </div>
          {error && <div className="alert error" role="alert">{error}</div>}
          {notice && <div className="alert info" role="status">{notice}</div>}
          {!status && !error && <p className="security-loading"><span className="spinner" /> {t('mfa.loading')}</p>}

          {status && !status.enabled && !setup && (
            <div className="security-summary">
              <span className="status-pill failed">{t('mfa.off')}</span>
              <span>{t('mfa.offDescription')}</span>
              <button className="btn" type="button" disabled={busy} onClick={begin}>
                {busy && <span className="spinner" />}{t('mfa.startSetup')}
              </button>
            </div>
          )}

          {setup && (
            <div className="totp-setup">
              <h3>{t('mfa.setupTitle')}</h3>
              <ol>
                <li>{t('mfa.setupStep1')}</li>
                <li>{t('mfa.setupStep2')}</li>
                <li>{t('mfa.setupStep3')}</li>
              </ol>
              <label>{t('mfa.secret')}</label>
              <code className="secret-code" data-testid="totp-secret">{setup.secret}</code>
              <a className="link" href={setup.provisioningUri}>{t('mfa.openAuthenticator')}</a>
              {LOCAL_DEMO && (
                <div className="demo-helper">
                  <strong>{t('mfa.localHelper')}</strong>
                  <span>{t('mfa.localHelperDescription')}</span>
                  <code>node scripts/totp-code.mjs {setup.secret}</code>
                </div>
              )}
              <label htmlFor="totp-confirm-code">{t('auth.totpCode')}</label>
              <div className="search-row">
                <input
                  id="totp-confirm-code"
                  className="input"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  value={code}
                  disabled={busy}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
                />
                <button className="btn" type="button" disabled={busy || !/^\d{6}$/.test(code)} onClick={confirm}>
                  {busy && <span className="spinner" />}{t('mfa.enable')}
                </button>
              </div>
              <button className="link-button" type="button" disabled={busy}
                onClick={() => { setSetup(undefined); setCode(''); }}>
                {t('mfa.cancelSetup')}
              </button>
            </div>
          )}

          {status?.enabled && !setup && (
            <div className="security-summary">
              <div className="security-status-line">
                <span className="status-pill completed">{t('mfa.on')}</span>
                <span>{t('mfa.enrolled')} {status.enrolledAt ? new Date(status.enrolledAt).toLocaleString() : ''}</span>
              </div>
              <p>{t('mfa.remaining')} <strong>{remaining}</strong></p>
              <label htmlFor="totp-manage-code">{t('auth.totpCode')}</label>
              <input
                id="totp-manage-code"
                className="input"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                value={code}
                disabled={busy}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
              />
              <div className="security-actions">
                <button className="secondary-btn" type="button" disabled={busy || !/^\d{6}$/.test(code)}
                  onClick={regenerate}>
                  {t('mfa.regenerate')}
                </button>
                <button className="danger-btn" type="button" disabled={busy || !/^\d{6}$/.test(code)}
                  onClick={disable}>
                  {t('mfa.disable')}
                </button>
              </div>
            </div>
          )}

          {recoveryCodes.length > 0 && (
            <div className="recovery-codes" aria-labelledby="recovery-codes-title">
              <h3 id="recovery-codes-title">{t('mfa.recoveryTitle')}</h3>
              <p>{t('mfa.recoveryIntro')}</p>
              <ul>
                {recoveryCodes.map((recoveryCode) => <li key={recoveryCode}><code>{recoveryCode}</code></li>)}
              </ul>
              <button className="secondary-btn" type="button" disabled={busy} onClick={copyCodes}>
                {t('mfa.copyCodes')}
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
