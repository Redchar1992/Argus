import axios from 'axios';
import type { AuditEntry, CaseView, Policy, ToolStatus } from '../types';

// case-service (policies, audit, cases) and tools-service (tool catalog) base URLs.
// In an integrated deployment both sit behind the gateway; for standalone dev they
// are separate ports.
const caseBase = import.meta.env.VITE_API_BASE ?? 'http://localhost:8084';
const toolsBase = import.meta.env.VITE_TOOLS_BASE ?? 'http://localhost:8083';

const caseHttp = axios.create({ baseURL: caseBase, timeout: 10000 });
const toolsHttp = axios.create({ baseURL: toolsBase, timeout: 10000 });

export const api = {
  async getPolicies(): Promise<Policy[]> {
    return (await caseHttp.get<Policy[]>('/api/policies')).data;
  },
  async updatePolicy(key: string, value: number, actor: string): Promise<Policy> {
    return (await caseHttp.put<Policy>(`/api/policies/${key}`, { value, actor })).data;
  },
  async getAudit(): Promise<AuditEntry[]> {
    return (await caseHttp.get<AuditEntry[]>('/api/audit')).data;
  },
  async getCases(): Promise<CaseView[]> {
    return (await caseHttp.get<CaseView[]>('/api/cases')).data;
  },
  async getTools(): Promise<ToolStatus[]> {
    return (await toolsHttp.get<ToolStatus[]>('/api/tools/catalog')).data;
  },
  async setTool(toolId: string, enabled: boolean): Promise<ToolStatus> {
    return (await toolsHttp.put<ToolStatus>(`/api/tools/catalog/${toolId}`, { enabled })).data;
  },
};
