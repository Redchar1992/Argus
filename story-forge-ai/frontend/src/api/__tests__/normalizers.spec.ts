import { describe, expect, it } from 'vitest'

import {
  normalizeGenerateResult,
  normalizeStory,
  normalizeTopics,
} from '@/api/normalizers'

describe('AI response normalizers', () => {
  it('normalizes numeric topic ids and four-dimensional score reasons', () => {
    const topics = normalizeTopics({
      topics: [
        {
          id: 1,
          title: '离婚当天，我继承百亿集团',
          hook: '落魄妻子竟是失踪继承人',
          summary: '离婚签字后，豪门车队停在她面前。',
          score: 92,
          scoreReasons: {
            conflict: { score: 95, reason: '离婚现场直接引爆核心冲突' },
            reversal: { score: 94, reason: '被抛弃者反转为集团继承人' },
            emotionalValue: { score: 90, reason: '提供强烈的逆袭满足感' },
            shortDramaFit: { score: 89, reason: '开场快、节点密、易切集' },
          },
        },
      ],
    })

    expect(topics).toHaveLength(1)
    expect(topics[0].id).toBe('1')
    expect(topics[0].scoreDetails).toEqual([
      expect.objectContaining({ dimension: 'conflict', label: '冲突强度', score: 95 }),
      expect.objectContaining({ dimension: 'reversal', label: '反转张力', score: 94 }),
      expect.objectContaining({ dimension: 'emotionalValue', label: '情绪价值', score: 90 }),
      expect.objectContaining({ dimension: 'shortDramaFit', label: '短剧适配', score: 89 }),
    ])
    expect(topics[0].reasons).toContain('被抛弃者反转为集团继承人')
  })

  it('reads generatedTopics and selectedTopic when returned as JSON strings', () => {
    const story = normalizeStory({
      id: 10001,
      title: '都市复仇',
      genre: '都市情感',
      generatedTopics: JSON.stringify([
        {
          id: 7,
          title: '消失的继承人',
          hook: '身份反转',
          summary: '她回来夺回一切。',
          score: 91,
        },
      ]),
      selectedTopic: JSON.stringify({
        id: 7,
        title: '消失的继承人',
        hook: '身份反转',
        summary: '她回来夺回一切。',
        score: 91,
      }),
    })

    expect(story.topics).toHaveLength(1)
    expect(story.selectedTopicId).toBe('7')
  })

  it('unwraps generated topic arrays nested in a result field', () => {
    const result = normalizeGenerateResult({
      taskId: 8,
      result: JSON.stringify([
        { id: 1, title: '选题一', hook: '反转', summary: '梗概', score: 88 },
      ]),
    })

    expect(result.taskId).toBe(8)
    expect(result.topics[0]).toMatchObject({ id: '1', title: '选题一', score: 88 })
  })
})
