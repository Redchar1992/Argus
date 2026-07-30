import axios from 'axios'

import {
  normalizeWorkflowReview,
  normalizeWorkflowTask,
} from '@/api/workflow-normalizers'
import type {
  EntityId,
  StartWorkflowInput,
  SubmitWorkflowReviewInput,
  WorkflowReview,
  WorkflowTask,
} from '@/types'
import { apiClient } from '@/utils/request'

function topicIdPayload(topicId: EntityId) {
  return typeof topicId === 'string' && /^\d+$/.test(topicId)
    ? Number(topicId)
    : topicId
}

export async function startWorkflow(input: StartWorkflowInput): Promise<WorkflowTask> {
  const response = await apiClient.post<unknown>(
    `/api/stories/${input.storyId}/workflow`,
    { topicId: topicIdPayload(input.topicId) },
  )
  const task = normalizeWorkflowTask(response)
  if (task.taskId === '') throw new Error('工作流已提交，但服务未返回任务 ID。')
  return {
    ...task,
    storyId: task.storyId ?? input.storyId,
    topicId: task.topicId ?? input.topicId,
  }
}

export async function getWorkflowTask(taskId: EntityId): Promise<WorkflowTask> {
  const response = await apiClient.get<unknown>(`/api/ai-tasks/${taskId}`)
  const task = normalizeWorkflowTask(response)
  return { ...task, taskId: task.taskId || taskId }
}

export async function getLatestStoryWorkflowTask(
  storyId: EntityId,
): Promise<WorkflowTask | null> {
  try {
    const response = await apiClient.get<unknown>(
      `/api/stories/${storyId}/workflow/latest`,
    )
    if (
      response === undefined ||
      response === null ||
      (typeof response === 'string' && response.trim() === '')
    ) {
      return null
    }

    const task = normalizeWorkflowTask(response)
    if (task.taskId === '') {
      throw new Error('服务返回的最新工作流缺少任务 ID。')
    }
    return { ...task, storyId: task.storyId ?? storyId }
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 404) return null
    throw error
  }
}

export async function getWorkflowReview(taskId: EntityId): Promise<WorkflowReview> {
  const response = await apiClient.get<unknown>(`/api/ai-tasks/${taskId}/review`)
  const review = normalizeWorkflowReview(response)
  return { ...review, taskId: review.taskId ?? taskId }
}

export async function submitWorkflowReview(
  taskId: EntityId,
  input: SubmitWorkflowReviewInput,
): Promise<WorkflowTask> {
  const response = await apiClient.post<unknown>(
    `/api/ai-tasks/${taskId}/review`,
    input,
  )
  const task = normalizeWorkflowTask(response)
  if (task.taskId === '') throw new Error('审核已提交，但服务未返回新的任务 ID。')
  return { ...task, taskId: task.taskId || taskId }
}
