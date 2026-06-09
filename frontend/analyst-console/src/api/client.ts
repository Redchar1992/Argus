import axios from 'axios';
import type { Investigation, SubmitResponse } from '../types/investigation';

// Default to the orchestrator directly for standalone dev; point at the gateway
// (http://localhost:8080) in an integrated deployment via VITE_API_BASE.
const baseURL = import.meta.env.VITE_API_BASE ?? 'http://localhost:8082';

const http = axios.create({ baseURL, timeout: 15000 });

// The backend services are now JWT-protected (per-service RBAC). Supply a bearer token
// via VITE_API_TOKEN (e.g. one minted from POST /api/auth/login) so the console can call
// the secured endpoints. Left unset in pure-UI dev; required against the live backend.
const apiToken = import.meta.env.VITE_API_TOKEN as string | undefined;
http.interceptors.request.use((config) => {
  if (apiToken) {
    config.headers.Authorization = `Bearer ${apiToken}`;
  }
  return config;
});

export async function submitInvestigation(address: string): Promise<SubmitResponse> {
  const { data } = await http.post<SubmitResponse>('/api/investigations', { address });
  return data;
}

export async function getInvestigation(id: string): Promise<Investigation> {
  const { data } = await http.get<Investigation>(`/api/investigations/${id}`);
  return data;
}
