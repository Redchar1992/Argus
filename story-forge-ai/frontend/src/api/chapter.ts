import axios from 'axios'

import {
  normalizeChapterDetail,
  normalizeChapterList,
  normalizeChapterPlanEnvelope,
  normalizeChapterTask,
  normalizeChapterVersion,
  normalizeChapterVersions,
  normalizeRewriteProposal,
  normalizeRewriteProposals,
  normalizeVersionComparison,
} from '@/api/chapter-normalizers'
import type {
  ChapterDetail,
  ChapterListItem,
  ChapterPlanEnvelope,
  ChapterTask,
  ChapterVersion,
  ChapterVersionComparison,
  EntityId,
  RewriteProposal,
  RewriteSelectionInput,
} from '@/types'
import { apiClient } from '@/utils/request'

function numericPathValue(value: EntityId) {
  return typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value
}

function isNotFound(error: unknown) {
  return axios.isAxiosError(error) && error.response?.status === 404
}

export async function listStoryChapters(storyId: EntityId): Promise<ChapterListItem[]> {
  const response = await apiClient.get<unknown>(`/api/stories/${storyId}/chapters`)
  return normalizeChapterList(response)
}

export async function getStoryChapter(
  storyId: EntityId,
  chapterNo: number,
): Promise<ChapterDetail | null> {
  try {
    const response = await apiClient.get<unknown>(
      `/api/stories/${storyId}/chapters/${chapterNo}`,
    )
    return normalizeChapterDetail(response)
  } catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

export async function getChapter(chapterId: EntityId): Promise<ChapterDetail> {
  const response = await apiClient.get<unknown>(`/api/chapters/${chapterId}`)
  return normalizeChapterDetail(response)
}

export async function getChapterPlan(
  storyId: EntityId,
  chapterNo: number,
): Promise<ChapterPlanEnvelope | null> {
  try {
    const response = await apiClient.get<unknown>(
      `/api/stories/${storyId}/chapters/${chapterNo}/plan`,
    )
    return normalizeChapterPlanEnvelope(response)
  } catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

export async function createChapterPlan(
  storyId: EntityId,
  chapterNo: number,
  targetLength?: number,
): Promise<ChapterTask> {
  const response = await apiClient.post<unknown>(
    `/api/stories/${storyId}/chapters/${chapterNo}/plan`,
    targetLength ? { targetLength } : {},
  )
  const task = normalizeChapterTask(response)
  if (task.taskId === '') throw new Error('章节计划已提交，但服务未返回任务 ID。')
  return { ...task, storyId: task.storyId ?? storyId, chapterNo: task.chapterNo ?? chapterNo }
}

export async function approveChapterPlan(
  storyId: EntityId,
  chapterNo: number,
  planHash?: string,
): Promise<ChapterDetail> {
  const response = await apiClient.post<unknown>(
    `/api/stories/${storyId}/chapters/${chapterNo}/plan/approve`,
    planHash ? { planHash } : {},
  )
  return normalizeChapterDetail(response)
}

export async function generateChapter(
  storyId: EntityId,
  chapterNo: number,
): Promise<ChapterTask> {
  const response = await apiClient.post<unknown>(
    `/api/stories/${storyId}/chapters/${chapterNo}/generate`,
    {},
  )
  const task = normalizeChapterTask(response)
  if (task.taskId === '') throw new Error('章节生成已提交，但服务未返回任务 ID。')
  return { ...task, storyId: task.storyId ?? storyId, chapterNo: task.chapterNo ?? chapterNo }
}

export async function saveChapterContent(
  chapterId: EntityId,
  input: { baseVersionId: EntityId; content: string; baseContentHash?: string },
): Promise<ChapterVersion> {
  const response = await apiClient.put<unknown>(`/api/chapters/${chapterId}/content`, {
    ...input,
    baseVersionId: numericPathValue(input.baseVersionId),
  })
  return normalizeChapterVersion(response)
}

export async function rewriteChapterSelection(
  chapterId: EntityId,
  input: RewriteSelectionInput,
): Promise<RewriteProposal | ChapterTask> {
  const response = await apiClient.post<unknown>(
    `/api/chapters/${chapterId}/rewrite-selection`,
    input,
  )
  const task = normalizeChapterTask(response)
  const proposal = normalizeRewriteProposal(response)
  if (proposal.proposalId === '' && task.taskId !== '') return task
  if (proposal.proposalId === '') throw new Error('AI 已完成改写，但服务未返回 Proposal ID。')
  return proposal
}

export async function listRewriteProposals(
  chapterId: EntityId,
): Promise<RewriteProposal[]> {
  const response = await apiClient.get<unknown>(
    `/api/chapters/${chapterId}/rewrite-proposals`,
  )
  return normalizeRewriteProposals(response)
}

export async function acceptRewriteProposal(
  chapterId: EntityId,
  proposalId: EntityId,
  baseVersionId?: EntityId,
): Promise<ChapterVersion> {
  const response = await apiClient.post<unknown>(
    `/api/chapters/${chapterId}/rewrite-proposals/${proposalId}/accept`,
    baseVersionId === undefined ? {} : { baseVersionId: numericPathValue(baseVersionId) },
  )
  return normalizeChapterVersion(response)
}

export async function rejectRewriteProposal(
  chapterId: EntityId,
  proposalId: EntityId,
): Promise<RewriteProposal> {
  const response = await apiClient.post<unknown>(
    `/api/chapters/${chapterId}/rewrite-proposals/${proposalId}/reject`,
    {},
  )
  return normalizeRewriteProposal(response)
}

export async function regenerateRewriteProposal(
  chapterId: EntityId,
  proposalId: EntityId,
): Promise<RewriteProposal | ChapterTask> {
  const response = await apiClient.post<unknown>(
    `/api/chapters/${chapterId}/rewrite-proposals/${proposalId}/regenerate`,
    {},
  )
  const task = normalizeChapterTask(response)
  const proposal = normalizeRewriteProposal(response)
  if (proposal.proposalId === '' && task.taskId !== '') return task
  if (proposal.proposalId === '') throw new Error('重新生成成功，但服务未返回 Proposal ID。')
  return proposal
}

export async function listChapterVersions(chapterId: EntityId): Promise<ChapterVersion[]> {
  const response = await apiClient.get<unknown>(`/api/chapters/${chapterId}/versions`)
  return normalizeChapterVersions(response)
}

export async function compareChapterVersions(
  chapterId: EntityId,
  fromVersionId: EntityId,
  toVersionId: EntityId,
): Promise<ChapterVersionComparison> {
  const response = await apiClient.get<unknown>(`/api/chapters/${chapterId}/versions/compare`, {
    params: {
      fromVersionId: numericPathValue(fromVersionId),
      toVersionId: numericPathValue(toVersionId),
    },
  })
  return normalizeVersionComparison(response)
}

export async function restoreChapterVersion(
  chapterId: EntityId,
  versionId: EntityId,
): Promise<ChapterVersion> {
  const response = await apiClient.post<unknown>(
    `/api/chapters/${chapterId}/versions/${versionId}/restore`,
    {},
  )
  return normalizeChapterVersion(response)
}

export async function approveChapter(
  chapterId: EntityId,
): Promise<ChapterDetail | ChapterTask> {
  const response = await apiClient.post<unknown>(`/api/chapters/${chapterId}/approve`, {
    approved: true,
  })
  const task = normalizeChapterTask(response)
  if (task.taskId !== '') return task
  return normalizeChapterDetail(response)
}

export async function requestChapterChanges(
  chapterId: EntityId,
  notes: string,
): Promise<ChapterDetail | ChapterTask> {
  const response = await apiClient.post<unknown>(`/api/chapters/${chapterId}/approve`, {
    approved: false,
    notes: notes.trim(),
  })
  const task = normalizeChapterTask(response)
  if (task.taskId !== '') return task
  return normalizeChapterDetail(response)
}
