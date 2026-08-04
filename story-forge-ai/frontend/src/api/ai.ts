import type { AiWallet } from '@/types'
import { apiClient } from '@/utils/request'

export interface AiPricing {
  operationType: string
  credits: number
  enabled: boolean
}

export async function getAiWallet(): Promise<AiWallet> {
  return apiClient.get<AiWallet>('/api/me/ai-wallet')
}

export async function listAiPricing(): Promise<AiPricing[]> {
  return apiClient.get<AiPricing[]>('/api/ai/pricing')
}
