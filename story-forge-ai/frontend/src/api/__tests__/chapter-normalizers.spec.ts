import { describe, expect, it } from 'vitest'

import {
  normalizeChapterDetail,
  normalizeChapterPlanEnvelope,
  normalizeChapterStatus,
  normalizeChapterStreamEvent,
  normalizeRewriteProposal,
} from '@/api/chapter-normalizers'

const plan = {
  chapterTitle: '雨夜归来',
  chapterGoal: '主角拿到第一份证据',
  openingHook: '离婚协议被撕碎',
  endingHook: '门外站着失踪三年的证人',
  targetLength: 1800,
  scenes: Array.from({ length: 3 }, (_, index) => ({
    sceneNo: index + 1,
    location: '集团会议室',
    time: '夜晚',
    characters: ['林晚', '陈宇'],
    protagonistGoal: '拿到账本',
    opposingForce: '陈宇封锁会议室',
    visibleConflict: '双方争夺保险柜钥匙',
    informationRevealed: '账本有两份',
    emotionalChange: '从克制到决绝',
    setupOrPayoff: '回收旧钥匙伏笔',
    exitHook: '证人敲门',
    sceneFunction: index === 2 ? '反转' : '升级',
  })),
}

describe('chapter contract normalizers', () => {
  it('normalizes the real ChapterResponse including plan lock and current-version review', () => {
    const chapter = normalizeChapterDetail({
      id: 301,
      storyId: 42,
      chapterNo: 1,
      title: '雨夜归来',
      status: 'FINALIZING',
      planStatus: 'APPROVED',
      planHash: 'plan-sha256',
      plan,
      wordCount: 1760,
      currentVersionId: 901,
      currentVersion: {
        id: 901,
        chapterId: 301,
        versionNo: 4,
        sourceType: 'AI_REVISION',
        content: '她在雨里推开会议室的门。',
        contentHash: 'content-sha256',
        review: {
          total: 88,
          mechanicalErrors: ['正文包含未闭合引号'],
          outlineCompletion: { score: 18 },
          continuity: { score: 19 },
          conflictProgression: { score: 18 },
          emotionAndVisuals: { score: 13 },
          hooks: { score: 12 },
          languageQuality: { score: 8 },
        },
      },
      activeTaskId: 7001,
      activeTaskStatus: 'RUNNING',
      activeTaskType: 'CHAPTER_FINALIZE',
    })

    expect(chapter).toMatchObject({
      chapterId: 301,
      storyId: 42,
      chapterNo: 1,
      status: 'GENERATING',
      planApproved: true,
      planStatus: 'APPROVED',
      planHash: 'plan-sha256',
      content: '她在雨里推开会议室的门。',
      contentHash: 'content-sha256',
      currentVersionNo: 4,
      activeTaskId: 7001,
      activeTaskStatus: 'RUNNING',
      activeTaskType: 'CHAPTER_FINALIZE',
    })
    expect(chapter.plan?.scenes).toHaveLength(3)
    expect(chapter.review?.total).toBe(88)
    expect(chapter.review?.dimensions).toHaveLength(6)
    expect(chapter.review?.mechanicalErrors).toEqual(['正文包含未闭合引号'])
    expect(chapter.mechanicalErrors).toEqual(['正文包含未闭合引号'])
  })

  it('derives plan approval and hash from the ChapterResponse plan envelope', () => {
    expect(
      normalizeChapterPlanEnvelope({
        id: 301,
        status: 'PLAN_APPROVED',
        planStatus: 'APPROVED',
        planHash: 'plan-sha256',
        plan,
      }),
    ).toMatchObject({
      chapterId: 301,
      status: 'PLAN_APPROVED',
      approved: true,
      planHash: 'plan-sha256',
    })
  })

  it('keeps backend READY proposals actionable and maps originalTextHash', () => {
    expect(
      normalizeRewriteProposal({
        id: 808,
        chapterId: 301,
        baseVersionId: 901,
        startOffset: 2,
        endOffset: 8,
        originalText: '推开门',
        originalTextHash: 'selection-hash',
        replacementText: '猛地撞开那扇门',
        reason: '增强动作冲突',
        status: 'READY',
      }),
    ).toMatchObject({
      proposalId: 808,
      chapterVersionId: 901,
      selectedTextHash: 'selection-hash',
      status: 'READY',
      stale: false,
    })
  })

  it('normalizes SSE metadata and clamps progress', () => {
    const event = normalizeChapterStreamEvent(
      {
        task_id: 7001,
        type: 'token_delta',
        sequence: 5,
        progress: 120,
        data: { text: '雨' },
      },
      { id: 'redis-5' },
    )

    expect(event).toMatchObject({
      eventId: 'redis-5',
      taskId: 7001,
      type: 'TOKEN_DELTA',
      sequence: 5,
      progress: 100,
      data: { text: '雨' },
    })
    expect(normalizeChapterStatus('FINALIZING')).toBe('GENERATING')
  })
})
