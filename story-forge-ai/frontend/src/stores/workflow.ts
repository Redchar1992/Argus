import { defineStore } from 'pinia'
import { ref } from 'vue'

import * as workflowApi from '@/api/workflow'
import type {
  EntityId,
  StartWorkflowInput,
  SubmitWorkflowReviewInput,
  WorkflowReview,
  WorkflowTask,
} from '@/types'
import {
  getLatestStoryWorkflow,
  getWorkflowSession,
  saveWorkflowSession,
} from '@/utils/storage'

const POLL_INTERVAL_MS = 2_000

function isPollingTerminal(status: WorkflowTask['status']) {
  return ['REVIEW_REQUIRED', 'SUCCESS', 'FAILED'].includes(status)
}

export const useWorkflowStore = defineStore('workflow', () => {
  const tasks = ref<Record<string, WorkflowTask>>({})
  const reviews = ref<Record<string, WorkflowReview>>({})
  const starting = ref(false)
  const loadingReview = ref(false)
  const submittingReview = ref(false)
  const timers = new Map<string, ReturnType<typeof setTimeout>>()
  const pollTokens = new Map<string, symbol>()

  const activePollCount = ref(0)

  function rememberTask(task: WorkflowTask) {
    tasks.value[String(task.taskId)] = task
    saveWorkflowSession(task)
    return task
  }

  function restoreTask(taskId: EntityId, storyId?: EntityId) {
    const existing = tasks.value[String(taskId)]
    if (existing) return existing
    const session = getWorkflowSession(taskId)
    if (!session && storyId === undefined) return undefined

    const restored: WorkflowTask = {
      taskId,
      storyId: session?.storyId ?? storyId,
      topicId: session?.topicId,
      threadId: session?.threadId,
      status: session?.status ?? 'WAITING',
      currentNode: session?.currentNode ?? '',
      progress: session?.progress ?? 0,
      revisionCount: 0,
      maxRevisions: 2,
      events: [],
      updatedTime: session?.updatedAt,
    }
    tasks.value[String(taskId)] = restored
    return restored
  }

  function latestStorySession(storyId: EntityId) {
    return getLatestStoryWorkflow(storyId)
  }

  async function start(input: StartWorkflowInput) {
    starting.value = true
    try {
      return rememberTask(await workflowApi.startWorkflow(input))
    } finally {
      starting.value = false
    }
  }

  async function fetchTask(taskId: EntityId) {
    const previous = tasks.value[String(taskId)]
    const task = await workflowApi.getWorkflowTask(taskId)
    if (task.storyId === undefined && previous?.storyId !== undefined) {
      task.storyId = previous.storyId
    }
    if (task.topicId === undefined && previous?.topicId !== undefined) {
      task.topicId = previous.topicId
    }
    if (!task.threadId && previous?.threadId) task.threadId = previous.threadId
    return rememberTask(task)
  }

  async function fetchLatestStoryTask(storyId: EntityId) {
    const task = await workflowApi.getLatestStoryWorkflowTask(storyId)
    if (!task) return null

    const cached = latestStorySession(storyId)
    if (task.topicId === undefined && cached?.topicId !== undefined) {
      task.topicId = cached.topicId
    }
    return rememberTask(task)
  }

  async function fetchReview(taskId: EntityId) {
    loadingReview.value = true
    try {
      const review = await workflowApi.getWorkflowReview(taskId)
      reviews.value[String(taskId)] = review
      return review
    } finally {
      loadingReview.value = false
    }
  }

  async function submitReview(taskId: EntityId, input: SubmitWorkflowReviewInput) {
    submittingReview.value = true
    try {
      const previous = tasks.value[String(taskId)]
      const task = await workflowApi.submitWorkflowReview(taskId, input)
      if (task.storyId === undefined && previous?.storyId !== undefined) {
        task.storyId = previous.storyId
      }
      if (task.topicId === undefined && previous?.topicId !== undefined) {
        task.topicId = previous.topicId
      }
      if (!task.threadId && previous?.threadId) task.threadId = previous.threadId
      rememberTask(task)
      delete reviews.value[String(taskId)]
      return task
    } finally {
      submittingReview.value = false
    }
  }

  function stopPolling(taskId: EntityId) {
    const key = String(taskId)
    const timer = timers.get(key)
    if (timer) clearTimeout(timer)
    timers.delete(key)
    pollTokens.delete(key)
    activePollCount.value = timers.size
  }

  function startPolling(
    taskId: EntityId,
    handlers: {
      onUpdate?: (task: WorkflowTask) => void
      onError?: (error: unknown) => void
    } = {},
  ) {
    const key = String(taskId)
    stopPolling(taskId)
    const token = Symbol(key)
    pollTokens.set(key, token)
    let stopped = false

    const poll = async () => {
      if (stopped || pollTokens.get(key) !== token) return
      try {
        const task = await fetchTask(taskId)
        handlers.onUpdate?.(task)
        if (isPollingTerminal(task.status)) {
          timers.delete(key)
          pollTokens.delete(key)
          activePollCount.value = timers.size
          return
        }
      } catch (error) {
        handlers.onError?.(error)
      }

      if (!stopped && pollTokens.get(key) === token) {
        timers.set(key, setTimeout(poll, POLL_INTERVAL_MS))
        activePollCount.value = timers.size
      }
    }

    void poll()
    return () => {
      stopped = true
      stopPolling(taskId)
    }
  }

  function reset() {
    timers.forEach((timer) => clearTimeout(timer))
    timers.clear()
    pollTokens.clear()
    activePollCount.value = 0
    tasks.value = {}
    reviews.value = {}
    starting.value = false
    loadingReview.value = false
    submittingReview.value = false
  }

  return {
    tasks,
    reviews,
    starting,
    loadingReview,
    submittingReview,
    activePollCount,
    restoreTask,
    latestStorySession,
    start,
    fetchTask,
    fetchLatestStoryTask,
    fetchReview,
    submitReview,
    startPolling,
    stopPolling,
    reset,
  }
})
