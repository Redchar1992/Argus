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
}

export interface SubmitResponse {
  investigationId: string;
  status: string;
}
