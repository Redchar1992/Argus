import axios from 'axios';
import {
  getDemoAudit,
  getDemoCases,
  getDemoPolicies,
  getDemoTools,
  reviewDemoCase,
  setDemoTool,
  updateDemoPolicy,
} from '../demo/staticData';
import type { AuditEntry, CaseView, Policy, ReviewAction, ToolStatus } from '../types';

// case-service (policies, audit, cases) and tools-service (tool catalog) base URLs.
// In an integrated deployment both sit behind the gateway; for standalone dev they
// are separate ports.
const caseBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8084';
const toolsBase = import.meta.env.VITE_TOOLS_BASE ?? 'http://localhost:8083';
const STATIC_DEMO = import.meta.env.VITE_STATIC_DEMO === 'true';

const caseHttp = axios.create({ baseURL: caseBase, timeout: 10000 });
const toolsHttp = axios.create({ baseURL: toolsBase, timeout: 10000 });

// The case/tools services are now JWT-protected, and policy/tool mutation + audit are
// ADMIN-only. Supply an ADMIN bearer token via VITE_API_TOKEN (mint one from
// POST /api/auth/login as the admin user) so the admin console can call these endpoints.
const apiToken = import.meta.env.VITE_API_TOKEN as string | undefined;
const attachToken = (config: import('axios').InternalAxiosRequestConfig) => {
  if (apiToken) {
    config.headers.Authorization = `Bearer ${apiToken}`;
  }
  return config;
};
caseHttp.interceptors.request.use(attachToken);
toolsHttp.interceptors.request.use(attachToken);

export const api = {
  async getPolicies(): Promise<Policy[]> {
    if (STATIC_DEMO) return getDemoPolicies();
    return (await caseHttp.get<Policy[]>('/api/policies')).data;
  },
  async updatePolicy(key: string, value: number, actor: string): Promise<Policy> {
    if (STATIC_DEMO) return updateDemoPolicy(key, value);
    return (await caseHttp.put<Policy>(`/api/policies/${key}`, { value, actor })).data;
  },
  async getAudit(): Promise<AuditEntry[]> {
    if (STATIC_DEMO) return getDemoAudit();
    return (await caseHttp.get<AuditEntry[]>('/api/audit')).data;
  },
  async getCases(): Promise<CaseView[]> {
    if (STATIC_DEMO) return getDemoCases();
    return (await caseHttp.get<CaseView[]>('/api/cases')).data;
  },
  async reviewCase(id: string, action: ReviewAction, note?: string): Promise<CaseView> {
    if (STATIC_DEMO) return reviewDemoCase(id, action, note);
    return (await caseHttp.post<CaseView>(`/api/cases/${id}/review`, { action, note })).data;
  },
  async getTools(): Promise<ToolStatus[]> {
    if (STATIC_DEMO) return getDemoTools();
    return (await toolsHttp.get<ToolStatus[]>('/api/tools/catalog')).data;
  },
  async setTool(toolId: string, enabled: boolean): Promise<ToolStatus> {
    if (STATIC_DEMO) return setDemoTool(toolId, enabled);
    return (await toolsHttp.put<ToolStatus>(`/api/tools/catalog/${toolId}`, { enabled })).data;
  },
};
