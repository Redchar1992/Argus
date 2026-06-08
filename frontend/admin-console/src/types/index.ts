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
  createdBy: string;
  createdAt: string;
}
