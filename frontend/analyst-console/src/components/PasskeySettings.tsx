import { useState } from 'react';
import {
  deletePasskey,
  listPasskeys,
  registerPasskey,
  type PasskeyView,
} from '../api/passkeys';
import { useI18n } from '../i18n';

export function PasskeySettings() {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [passkeys, setPasskeys] = useState<PasskeyView[]>([]);
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const load = async () => {
    setBusy(true);
    setError(undefined);
    try {
      setPasskeys(await listPasskeys());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('passkeys.failed'));
    } finally {
      setBusy(false);
    }
  };

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next) void load();
  };

  const create = async () => {
    setBusy(true);
    setError(undefined);
    try {
      await registerPasskey(label);
      setLabel('');
      setPasskeys(await listPasskeys());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('passkeys.failed'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (credentialId: string) => {
    setBusy(true);
    setError(undefined);
    try {
      await deletePasskey(credentialId);
      setPasskeys((current) => current.filter((passkey) => passkey.credentialId !== credentialId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('passkeys.failed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="passkey-settings" aria-labelledby={open ? 'passkey-settings-title' : undefined}>
      <button className="secondary-btn" type="button" onClick={toggle} aria-expanded={open}>
        {open ? t('passkeys.close') : t('passkeys.manage')}
      </button>
      {open && (
        <div className="panel passkey-panel">
          <div>
            <h2 id="passkey-settings-title">{t('passkeys.title')}</h2>
            <p>{t('passkeys.intro')}</p>
          </div>
          {error && <div className="alert error" role="alert">{error}</div>}
          <div className="passkey-create">
            <label htmlFor="passkey-label">{t('passkeys.label')}</label>
            <div className="search-row">
              <input
                id="passkey-label"
                className="input"
                value={label}
                maxLength={80}
                disabled={busy}
                placeholder={t('passkeys.placeholder')}
                onChange={(event) => setLabel(event.target.value)}
              />
              <button className="btn" type="button" disabled={busy} onClick={() => void create()}>
                {busy && <span className="spinner" />}
                {t('passkeys.add')}
              </button>
            </div>
          </div>
          {!busy && passkeys.length === 0 && <p className="passkey-empty">{t('passkeys.empty')}</p>}
          <ul className="passkey-list">
            {passkeys.map((passkey) => (
              <li key={passkey.credentialId}>
                <span>
                  <strong>{passkey.label}</strong>
                  <small>
                    {passkey.backedUp ? t('passkeys.synced') : t('passkeys.deviceBound')}
                    {' · '}{new Date(passkey.createdAt).toLocaleDateString()}
                  </small>
                </span>
                <button className="secondary-btn" type="button" disabled={busy}
                  aria-label={`${t('passkeys.remove')} ${passkey.label}`}
                  onClick={() => void remove(passkey.credentialId)}>
                  {t('passkeys.remove')}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
