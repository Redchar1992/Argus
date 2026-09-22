import { useEffect, useState } from 'react';
import type { Investigation } from '../types/investigation';

type ReviewAction = 'CLEAR' | 'BLOCK' | 'REQUEST_INFO';

interface ReviewGateProps {
  inv: Investigation;
  onChange: (next: Investigation) => void;
}

export function ReviewGate({ inv, onChange }: ReviewGateProps) {
  const [note, setNote] = useState(inv.reviewNote ?? '');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setNote(inv.reviewNote ?? '');
    setError(null);
  }, [inv.id, inv.reviewNote]);

  if (inv.status !== 'COMPLETED' || inv.governance?.state !== 'HUMAN_REVIEW') return null;

  const status = inv.reviewStatus ?? 'PENDING_REVIEW';
  const actionable = status !== 'RESOLVED';

  function act(action: ReviewAction) {
    if ((action === 'BLOCK' || action === 'REQUEST_INFO') && !note.trim()) {
      setError('Add a short evidence note before this action.');
      return;
    }
    setError(null);
    onChange({
      ...inv,
      reviewStatus: action === 'REQUEST_INFO' ? 'NEEDS_INFO' : 'RESOLVED',
      reviewDecision: action === 'REQUEST_INFO' ? null : action,
      reviewNote: note.trim() || null,
      transactionState: action === 'REQUEST_INFO' ? 'AWAITING_REVIEW' : 'SETTLED',
    });
  }

  return (
    <div className="panel review-gate">
      <div className="card-head">
        <h3>Human review gate</h3>
        <span className={`review-status ${status.toLowerCase()}`}>{status}</span>
      </div>
      <p className="review-copy">
        The agent has collected evidence but cannot close this case automatically. This Pages action is a
        deterministic local fixture; the live equivalent is persisted by case-service and audited.
      </p>
      {actionable ? (
        <>
          <textarea
            className="review-note"
            value={note}
            onChange={(event) => setNote(event.target.value)}
            placeholder="Evidence or rationale for the intervention"
            maxLength={4000}
            rows={4}
          />
          {error && <div className="review-error">{error}</div>}
          <div className="review-actions">
            <button className="secondary-btn" onClick={() => act('REQUEST_INFO')}>Request evidence</button>
            <button className="danger-btn" onClick={() => act('BLOCK')}>Confirm BLOCK</button>
            <button className="clear-btn" onClick={() => act('CLEAR')}>Mark CLEAR</button>
          </div>
        </>
      ) : (
        <div className="review-resolved">
          Human decision <strong>{inv.reviewDecision}</strong> recorded locally with the evidence note.
        </div>
      )}
    </div>
  );
}
