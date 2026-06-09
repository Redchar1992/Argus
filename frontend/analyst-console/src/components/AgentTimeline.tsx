import type { AgentStep } from '../types/investigation';
import { useI18n } from '../i18n';

/** Compact glyphs per tool / phase — kept as inline text so the timeline stays
 * dependency-free and on-brand with the rest of the dark console. */
const TOOL_GLYPH: Record<string, string> = {
  sanctions_screen: '⚖',
  address_profile: '◉',
  trace_transactions: '⤳',
  risk_rules: '∑',
};

function glyphFor(step: AgentStep): string {
  if (step.phase === 'FINISH') return '✓';
  if (step.toolName && TOOL_GLYPH[step.toolName]) return TOOL_GLYPH[step.toolName];
  if (step.phase === 'PLAN') return '✦';
  return '•';
}

function pretty(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  return JSON.stringify(v, null, 2);
}

/** Whether this is the agent's currently-executing step (last step, not finished). */
function isLive(steps: AgentStep[], i: number, running: boolean): boolean {
  return running && i === steps.length - 1 && steps[i].phase !== 'FINISH';
}

export function AgentTimeline({ steps, running }: { steps: AgentStep[]; running: boolean }) {
  const { t } = useI18n();

  if (!steps.length) {
    return <div className="tl-waiting">{t('tl.waiting')}</div>;
  }

  return (
    <div className="tl">
      {steps.map((step, i) => {
        const finish = step.phase === 'FINISH';
        const live = isLive(steps, i, running);
        const hasArgs = step.toolArgs != null;
        const hasObs = step.observation != null;
        return (
          <div className="tl-step" key={step.index}>
            <div className={`tl-dot${finish ? ' finish' : ''}${live ? ' running' : ''}`}>
              {glyphFor(step)}
            </div>
            <div className="tl-head">
              <span className="tl-phase">{step.phase}</span>
              <span className="tl-index">#{step.index}</span>
              {step.toolName && <span className="tag tool">{step.toolName}</span>}
              {step.durationMs != null && <span className="tag dur">{step.durationMs} ms</span>}
            </div>
            {step.thought && <p className="tl-thought">{step.thought}</p>}
            {step.note && !step.thought && <p className="tl-thought">{step.note}</p>}
            {(hasArgs || hasObs) && (
              <details className="tl-io" open={finish}>
                <summary>{t('tl.io')}</summary>
                <div className="tl-io-body">
                  <div className="tl-io-grid">
                    {hasArgs && (
                      <div className="tl-io-block">
                        <div className="tl-io-label">{t('tl.args')}</div>
                        <pre>{pretty(step.toolArgs)}</pre>
                      </div>
                    )}
                    {hasObs && (
                      <div className="tl-io-block">
                        <div className="tl-io-label">{t('tl.obs')}</div>
                        <pre>{pretty(step.observation)}</pre>
                      </div>
                    )}
                  </div>
                </div>
              </details>
            )}
          </div>
        );
      })}
    </div>
  );
}
