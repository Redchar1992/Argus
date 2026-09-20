import { beforeEach, describe, expect, it } from 'vitest';
import {
  getDemoInvestigation,
  resetStaticInvestigationsForTest,
  submitDemoInvestigation,
} from './staticInvestigations';

describe('GitHub Pages static investigation demo', () => {
  beforeEach(() => resetStaticInvestigationsForTest());

  it('progresses from a running tool trace to a completed policy decision', async () => {
    const { investigationId } = await submitDemoInvestigation('0xc0ffee00000000000000000000000000000c0ffee');
    const first = await getDemoInvestigation(investigationId);
    const second = await getDemoInvestigation(investigationId);
    const completed = await getDemoInvestigation(investigationId);

    expect(first.status).toBe('RUNNING');
    expect(second.steps.length).toBeGreaterThan(first.steps.length);
    expect(completed).toMatchObject({ status: 'COMPLETED', decision: 'REVIEW', riskScore: 35 });
    expect(completed.steps.at(-1)?.phase).toBe('FINISH');
    expect(completed.governance).toMatchObject({ state: 'HUMAN_REVIEW', maxSteps: 6, maxCostUnits: 6 });
    expect(completed.transactionState).toBe('AWAITING_REVIEW');
  });

  it('never infers CLEAR for an address outside the fixture set', async () => {
    const { investigationId } = await submitDemoInvestigation('0x1234');
    await getDemoInvestigation(investigationId);
    await getDemoInvestigation(investigationId);
    const completed = await getDemoInvestigation(investigationId);

    expect(completed.decision).toBe('REVIEW');
    expect(completed.riskFactors).toContain('Unknown address in the static fixture set.');
  });

  it('keeps the clean fixture efficient while requiring sanctions and policy evidence', async () => {
    const { investigationId } = await submitDemoInvestigation('0xc1ean000000000000000000000000000000c1ean');
    await getDemoInvestigation(investigationId);
    await getDemoInvestigation(investigationId);
    const completed = await getDemoInvestigation(investigationId);

    expect(completed.decision).toBe('CLEAR');
    expect(completed.steps.map((item) => item.toolName)).toEqual([
      'sanctions_screen', 'address_profile', 'risk_rules', null,
    ]);
  });
});
