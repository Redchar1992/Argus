import { bffRequest } from './bff';
import type { Investigation, SubmitResponse } from '../types/investigation';

export async function submitInvestigation(address: string): Promise<SubmitResponse> {
  return bffRequest<SubmitResponse>('/bff/api/investigations', {
    method: 'POST',
    csrf: true,
    notifySessionExpiry: true,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ address }),
  });
}

export async function getInvestigation(id: string): Promise<Investigation> {
  return bffRequest<Investigation>(`/bff/api/investigations/${encodeURIComponent(id)}`, {
    notifySessionExpiry: true,
  });
}
