import type { AgentStep, Investigation, SubmitResponse } from '../types/investigation';

interface DemoRun {
  address: string;
  polls: number;
  createdAt: string;
}

interface DemoProfile {
  decision: 'CLEAR' | 'REVIEW' | 'BLOCK';
  riskScore: number;
  riskBand: 'MINIMAL' | 'MEDIUM' | 'HIGH';
  riskFactors: string[];
  sanctioned: boolean;
  exposure: string;
  skipTrace?: boolean;
}

const runs = new Map<string, DemoRun>();
let nextId = 1;

function profileFor(address: string): DemoProfile {
  const normalized = address.toLowerCase();
  if (normalized.startsWith('0x098b716b')) {
    return {
      decision: 'BLOCK', riskScore: 100, riskBand: 'HIGH', sanctioned: true,
      exposure: 'Direct match in the bundled OFAC SDN snapshot.',
      riskFactors: ['Direct match in the bundled OFAC SDN snapshot.', 'Sanctions policy mandates automatic BLOCK.'],
    };
  }
  if (normalized.startsWith('0xbadc0de')) {
    return {
      decision: 'BLOCK', riskScore: 60, riskBand: 'HIGH', sanctioned: true,
      exposure: 'Direct match in the local demo watchlist.',
      riskFactors: ['Direct sanctions match.', 'Deterministic policy mandates automatic BLOCK.'],
    };
  }
  if (normalized.startsWith('0xc0ffee')) {
    return {
      decision: 'REVIEW', riskScore: 35, riskBand: 'MEDIUM', sanctioned: false,
      exposure: 'One-hop exposure to a labelled mixer.',
      riskFactors: ['One-hop mixer exposure.', 'Elevated transaction-graph risk requires human review.'],
    };
  }
  if (normalized.startsWith('0xdeadbeef')) {
    return {
      decision: 'REVIEW', riskScore: 35, riskBand: 'MEDIUM', sanctioned: false,
      exposure: 'Two-hop flagged exposure and a structuring pattern.',
      riskFactors: ['Two-hop flagged exposure.', 'Repeated transfers match a structuring pattern.'],
    };
  }
  if (normalized.startsWith('0xc1ean')) {
    return {
      decision: 'CLEAR', riskScore: 0, riskBand: 'MINIMAL', sanctioned: false,
      exposure: 'No disqualifying exposure in the deterministic fixture.', skipTrace: true,
      riskFactors: ['Required sanctions and policy checks completed with no disqualifying evidence.'],
    };
  }
  return {
    decision: 'REVIEW', riskScore: 30, riskBand: 'MEDIUM', sanctioned: false,
    exposure: 'The static demo has no fixture evidence for this address.',
    riskFactors: ['Unknown address in the static fixture set.', 'Route to a human analyst rather than inferring a clean result.'],
  };
}

function step(
  index: number,
  thought: string,
  toolName: string,
  toolArgs: unknown,
  observation: unknown,
  durationMs: number,
): AgentStep {
  return {
    index, phase: 'ACT', thought, toolName, toolArgs, observation,
    note: null, timestamp: null, durationMs,
  };
}

function stepsFor(address: string, profile: DemoProfile): AgentStep[] {
  const steps: AgentStep[] = [
    step(1, 'Screen the subject before any lower-priority analysis.', 'sanctions_screen',
      { addresses: [address] },
      { match: profile.sanctioned, source: profile.sanctioned ? 'demo-watchlist' : 'none', evidenceComplete: true }, 41),
    step(2, 'Collect a compact address profile to calibrate the investigation path.', 'address_profile',
      { address }, { accountAgeDays: 842, transactionCount: profile.decision === 'CLEAR' ? 18 : 426 }, 33),
  ];
  if (!profile.skipTrace) {
    steps.push(step(3, 'Trace labelled counterparties because the profile warrants graph analysis.', 'trace_transactions',
      { address, maxHops: 2 }, { finding: profile.exposure, evidenceComplete: true }, 87));
  }
  steps.push(step(steps.length + 1, 'Apply deterministic policy thresholds to the collected evidence.', 'risk_rules',
    { address }, { riskScore: profile.riskScore, riskBand: profile.riskBand, firedRules: profile.riskFactors }, 28));
  steps.push({
    index: steps.length + 1,
    phase: 'FINISH',
    thought: `Required evidence is complete. Close the investigation as ${profile.decision}.`,
    toolName: null,
    toolArgs: null,
    observation: null,
    note: `decision=${profile.decision}`,
    timestamp: null,
    durationMs: 12,
  });
  return steps;
}

export async function submitDemoInvestigation(address: string): Promise<SubmitResponse> {
  const id = `pages-demo-${nextId++}`;
  runs.set(id, { address, polls: 0, createdAt: new Date().toISOString() });
  return { investigationId: id, status: 'RUNNING' };
}

export async function getDemoInvestigation(id: string): Promise<Investigation> {
  const run = runs.get(id);
  if (!run) throw new Error('Static demo investigation not found. Start a new investigation.');
  run.polls += 1;
  const profile = profileFor(run.address);
  const allSteps = stepsFor(run.address, profile);
  const completed = run.polls >= 3;
  const visibleSteps = completed ? allSteps : allSteps.slice(0, Math.min(allSteps.length - 1, run.polls * 2));

  return {
    id,
    subjectAddress: run.address,
    status: completed ? 'COMPLETED' : 'RUNNING',
    llmProvider: 'pages-fixture (deterministic)',
    maxSteps: 6,
    decision: completed ? profile.decision : null,
    riskScore: completed ? profile.riskScore : null,
    riskBand: completed ? profile.riskBand : null,
    riskFactors: completed ? profile.riskFactors : [],
    summary: completed
      ? `Static demo completed ${allSteps.length} auditable steps and returned ${profile.decision} with risk score ${profile.riskScore}.`
      : null,
    error: null,
    requestedBy: 'pages-demo',
    createdAt: run.createdAt,
    completedAt: completed ? new Date().toISOString() : null,
    steps: visibleSteps,
    governance: {
      state: profile.decision === 'REVIEW' ? 'HUMAN_REVIEW' : 'AUTO_APPROVED',
      reason: profile.decision === 'REVIEW'
        ? 'Deterministic policy requires a human decision before any downstream action.'
        : 'Required evidence is complete and the deterministic policy permits an automated close.',
      policyId: 'wallet-screening-default',
      policyVersion: '2026-08-18',
      maxCostUnits: 6,
      maxSteps: 6,
    },
    reviewStatus: profile.decision === 'REVIEW' ? 'PENDING_REVIEW' : 'AUTO_APPROVED',
    reviewDecision: null,
    reviewNote: null,
    transactionState: profile.decision === 'REVIEW' ? 'AWAITING_REVIEW' : 'SETTLED',
  };
}

export function exportDemoInvestigation(inv: Investigation): void {
  const evidence = {
    artifactType: 'argus-investigation-evidence',
    artifactVersion: 1,
    generatedBy: 'pages-fixture (deterministic)',
    ...inv,
  };
  const blob = new Blob([JSON.stringify(evidence, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${inv.id}-evidence.json`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function resetStaticInvestigationsForTest(): void {
  runs.clear();
  nextId = 1;
}
