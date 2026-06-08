import axios from 'axios';
import type { Investigation, SubmitResponse } from '../types/investigation';

// Default to the orchestrator directly for standalone dev; point at the gateway
// (http://localhost:8080) in an integrated deployment via VITE_API_BASE.
const baseURL = import.meta.env.VITE_API_BASE ?? 'http://localhost:8082';

const http = axios.create({ baseURL, timeout: 15000 });

export async function submitInvestigation(address: string): Promise<SubmitResponse> {
  const { data } = await http.post<SubmitResponse>('/api/investigations', { address });
  return data;
}

export async function getInvestigation(id: string): Promise<Investigation> {
  const { data } = await http.get<Investigation>(`/api/investigations/${id}`);
  return data;
}
