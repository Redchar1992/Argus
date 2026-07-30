import { defineStore } from 'pinia'
import { ref } from 'vue'

import * as storyApi from '@/api/story'
import type { CreateStoryInput, EntityId, StoryProject } from '@/types'

export const useStoryStore = defineStore('story', () => {
  const stories = ref<StoryProject[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  async function fetchStories(force = false) {
    if (loaded.value && !force) return stories.value

    loading.value = true
    try {
      stories.value = await storyApi.listStories()
      loaded.value = true
      return stories.value
    } finally {
      loading.value = false
    }
  }

  async function createStory(input: CreateStoryInput) {
    const story = await storyApi.createStory(input)
    stories.value = [story, ...stories.value.filter((item) => item.id !== story.id)]
    return story
  }

  function updateStory(id: EntityId, patch: Partial<StoryProject>) {
    const index = stories.value.findIndex((story) => String(story.id) === String(id))
    if (index < 0) return
    stories.value[index] = { ...stories.value[index], ...patch }
  }

  function reset() {
    stories.value = []
    loaded.value = false
  }

  return {
    stories,
    loading,
    loaded,
    fetchStories,
    createStory,
    updateStory,
    reset,
  }
})
