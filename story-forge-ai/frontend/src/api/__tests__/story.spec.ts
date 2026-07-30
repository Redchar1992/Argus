import { beforeEach, describe, expect, it, vi } from 'vitest'

import { saveTopicSelection } from '@/api/story'
import { apiClient } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('story selection API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('persists a numeric topic id and refreshes the story from the server', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ success: true })
    vi.mocked(apiClient.get).mockResolvedValue({
      id: 42,
      title: '都市复仇',
      genre: '都市情感',
      selectedTopicId: 3,
      generatedTopics: [
        {
          id: 3,
          title: '归来的继承人',
          hook: '身份反转',
          summary: '她以新身份回到旧爱面前。',
          score: 93,
        },
      ],
    })

    const story = await saveTopicSelection(42, '3')

    expect(apiClient.put).toHaveBeenCalledWith('/api/story/42/selection', {
      topicId: 3,
    })
    expect(apiClient.get).toHaveBeenCalledWith('/api/story/42')
    expect(story.selectedTopicId).toBe('3')
    expect(story.topics?.[0].title).toBe('归来的继承人')
  })
})
