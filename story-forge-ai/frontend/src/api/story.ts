import {
  normalizeGenerateResult,
  normalizeStory,
  normalizeStoryList,
} from '@/api/normalizers'
import type {
  CreateStoryInput,
  EntityId,
  GenerateTopicInput,
  GenerateTopicResult,
  StoryProject,
} from '@/types'
import { apiClient } from '@/utils/request'

export async function listStories(): Promise<StoryProject[]> {
  const response = await apiClient.get<unknown>('/api/story/list')
  return normalizeStoryList(response)
}

export async function createStory(input: CreateStoryInput): Promise<StoryProject> {
  const response = await apiClient.post<unknown>('/api/story/create', input)
  if (typeof response === 'number' || typeof response === 'string') {
    return {
      id: response,
      title: input.title,
      genre: input.genre,
      audience: input.audience,
      keywords: input.keywords,
      contentMode: input.contentMode || 'SHORT_STORY',
      targetChapterCount: input.targetChapterCount,
      targetTotalWords: input.targetTotalWords,
      chapterTargetWords: input.chapterTargetWords,
      viewpoint: input.viewpoint,
      styleProfile: input.styleProfile,
      status: 'draft',
      createdTime: new Date().toISOString(),
    }
  }

  const story = normalizeStory(response)
  return {
    ...story,
    title: story.title === '未命名故事' ? input.title : story.title,
    genre: story.genre === '未分类' ? input.genre : story.genre,
    audience: story.audience || input.audience,
    keywords: story.keywords || input.keywords,
    contentMode: story.contentMode || input.contentMode || 'SHORT_STORY',
    targetChapterCount: story.targetChapterCount || input.targetChapterCount,
    targetTotalWords: story.targetTotalWords || input.targetTotalWords,
    chapterTargetWords: story.chapterTargetWords || input.chapterTargetWords,
    viewpoint: story.viewpoint || input.viewpoint,
    styleProfile: story.styleProfile || input.styleProfile,
  }
}

export async function getStory(id: EntityId): Promise<StoryProject> {
  const response = await apiClient.get<unknown>(`/api/story/${id}`)
  return normalizeStory(response)
}

export async function saveTopicSelection(
  id: EntityId,
  topicId: string,
): Promise<StoryProject> {
  const normalizedTopicId = /^\d+$/.test(topicId) ? Number(topicId) : topicId
  await apiClient.put<unknown>(`/api/story/${id}/selection`, {
    topicId: normalizedTopicId,
  })
  return getStory(id)
}

export async function generateTopics(
  input: GenerateTopicInput,
): Promise<GenerateTopicResult> {
  const response = await apiClient.post<unknown>('/api/ai/topic/generate', input, {
    timeout: 90_000,
  })
  return normalizeGenerateResult(response)
}
