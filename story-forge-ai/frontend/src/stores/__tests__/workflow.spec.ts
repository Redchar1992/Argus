import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as workflowApi from '@/api/workflow'
import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowTask } from '@/types'
import { getWorkflowSession, setStoredAuth } from '@/utils/storage'

vi.mock('@/api/workflow', () => ({
  startWorkflow: vi.fn(),
  getLatestStoryWorkflowTask: vi.fn(),
  getWorkflowTask: vi.fn(),
  getWorkflowReview: vi.fn(),
  submitWorkflowReview: vi.fn(),
}))

function task(patch: Partial<WorkflowTask> = {}): WorkflowTask {
  return {
    taskId: 90001,
    storyId: 5001,
    topicId: 7,
    threadId: 'thread-1',
    status: 'RUNNING',
    currentNode: 'generate_characters',
    progress: 18,
    revisionCount: 0,
    maxRevisions: 2,
    events: [],
    ...patch,
  }
}

describe('workflow store', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    setStoredAuth({ token: 'token', userId: 10001 })
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('persists task recovery data and carries the story to a new resume task', async () => {
    vi.mocked(workflowApi.startWorkflow).mockResolvedValue(task())
    vi.mocked(workflowApi.submitWorkflowReview).mockResolvedValue(
      task({
        taskId: 90002,
        storyId: undefined,
        topicId: undefined,
        threadId: undefined,
        status: 'WAITING',
        currentNode: '',
        progress: 0,
      }),
    )

    const store = useWorkflowStore()
    await store.start({ storyId: 5001, topicId: 7 })
    const resumed = await store.submitReview(90001, {
      approved: false,
      notes: '补强节点 12 的前置动机',
    })

    expect(resumed).toMatchObject({
      taskId: 90002,
      storyId: 5001,
      topicId: 7,
      threadId: 'thread-1',
    })
    expect(getWorkflowSession(90002)).toMatchObject({
      taskId: 90002,
      storyId: 5001,
      status: 'WAITING',
    })
  })

  it('polls immediately and then every two seconds until review is required', async () => {
    vi.useFakeTimers()
    vi.mocked(workflowApi.getWorkflowTask)
      .mockResolvedValueOnce(task())
      .mockResolvedValueOnce(
        task({
          status: 'REVIEW_REQUIRED',
          currentNode: 'human_review',
          progress: 88,
        }),
      )

    const store = useWorkflowStore()
    const updates: string[] = []
    store.startPolling(90001, {
      onUpdate: (nextTask) => updates.push(nextTask.status),
    })

    await vi.advanceTimersByTimeAsync(0)
    expect(workflowApi.getWorkflowTask).toHaveBeenCalledTimes(1)
    expect(store.activePollCount).toBe(1)

    await vi.advanceTimersByTimeAsync(1_999)
    expect(workflowApi.getWorkflowTask).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1)
    expect(workflowApi.getWorkflowTask).toHaveBeenCalledTimes(2)
    expect(updates).toEqual(['RUNNING', 'REVIEW_REQUIRED'])
    expect(store.activePollCount).toBe(0)
  })

  it('uses the server task as the canonical latest workflow for a story', async () => {
    vi.mocked(workflowApi.getLatestStoryWorkflowTask).mockResolvedValue(
      task({
        taskId: 90003,
        topicId: 7,
        status: 'SUCCESS',
        currentNode: 'finish',
        progress: 100,
      }),
    )

    const store = useWorkflowStore()
    const latest = await store.fetchLatestStoryTask(5001)

    expect(workflowApi.getLatestStoryWorkflowTask).toHaveBeenCalledWith(5001)
    expect(latest).toMatchObject({
      taskId: 90003,
      storyId: 5001,
      topicId: 7,
      status: 'SUCCESS',
    })
    expect(store.latestStorySession(5001)).toMatchObject({
      taskId: 90003,
      status: 'SUCCESS',
    })
  })

  it('does not revive a cached workflow when the server reports no task', async () => {
    vi.mocked(workflowApi.startWorkflow).mockResolvedValue(task())
    vi.mocked(workflowApi.getLatestStoryWorkflowTask).mockResolvedValue(null)

    const store = useWorkflowStore()
    await store.start({ storyId: 5001, topicId: 7 })

    await expect(store.fetchLatestStoryTask(5001)).resolves.toBeNull()
  })
})
