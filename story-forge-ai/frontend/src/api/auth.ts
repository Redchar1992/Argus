import { normalizeAuth } from '@/api/normalizers'
import type { AuthCredentials, AuthResult } from '@/types'
import { apiClient } from '@/utils/request'

export async function register(credentials: AuthCredentials) {
  return apiClient.post<unknown>('/api/auth/register', credentials)
}

export async function login(credentials: AuthCredentials): Promise<AuthResult> {
  const response = await apiClient.post<unknown>('/api/auth/login', credentials)
  return normalizeAuth(response, credentials.username)
}
