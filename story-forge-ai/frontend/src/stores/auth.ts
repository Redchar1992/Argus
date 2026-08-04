import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/api/auth'
import { useChapterStore } from '@/stores/chapter'
import { useStoryStore } from '@/stores/story'
import { useWorkflowStore } from '@/stores/workflow'
import { useAiStore } from '@/stores/ai'
import type { AuthResult, EntityId, LoginCredentials, RegisterCredentials } from '@/types'
import {
  clearStoredAuth,
  clearChapterStreamCursors,
  clearTopicSessions,
  clearWorkflowSessions,
  getStoredAuth,
  setStoredAuth,
} from '@/utils/storage'

interface LogoutOptions {
  saveChapter?: boolean
}

export const useAuthStore = defineStore('auth', () => {
  const persisted = getStoredAuth()
  const token = ref(persisted?.token ?? '')
  const userId = ref<EntityId | null>(persisted?.userId ?? null)
  const username = ref(persisted?.username ?? '')
  const submitting = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value))
  const displayName = computed(() => username.value || '创作者')

  function persistAuth(result: AuthResult, fallbackUsername: string) {
    token.value = result.token
    userId.value = result.userId
    username.value = result.username || fallbackUsername
    setStoredAuth({
      token: result.token,
      userId: result.userId,
      username: username.value,
    })
  }

  async function login(credentials: LoginCredentials) {
    submitting.value = true
    try {
      const result = await authApi.login(credentials)
      persistAuth(result, credentials.username)
      return result
    } finally {
      submitting.value = false
    }
  }

  async function register(credentials: RegisterCredentials) {
    submitting.value = true
    try {
      const result = await authApi.register(credentials)
      persistAuth(result, credentials.username)
      return result
    } finally {
      submitting.value = false
    }
  }

  async function logout(options: LogoutOptions = {}) {
    const chapterStore = useChapterStore()
    if (options.saveChapter && chapterStore.isDirty) {
      await chapterStore.saveNow()
      if (chapterStore.isDirty) {
        throw new Error('正文尚未保存，暂时不能退出登录。')
      }
    }
    chapterStore.reset()
    useStoryStore().reset()
    useWorkflowStore().reset()
    useAiStore().reset()
    clearTopicSessions()
    clearWorkflowSessions()
    clearChapterStreamCursors()
    token.value = ''
    userId.value = null
    username.value = ''
    clearStoredAuth()
  }

  return {
    token,
    userId,
    username,
    submitting,
    isAuthenticated,
    displayName,
    login,
    register,
    logout,
  }
})
