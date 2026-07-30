import type {
  ChapterStreamCursor,
  EntityId,
  TopicSession,
  WorkflowSession,
  WorkflowTask,
} from '@/types'

const AUTH_STORAGE_KEY = 'story-forge.auth'
const TOPIC_STORAGE_PREFIX = 'story-forge.topic-sessions'
const WORKFLOW_STORAGE_PREFIX = 'story-forge.workflow-sessions'
const CHAPTER_STREAM_PREFIX = 'story-forge.chapter-stream-cursors'
const MAX_CACHED_SESSIONS = 50

export interface StoredAuth {
  token: string
  userId: EntityId
  username?: string
}

function canUseStorage() {
  return typeof window !== 'undefined' && Boolean(window.localStorage)
}

function readJson<T>(key: string, fallback: T): T {
  if (!canUseStorage()) return fallback

  try {
    const value = window.localStorage.getItem(key)
    return value ? (JSON.parse(value) as T) : fallback
  } catch {
    return fallback
  }
}

function writeJson(key: string, value: unknown) {
  if (!canUseStorage()) return

  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Storage can be unavailable in privacy mode. The active session still works.
  }
}

export function getStoredAuth(): StoredAuth | null {
  const auth = readJson<StoredAuth | null>(AUTH_STORAGE_KEY, null)
  return auth?.token ? auth : null
}

export function setStoredAuth(auth: StoredAuth) {
  writeJson(AUTH_STORAGE_KEY, auth)
}

export function clearStoredAuth() {
  if (!canUseStorage()) return
  window.localStorage.removeItem(AUTH_STORAGE_KEY)
}

function topicStorageKey() {
  const userId = getStoredAuth()?.userId
  return `${TOPIC_STORAGE_PREFIX}.${String(userId ?? 'anonymous')}`
}

function getSessions(): Record<string, TopicSession> {
  return readJson<Record<string, TopicSession>>(topicStorageKey(), {})
}

export function getTopicSession(storyId: EntityId): TopicSession | null {
  return getSessions()[String(storyId)] ?? null
}

export function saveTopicSession(session: TopicSession) {
  const sessions = getSessions()
  sessions[String(session.storyId)] = session

  const limitedEntries = Object.entries(sessions)
    .sort(
      ([, left], [, right]) =>
        new Date(right.generatedAt).getTime() - new Date(left.generatedAt).getTime(),
    )
    .slice(0, MAX_CACHED_SESSIONS)

  writeJson(topicStorageKey(), Object.fromEntries(limitedEntries))
}

export function selectCachedTopic(storyId: EntityId, topicId: string) {
  const session = getTopicSession(storyId)
  if (!session) return
  saveTopicSession({ ...session, selectedTopicId: topicId })
}

export function clearTopicSessions() {
  if (!canUseStorage()) return

  const keysToRemove: string[] = []
  for (let index = 0; index < window.localStorage.length; index += 1) {
    const key = window.localStorage.key(index)
    if (key?.startsWith(TOPIC_STORAGE_PREFIX)) keysToRemove.push(key)
  }
  keysToRemove.forEach((key) => window.localStorage.removeItem(key))
}

function workflowStorageKey() {
  const userId = getStoredAuth()?.userId
  return `${WORKFLOW_STORAGE_PREFIX}.${String(userId ?? 'anonymous')}`
}

function getWorkflowSessions(): Record<string, WorkflowSession> {
  return readJson<Record<string, WorkflowSession>>(workflowStorageKey(), {})
}

export function saveWorkflowSession(task: WorkflowTask) {
  if (task.taskId === '' || task.storyId === undefined) return
  const sessions = getWorkflowSessions()
  sessions[String(task.taskId)] = {
    taskId: task.taskId,
    storyId: task.storyId,
    topicId: task.topicId,
    threadId: task.threadId,
    status: task.status,
    currentNode: task.currentNode,
    progress: task.progress,
    updatedAt: task.updatedTime || new Date().toISOString(),
  }
  const limited = Object.entries(sessions)
    .sort(
      ([, left], [, right]) =>
        new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime(),
    )
    .slice(0, MAX_CACHED_SESSIONS)
  writeJson(workflowStorageKey(), Object.fromEntries(limited))
}

export function getWorkflowSession(taskId: EntityId): WorkflowSession | null {
  return getWorkflowSessions()[String(taskId)] ?? null
}

export function getLatestStoryWorkflow(storyId: EntityId): WorkflowSession | null {
  return (
    Object.values(getWorkflowSessions())
      .filter((session) => String(session.storyId) === String(storyId))
      .sort(
        (left, right) =>
          new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime(),
      )[0] ?? null
  )
}

export function clearWorkflowSessions() {
  if (!canUseStorage()) return
  const keysToRemove: string[] = []
  for (let index = 0; index < window.localStorage.length; index += 1) {
    const key = window.localStorage.key(index)
    if (key?.startsWith(WORKFLOW_STORAGE_PREFIX)) keysToRemove.push(key)
  }
  keysToRemove.forEach((key) => window.localStorage.removeItem(key))
}

function chapterStreamStorageKey() {
  const userId = getStoredAuth()?.userId
  return `${CHAPTER_STREAM_PREFIX}.${String(userId ?? 'anonymous')}`
}

function getChapterStreamCursors(): Record<string, ChapterStreamCursor> {
  return readJson<Record<string, ChapterStreamCursor>>(chapterStreamStorageKey(), {})
}

export function getChapterStreamCursor(taskId: EntityId): ChapterStreamCursor | null {
  return getChapterStreamCursors()[String(taskId)] ?? null
}

export function findChapterStreamCursor(
  storyId: EntityId,
  chapterNo: number,
): ChapterStreamCursor | null {
  return (
    Object.values(getChapterStreamCursors())
      .filter(
        (cursor) =>
          String(cursor.storyId) === String(storyId) && cursor.chapterNo === chapterNo,
      )
      .sort(
        (left, right) =>
          new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime(),
      )[0] ?? null
  )
}

export function saveChapterStreamCursor(cursor: ChapterStreamCursor) {
  const cursors = getChapterStreamCursors()
  cursors[String(cursor.taskId)] = cursor
  const limited = Object.entries(cursors)
    .sort(
      ([, left], [, right]) =>
        new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime(),
    )
    .slice(0, MAX_CACHED_SESSIONS)
  writeJson(chapterStreamStorageKey(), Object.fromEntries(limited))
}

export function removeChapterStreamCursor(taskId: EntityId) {
  const cursors = getChapterStreamCursors()
  delete cursors[String(taskId)]
  writeJson(chapterStreamStorageKey(), cursors)
}

export function clearChapterStreamCursors() {
  if (!canUseStorage()) return
  const keysToRemove: string[] = []
  for (let index = 0; index < window.localStorage.length; index += 1) {
    const key = window.localStorage.key(index)
    if (key?.startsWith(CHAPTER_STREAM_PREFIX)) keysToRemove.push(key)
  }
  keysToRemove.forEach((key) => window.localStorage.removeItem(key))
}
