export interface ToolStatus {
  toolId: string;
  description: string;
  enabled: boolean;
}

export interface Policy {
  key: string;
  description: string;
  value: number;
}

export interface AuditEntry {
  id: number;
  actor: string;
  action: string;
  target: string | null;
  detail: string | null;
  createdAt: string;
}

export interface CaseView {
  id: string;
  subjectAddress: string;
  decision: string;
  riskScore: number;
  riskBand: string;
  summary: string;
  riskFactorsJson: string | null;
  createdBy: string;
  createdAt: string;
  reviewStatus: 'AUTO_APPROVED' | 'PENDING_REVIEW' | 'NEEDS_INFO' | 'RESOLVED' | string;
  reviewDecision: 'CLEAR' | 'BLOCK' | null;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
}

export type ReviewAction = 'CLEAR' | 'BLOCK' | 'REQUEST_INFO';
