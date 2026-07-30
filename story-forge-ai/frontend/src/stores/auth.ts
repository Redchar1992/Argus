import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/api/auth'
import { useStoryStore } from '@/stores/story'
import { useWorkflowStore } from '@/stores/workflow'
import type { AuthCredentials, EntityId } from '@/types'
import {
  clearStoredAuth,
  clearTopicSessions,
  clearWorkflowSessions,
  getStoredAuth,
  setStoredAuth,
} from '@/utils/storage'

export const useAuthStore = defineStore('auth', () => {
  const persisted = getStoredAuth()
  const token = ref(persisted?.token ?? '')
  const userId = ref<EntityId | null>(persisted?.userId ?? null)
  const username = ref(persisted?.username ?? '')
  const submitting = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value))
  const displayName = computed(() => username.value || '创作者')

  async function login(credentials: AuthCredentials) {
    submitting.value = true
    try {
      const result = await authApi.login(credentials)
      token.value = result.token
      userId.value = result.userId
      username.value = result.username || credentials.username
      setStoredAuth({
        token: result.token,
        userId: result.userId,
        username: username.value,
      })
      return result
    } finally {
      submitting.value = false
    }
  }

  async function register(credentials: AuthCredentials) {
    submitting.value = true
    try {
      await authApi.register(credentials)
    } finally {
      submitting.value = false
    }
  }

  function logout() {
    useStoryStore().reset()
    useWorkflowStore().reset()
    clearTopicSessions()
    clearWorkflowSessions()
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
