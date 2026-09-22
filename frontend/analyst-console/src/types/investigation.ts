export interface AgentStep {
  index: number;
  phase: string;
  thought: string;
  toolName: string | null;
  toolArgs: unknown;
  observation: unknown;
  note: string | null;
  timestamp: string | null;
  durationMs: number | null;
}

export interface Investigation {
  id: string;
  subjectAddress: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  llmProvider: string;
  maxSteps: number;
  decision: string | null;
  riskScore: number | null;
  riskBand: string | null;
  riskFactors: string[];
  summary: string | null;
  error: string | null;
  requestedBy: string | null;
  createdAt: string | null;
  completedAt: string | null;
  steps: AgentStep[];
  /** Optional governance metadata supplied by the deterministic Pages fixture. */
  governance?: {
    state: 'AUTO_APPROVED' | 'HUMAN_REVIEW';
    reason: string;
    policyId: string;
    policyVersion: string;
    maxCostUnits: number;
    maxSteps: number;
  };
  /** Human-gate projection used by the deterministic demo and the case-service contract. */
  reviewStatus?: 'AUTO_APPROVED' | 'PENDING_REVIEW' | 'NEEDS_INFO' | 'RESOLVED';
  reviewDecision?: 'CLEAR' | 'BLOCK' | null;
  reviewNote?: string | null;
  transactionState?: 'DETECTED' | 'SCREENED' | 'AWAITING_REVIEW' | 'SETTLED';
}

export interface SubmitResponse {
  investigationId: string;
  status: string;
}
