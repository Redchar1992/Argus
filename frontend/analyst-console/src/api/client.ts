import { bffRequest } from './bff';
import type { Investigation, SubmitResponse } from '../types/investigation';
import { getDemoInvestigation, submitDemoInvestigation } from '../demo/staticInvestigations';

const STATIC_DEMO = import.meta.env.VITE_STATIC_DEMO === 'true';

export async function submitInvestigation(address: string): Promise<SubmitResponse> {
  if (STATIC_DEMO) return submitDemoInvestigation(address);
  return bffRequest<SubmitResponse>('/bff/api/investigations', {
    method: 'POST',
    csrf: true,
    notifySessionExpiry: true,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ address }),
  });
}

export async function getInvestigation(id: string): Promise<Investigation> {
  if (STATIC_DEMO) return getDemoInvestigation(id);
  return bffRequest<Investigation>(`/bff/api/investigations/${encodeURIComponent(id)}`, {
    notifySessionExpiry: true,
  });
}
