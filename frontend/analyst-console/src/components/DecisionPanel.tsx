import type { Investigation } from '../types/investigation';
import { useI18n } from '../i18n';
import { Hint } from './Hint';

const VERDICT_CLASS: Record<string, string> = {
  CLEAR: 'clear',
  REVIEW: 'review',
  BLOCK: 'block',
};

const BAND_COLOR: Record<string, string> = {
  MINIMAL: 'var(--ok)',
  LOW: '#7bd88a',
  MEDIUM: 'var(--warn)',
  HIGH: 'var(--danger)',
};

/** Heuristic: classify a risk-factor line by severity for the colored dot. */
function factorSeverity(f: string): string {
  const s = f.toLowerCase();
  if (/(sanction|block|ofac|sdn|direct hit|illicit)/.test(s)) return 'high';
  if (/(mixer|exposure|structuring|elevated|review|hop)/.test(s)) return 'med';
  return '';
}

export function DecisionPanel({ inv }: { inv: Investigation }) {
  const { t } = useI18n();
  const running = inv.status === 'RUNNING';

  if (inv.status !== 'COMPLETED') {
    return (
      <div className="panel">
        <div className="card-head">
          <h3>{t('dec.title')}</h3>
          <Hint text={t('hint.verdict')} />
        </div>
        <p className="tl-waiting" style={{ marginTop: 8 }}>
          {running ? t('dec.pending') : inv.error || t('dec.pending')}
        </p>
      </div>
    );
  }

  const decision = inv.decision ?? '';
  const cls = VERDICT_CLASS[decision] ?? 'review';
  const score = inv.riskScore ?? 0;
  const fill = score >= 60 ? 'var(--danger)' : score >= 30 ? 'var(--warn)' : 'var(--ok)';
  const sub =
    decision === 'CLEAR'
      ? t('dec.clearSub')
      : decision === 'BLOCK'
        ? t('dec.blockSub')
        : t('dec.reviewSub');

  return (
    <div className="panel">
      <div className="card-head" style={{ marginBottom: 14 }}>
        <h3>{t('dec.title')}</h3>
        <Hint text={t('hint.verdict')} />
      </div>

      <div className="verdict">
        <span className={`verdict-badge ${cls}`}>{decision}</span>
        <div className="verdict-sub">{sub}</div>
      </div>

      <div className="score-wrap">
        <div className="score-top">
          <span className="kv-k">
            {t('dec.score')}
            <Hint text={t('hint.score')} />
          </span>
          <span className="score-num">{score}</span>
        </div>
        <div className="score-bar">
          <div className="score-fill" style={{ width: `${Math.min(100, Math.max(0, score))}%`, background: fill }} />
        </div>
        <div className="score-top" style={{ marginTop: 8, marginBottom: 0 }}>
          <span className="kv-k">
            {t('dec.band')}
            <Hint text={t('hint.score')} />
          </span>
          <span
            className="score-band"
            style={{ color: BAND_COLOR[inv.riskBand ?? ''] ?? 'var(--muted)', borderColor: 'var(--line)' }}
          >
            {inv.riskBand ?? '—'}
          </span>
        </div>
      </div>

      {inv.riskFactors?.length > 0 && (
        <div style={{ marginBottom: 4 }}>
          <div className="factors-title">
            {t('dec.factors')}
            <Hint text={t('hint.exposure')} />
          </div>
          <ul className="factors">
            {inv.riskFactors.map((f, i) => (
              <li className={`factor ${factorSeverity(f)}`} key={i}>
                {f}
              </li>
            ))}
          </ul>
        </div>
      )}

      {inv.summary && <div className="summary-box">{inv.summary}</div>}

      <div className="summary-box" style={{ paddingTop: 12 }}>
        {t('dec.decidedBy')}: <span style={{ color: 'var(--text)' }}>{inv.llmProvider}</span>
        <Hint text={t('hint.provider')} />
      </div>
    </div>
  );
}
