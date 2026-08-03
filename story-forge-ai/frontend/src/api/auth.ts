import { normalizeAuth } from '@/api/normalizers'
import type { AuthResult, LoginCredentials, RegisterCredentials } from '@/types'
import { apiClient } from '@/utils/request'

export async function register(credentials: RegisterCredentials): Promise<AuthResult> {
  const response = await apiClient.post<unknown>('/api/auth/register', credentials)
  return normalizeAuth(response, credentials.username)
}

export async function login(credentials: LoginCredentials): Promise<AuthResult> {
  const response = await apiClient.post<unknown>('/api/auth/login', credentials)
  return normalizeAuth(response, credentials.username)
}
