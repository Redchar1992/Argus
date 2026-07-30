import type {
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
} from 'axios'
import axios from 'axios'

import { clearStoredAuth, getStoredAuth } from '@/utils/storage'

interface ApiClient {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>
}

interface ApiEnvelope {
  code?: number | string
  success?: boolean
  message?: string
  msg?: string
  data?: unknown
}

const rawClient = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, ''),
  timeout: 45_000,
  headers: {
    'Content-Type': 'application/json',
  },
})

rawClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getStoredAuth()?.token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

rawClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      clearStoredAuth()
      window.dispatchEvent(new CustomEvent('story-forge:unauthorized'))
    }
    return Promise.reject(error)
  },
)

function unwrapPayload<T>(payload: unknown): T {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return payload as T

  const envelope = payload as ApiEnvelope
  const acceptedCodes = [undefined, 0, 200, '0', '200']
  const isFailure =
    envelope.success === false ||
    ('code' in envelope && !acceptedCodes.includes(envelope.code))

  if (isFailure) {
    throw new Error(envelope.message || envelope.msg || '请求处理失败。')
  }

  if ('code' in envelope && 'data' in envelope) return envelope.data as T
  return payload as T
}

export const apiClient: ApiClient = {
  async get<T>(url: string, config?: AxiosRequestConfig) {
    return unwrapPayload<T>((await rawClient.get(url, config)).data)
  },
  async post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return unwrapPayload<T>((await rawClient.post(url, data, config)).data)
  },
  async put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
    return unwrapPayload<T>((await rawClient.put(url, data, config)).data)
  },
  async delete<T>(url: string, config?: AxiosRequestConfig) {
    return unwrapPayload<T>((await rawClient.delete(url, config)).data)
  },
}
