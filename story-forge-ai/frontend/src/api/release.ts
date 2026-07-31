import type { EntityId } from '@/types'
import type {
  AiCreditLog,
  AiWallet,
  ExportFormat,
  ExportTask,
  FinalReportResponse,
  StoryRelease,
} from '@/types'
import { apiClient } from '@/utils/request'

export async function runFinalReview(storyId: EntityId): Promise<FinalReportResponse> {
  return apiClient.post<FinalReportResponse>(`/api/stories/${storyId}/final-reviews`)
}

export async function getLatestFinalReport(storyId: EntityId): Promise<FinalReportResponse> {
  return apiClient.get<FinalReportResponse>(`/api/stories/${storyId}/final-reviews/latest`)
}

export async function listFinalReports(storyId: EntityId): Promise<FinalReportResponse[]> {
  return apiClient.get<FinalReportResponse[]>(`/api/stories/${storyId}/final-reviews`)
}

export async function createRelease(storyId: EntityId, reportId?: EntityId): Promise<StoryRelease> {
  return apiClient.post<StoryRelease>(`/api/stories/${storyId}/releases`, reportId ? { reportId } : {})
}

export async function listReleases(storyId: EntityId): Promise<StoryRelease[]> {
  return apiClient.get<StoryRelease[]>(`/api/stories/${storyId}/releases`)
}

export async function createExport(
  storyId: EntityId,
  releaseId: EntityId,
  format: ExportFormat,
  includeReport = false,
): Promise<ExportTask> {
  return apiClient.post<ExportTask>(`/api/stories/${storyId}/exports`, {
    releaseId,
    format,
    includeReport,
  })
}

export async function listExports(storyId: EntityId): Promise<ExportTask[]> {
  return apiClient.get<ExportTask[]>(`/api/stories/${storyId}/exports`)
}

export async function getWallet(): Promise<AiWallet> {
  return apiClient.get<AiWallet>('/api/me/ai-wallet')
}

export async function getWalletLogs(): Promise<AiCreditLog[]> {
  return apiClient.get<AiCreditLog[]>('/api/me/ai-wallet/logs')
}
