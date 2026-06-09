import { useEffect, useRef, useState } from 'react';
import { getInvestigation, submitInvestigation } from '../api/client';
import type { Investigation } from '../types/investigation';
import { AgentTimeline } from '../components/AgentTimeline';
import { DecisionPanel } from '../components/DecisionPanel';
import { Hero, HowItWorks } from '../components/Hero';
import { Hint } from '../components/Hint';
import { useI18n } from '../i18n';

const REPO = 'https://github.com/Redchar1992/argus';

// Pre-seeded demo wallets (match infra/screening-tools seed data).
const DEMO_WALLETS = [
  { value: '0xbadc0de000000000000000000000000000000bad', short: '0xbadc0de…', noteKey: 'demo.block', verdict: 'BLOCK', vclass: 'v-block' },
  { value: '0xc0ffee00000000000000000000000000000c0ffee', short: '0xc0ffee…', noteKey: 'demo.mixer', verdict: 'REVIEW', vclass: 'v-review' },
  { value: '0xdeadbeef0000000000000000000000000deadbeef', short: '0xdeadbeef…', noteKey: 'demo.structuring', verdict: 'REVIEW', vclass: 'v-review' },
  { value: '0xc1ean000000000000000000000000000000c1ean', short: '0xc1ean…', noteKey: 'demo.clean', verdict: 'CLEAR', vclass: 'v-clear' },
] as const;

export function InvestigatePage() {
  const { lang, setLang, t } = useI18n();
  const [address, setAddress] = useState('');
  const [inv, setInv] = useState<Investigation | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);

  useEffect(() => () => stopPolling(), []);

  function stopPolling() {
    if (pollRef.current) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }

  async function poll(id: string) {
    try {
      const data = await getInvestigation(id);
      setInv(data);
      if (data.status !== 'RUNNING') {
        stopPolling();
      }
    } catch (e) {
      setError((e as Error).message);
      stopPolling();
    }
  }

  async function onSubmit() {
    if (!address.trim()) {
      setError(t('search.empty'));
      return;
    }
    setError(null);
    setInv(null);
    setSubmitting(true);
    stopPolling();
    try {
      const { investigationId } = await submitInvestigation(address.trim());
      // Poll the orchestrator for the live reasoning + tool-call timeline.
      await poll(investigationId);
      pollRef.current = window.setInterval(() => poll(investigationId), 700);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  const running = inv?.status === 'RUNNING';
  const statusKey = inv ? inv.status.toLowerCase() : '';

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">Argus</span>
          <span className="tagline">{t('tagline')}</span>
        </div>
        <div className="topbar-right">
          <button className="lang-toggle" onClick={() => setLang(lang === 'en' ? 'zh' : 'en')}>
            {lang === 'en' ? '繁中' : 'EN'}
          </button>
        </div>
      </header>

      <Hero />
      <HowItWorks />

      <div className="subbar">
        <span className="chip">agentic · auditable · self-hosted LLM</span>
        <span className="dot">·</span>
        <a className="link" href={REPO} target="_blank" rel="noreferrer">
          GitHub ↗
        </a>
        <span className="subbar-note">
          plan → act → observe → decide
          <Hint text={t('hint.loop')} />
        </span>
      </div>

      <section className="section" id="investigate">
        <div className="section-title">{t('search.title')}</div>
        <div className="panel">
          <div className="search-row">
            <input
              className="input"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && onSubmit()}
              placeholder={t('search.placeholder')}
            />
            <button className="btn" disabled={submitting} onClick={onSubmit}>
              {submitting && <span className="spinner" />}
              {t('search.button')}
            </button>
          </div>
          <div style={{ marginTop: 12, fontSize: 12, color: 'var(--muted)' }}>
            {t('search.demoHint')}
          </div>
          <div className="demo-tags">
            {DEMO_WALLETS.map((w) => (
              <span key={w.value} className="demo-tag" onClick={() => setAddress(w.value)}>
                {w.short} — {t(w.noteKey)} <span className={w.vclass}>({w.verdict})</span>
              </span>
            ))}
          </div>
        </div>
      </section>

      {error && <div className="alert error">{error}</div>}

      {inv && (
        <div className="work-grid">
          <div className="panel">
            <div className="card-head">
              <h3>{t('tl.title')}</h3>
              <Hint text={t('hint.loop')} />
              {running && <span className="spinner" />}
              <span className={`status-pill ${statusKey}`}>{inv.status}</span>
              <span className="provider" title={t('hint.provider')}>
                {inv.llmProvider}
              </span>
            </div>
            <div className="subject-line">
              {t('tl.subject')}: <code>{inv.subjectAddress}</code>
            </div>
            <AgentTimeline steps={inv.steps} running={!!running} />
          </div>

          <div>
            <DecisionPanel inv={inv} />
            {running && (
              <div className="alert info">
                <strong>{t('inprogress.title')}</strong>
                <div style={{ marginTop: 4 }}>{t('inprogress.desc')}</div>
              </div>
            )}
          </div>
        </div>
      )}

      <footer className="foot">
        <p>{t('footer.note')}</p>
        <p className="foot-links">
          <a href={REPO} target="_blank" rel="noreferrer">
            {t('footer.source')}
          </a>
        </p>
      </footer>
    </div>
  );
}
