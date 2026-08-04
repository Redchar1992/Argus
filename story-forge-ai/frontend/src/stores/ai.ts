import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as aiApi from '@/api/ai'
import type { AiWallet } from '@/types'

export const useAiStore = defineStore('ai', () => {
  const wallet = ref<AiWallet | null>(null)
  const pricing = ref<aiApi.AiPricing[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  const pricingMap = computed(() =>
    new Map(pricing.value.map((item) => [item.operationType, item])),
  )

  async function fetch(force = false) {
    if (loaded.value && !force) return wallet.value
    loading.value = true
    try {
      const [nextWallet, nextPricing] = await Promise.all([
        aiApi.getAiWallet(),
        aiApi.listAiPricing(),
      ])
      wallet.value = nextWallet
      pricing.value = nextPricing
      loaded.value = true
      return wallet.value
    } finally {
      loading.value = false
    }
  }

  function cost(operationType: string) {
    return pricingMap.value.get(operationType)?.credits ?? null
  }

  function reset() {
    wallet.value = null
    pricing.value = []
    loaded.value = false
  }

  return { wallet, pricing, loading, loaded, fetch, cost, reset }
})
