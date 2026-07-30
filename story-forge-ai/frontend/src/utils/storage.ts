import type { EntityId, TopicSession } from '@/types'

const AUTH_STORAGE_KEY = 'story-forge.auth'
const TOPIC_STORAGE_PREFIX = 'story-forge.topic-sessions'
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
