import type { AuditEntry, CaseView, Policy, ReviewAction, ToolStatus } from '../types';

const now = '2026-09-22T15:00:00Z';

let cases: CaseView[] = [
  {
    id: 'case-pages-review-001',
    subjectAddress: '0xc0ffee00000000000000000000000000000c0ffee',
    decision: 'REVIEW',
    riskScore: 35,
    riskBand: 'MEDIUM',
    summary: 'One-hop exposure to a labelled mixer. Human review is required before the case can proceed.',
    riskFactorsJson: JSON.stringify([
      'One-hop mixer exposure.',
      'Elevated transaction-graph risk requires human review.',
    ]),
    createdBy: 'pages-agent-fixture',
    createdAt: now,
    reviewStatus: 'PENDING_REVIEW',
    reviewDecision: null,
    reviewNote: null,
    reviewedBy: null,
    reviewedAt: null,
  },
  {
    id: 'case-pages-review-002',
    subjectAddress: '0xdeadbeef0000000000000000000000000deadbeef',
    decision: 'REVIEW',
    riskScore: 35,
    riskBand: 'MEDIUM',
    summary: 'Two-hop flagged exposure and a structuring pattern require additional evidence.',
    riskFactorsJson: JSON.stringify([
      'Two-hop flagged exposure.',
      'Repeated transfers match a structuring pattern.',
    ]),
    createdBy: 'pages-agent-fixture',
    createdAt: '2026-09-22T14:58:00Z',
    reviewStatus: 'NEEDS_INFO',
    reviewDecision: null,
    reviewNote: 'Request counterparty origin and transfer rationale.',
    reviewedBy: 'demo-reviewer',
    reviewedAt: '2026-09-22T14:59:00Z',
  },
  {
    id: 'case-pages-auto-003',
    subjectAddress: '0xbadc0de000000000000000000000000000000bad',
    decision: 'BLOCK',
    riskScore: 60,
    riskBand: 'HIGH',
    summary: 'Direct sanctions match. Deterministic policy mandates automatic BLOCK.',
    riskFactorsJson: JSON.stringify(['Direct sanctions match.']),
    createdBy: 'pages-agent-fixture',
    createdAt: '2026-09-22T14:55:00Z',
    reviewStatus: 'AUTO_APPROVED',
    reviewDecision: null,
    reviewNote: null,
    reviewedBy: null,
    reviewedAt: null,
  },
];

let tools: ToolStatus[] = [
  { toolId: 'sanctions_screen', description: 'Screen addresses against the local sanctions provider.', enabled: true },
  { toolId: 'address_profile', description: 'Read a bounded profile for the subject address.', enabled: true },
  { toolId: 'trace_transactions', description: 'Trace labelled counterparties with bounded graph depth.', enabled: true },
  { toolId: 'risk_rules', description: 'Apply deterministic risk thresholds to collected evidence.', enabled: true },
];

let policies: Policy[] = [
  { key: 'blockThreshold', description: 'Score at or above this value produces BLOCK.', value: 60 },
  { key: 'reviewThreshold', description: 'Score at or above this value produces REVIEW.', value: 30 },
  { key: 'maxSteps', description: 'Maximum number of agent steps per investigation.', value: 6 },
];

let audit: AuditEntry[] = [
  {
    id: 1003,
    actor: 'pages-agent-fixture',
    action: 'CASE_PERSISTED',
    target: 'case-pages-review-001',
    detail: 'decision=REVIEW score=35',
    createdAt: now,
  },
  {
    id: 1002,
    actor: 'demo-reviewer',
    action: 'CASE_REVIEWED',
    target: 'case-pages-review-002',
    detail: 'action=REQUEST_INFO note=Request counterparty origin and transfer rationale.',
    createdAt: '2026-09-22T14:59:00Z',
  },
];

function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

export function getDemoCases(): CaseView[] {
  return copy(cases);
}

export function reviewDemoCase(id: string, action: ReviewAction, note?: string): CaseView {
  const record = cases.find((item) => item.id === id);
  if (!record) throw new Error(`No such demo case: ${id}`);
  if (record.decision !== 'REVIEW' || !['PENDING_REVIEW', 'NEEDS_INFO'].includes(record.reviewStatus)) {
    throw new Error('This demo case is no longer waiting for human review.');
  }
  if (action !== 'CLEAR' && !note?.trim()) {
    throw new Error('A note is required for BLOCK or REQUEST_INFO.');
  }

  const reviewedAt = new Date().toISOString();
  record.reviewStatus = action === 'REQUEST_INFO' ? 'NEEDS_INFO' : 'RESOLVED';
  record.reviewDecision = action === 'REQUEST_INFO' ? null : action;
  record.reviewNote = note?.trim() || null;
  record.reviewedBy = 'pages-demo-reviewer';
  record.reviewedAt = reviewedAt;
  audit.unshift({
    id: Math.max(...audit.map((item) => item.id), 0) + 1,
    actor: 'pages-demo-reviewer',
    action: 'CASE_REVIEWED',
    target: id,
    detail: `action=${action}${record.reviewNote ? ` note=${record.reviewNote}` : ''}`,
    createdAt: reviewedAt,
  });
  return copy(record);
}

export function getDemoPolicies(): Policy[] {
  return copy(policies);
}

export function updateDemoPolicy(key: string, value: number): Policy {
  const policy = policies.find((item) => item.key === key);
  if (!policy) throw new Error(`No such demo policy: ${key}`);
  const old = policy.value;
  policy.value = value;
  audit.unshift({
    id: Math.max(...audit.map((item) => item.id), 0) + 1,
    actor: 'pages-demo-admin',
    action: 'POLICY_UPDATED',
    target: key,
    detail: `value ${old} -> ${value}`,
    createdAt: new Date().toISOString(),
  });
  return copy(policy);
}

export function getDemoAudit(): AuditEntry[] {
  return copy(audit);
}

export function getDemoTools(): ToolStatus[] {
  return copy(tools);
}

export function setDemoTool(toolId: string, enabled: boolean): ToolStatus {
  const tool = tools.find((item) => item.toolId === toolId);
  if (!tool) throw new Error(`No such demo tool: ${toolId}`);
  tool.enabled = enabled;
  audit.unshift({
    id: Math.max(...audit.map((item) => item.id), 0) + 1,
    actor: 'pages-demo-admin',
    action: 'TOOL_UPDATED',
    target: toolId,
    detail: `enabled=${enabled}`,
    createdAt: new Date().toISOString(),
  });
  return copy(tool);
}
