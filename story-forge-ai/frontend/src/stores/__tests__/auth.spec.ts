import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { useAuthStore } from '@/stores/auth'
import { useStoryStore } from '@/stores/story'
import {
  getStoredAuth,
  getTopicSession,
  saveTopicSession,
  setStoredAuth,
} from '@/utils/storage'

describe('auth logout cleanup', () => {
  beforeEach(() => {
    window.localStorage.clear()
    setActivePinia(createPinia())
  })

  it('clears auth, cached topics, and the loaded story list', () => {
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
    authStore.logout()

    expect(getStoredAuth()).toBeNull()
    expect(getTopicSession(42)).toBeNull()
    expect(storyStore.stories).toEqual([])
    expect(storyStore.loaded).toBe(false)
  })
})
