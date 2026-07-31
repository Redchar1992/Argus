import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/stores/auth'
import { useChapterStore } from '@/stores/chapter'
import { useStoryStore } from '@/stores/story'
import {
  getStoredAuth,
  getChapterStreamCursor,
  getTopicSession,
  getWorkflowSession,
  saveTopicSession,
  saveChapterStreamCursor,
  saveWorkflowSession,
  setStoredAuth,
} from '@/utils/storage'

describe('auth logout cleanup', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('keeps the JWT in session storage instead of persistent local storage', () => {
    setStoredAuth({ token: 'token-a', userId: 10001, username: 'writer' })

    expect(window.localStorage.getItem('story-forge.auth')).toBeNull()
    expect(window.sessionStorage.getItem('story-forge.auth')).toContain('token-a')
  })

  it('clears auth, cached topics, stream cursors, and loaded story state', async () => {
    setStoredAuth({ token: 'token-a', userId: 10001, username: 'writer' })
    saveTopicSession({
      storyId: 42,
      topics: [],
      generatedAt: '2026-07-30T00:00:00.000Z',
      input: {
        title: '都市复仇',
        genre: '都市情感',
        audience: '女性',
        keywords: '复仇',
      },
    })
    saveWorkflowSession({
      taskId: 90001,
      storyId: 42,
      topicId: 1,
      status: 'RUNNING',
      currentNode: 'generate_outline',
      progress: 42,
      revisionCount: 0,
      maxRevisions: 2,
      events: [],
    })
    saveChapterStreamCursor({
      taskId: 80001,
      storyId: 42,
      chapterNo: 1,
      purpose: 'generate',
      lastEventId: 'redis-8',
      lastSequence: 8,
      updatedAt: '2026-07-30T00:00:00.000Z',
    })

    const storyStore = useStoryStore()
    storyStore.$patch({
      stories: [
        {
          id: 42,
          title: '都市复仇',
          genre: '都市情感',
          status: 'generated',
        },
      ],
      loaded: true,
    })

    const authStore = useAuthStore()
    await authStore.logout()

    expect(getStoredAuth()).toBeNull()
    expect(getTopicSession(42)).toBeNull()
    expect(getWorkflowSession(90001)).toBeNull()
    expect(getChapterStreamCursor(80001)).toBeNull()
    expect(storyStore.stories).toEqual([])
    expect(storyStore.loaded).toBe(false)
  })

  it('saves dirty chapter content before clearing the login session', async () => {
    setStoredAuth({ token: 'token-a', userId: 10001, username: 'writer' })
    const authStore = useAuthStore()
    const chapterStore = useChapterStore()
    chapterStore.updateEditorContent('尚未保存的正文')
    const saveNow = vi.spyOn(chapterStore, 'saveNow').mockImplementation(async () => {
      chapterStore.updateEditorContent('')
      return null
    })

    await authStore.logout({ saveChapter: true })

    expect(saveNow).toHaveBeenCalledOnce()
    expect(getStoredAuth()).toBeNull()
    expect(authStore.isAuthenticated).toBe(false)
  })

  it('keeps the login session when dirty chapter saving fails', async () => {
    setStoredAuth({ token: 'token-a', userId: 10001, username: 'writer' })
    const authStore = useAuthStore()
    const chapterStore = useChapterStore()
    chapterStore.updateEditorContent('尚未保存的正文')
    vi.spyOn(chapterStore, 'saveNow').mockRejectedValue(new Error('保存失败'))

    await expect(authStore.logout({ saveChapter: true })).rejects.toThrow('保存失败')

    expect(getStoredAuth()).toMatchObject({ token: 'token-a' })
    expect(authStore.isAuthenticated).toBe(true)
  })
})
