import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getLatestStoryWorkflowTask,
  getWorkflowReview,
  getWorkflowTask,
  startWorkflow,
  submitWorkflowReview,
} from '@/api/workflow'
import { apiClient } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('workflow API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('starts a workflow with a numeric topic id', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ taskId: 90001, status: 'WAITING' })

    const task = await startWorkflow({ storyId: 5001, topicId: '7' })

    expect(apiClient.post).toHaveBeenCalledWith('/api/stories/5001/workflow', {
      topicId: 7,
    })
    expect(task).toMatchObject({
      taskId: 90001,
      storyId: 5001,
      topicId: '7',
      status: 'WAITING',
    })
  })

  it('loads task and review endpoints and submits a resume decision', async () => {
    vi.mocked(apiClient.get)
      .mockResolvedValueOnce({
        taskId: 90001,
        storyId: 5001,
        status: 'RUNNING',
        currentNode: 'generate_outline',
        progress: 42,
      })
      .mockResolvedValueOnce({
        taskId: 90001,
        characters: [],
        outline: { nodes: [] },
        score: {},
      })
    vi.mocked(apiClient.post).mockResolvedValue({
      taskId: 90002,
      status: 'WAITING',
    })

    await getWorkflowTask(90001)
    await getWorkflowReview(90001)
    const resumed = await submitWorkflowReview(90001, {
      approved: false,
      notes: '请补强节点 12 的人物动机。',
    })

    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/api/ai-tasks/90001')
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/api/ai-tasks/90001/review')
    expect(apiClient.post).toHaveBeenCalledWith('/api/ai-tasks/90001/review', {
      approved: false,
      notes: '请补强节点 12 的人物动机。',
    })
    expect(resumed.taskId).toBe(90002)
  })

  it('loads the canonical latest task for a story', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      taskId: 90003,
      storyId: 5001,
      topicId: 7,
      status: 'SUCCESS',
      currentNode: 'finish',
      progress: 100,
    })

    const task = await getLatestStoryWorkflowTask(5001)

    expect(apiClient.get).toHaveBeenCalledWith('/api/stories/5001/workflow/latest')
    expect(task).toMatchObject({
      taskId: 90003,
      storyId: 5001,
      topicId: 7,
      status: 'SUCCESS',
    })
  })

  it('treats an empty or missing latest workflow as no task', async () => {
    vi.mocked(apiClient.get)
      .mockResolvedValueOnce(undefined)
      .mockResolvedValueOnce('')

    await expect(getLatestStoryWorkflowTask(5001)).resolves.toBeNull()
    await expect(getLatestStoryWorkflowTask(5001)).resolves.toBeNull()

    vi.mocked(apiClient.get).mockRejectedValueOnce(
      Object.assign(new Error('not found'), {
        isAxiosError: true,
        response: { status: 404 },
      }),
    )

    await expect(getLatestStoryWorkflowTask(5001)).resolves.toBeNull()
  })
})
