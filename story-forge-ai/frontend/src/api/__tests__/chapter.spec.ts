import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  acceptRewriteProposal,
  approveChapter,
  approveChapterPlan,
  compareChapterVersions,
  createChapterPlan,
  generateChapter,
  listChapterVersions,
  listRewriteProposals,
  requestChapterChanges,
  rewriteChapterSelection,
  saveChapterContent,
} from '@/api/chapter'
import { apiClient } from '@/utils/request'

vi.mock('@/utils/request', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('chapter API contract', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates, approves, and generates a chapter plan using optimistic planHash', async () => {
    vi.mocked(apiClient.post)
      .mockResolvedValueOnce({ taskId: 701, chapterId: 301, status: 'WAITING' })
      .mockResolvedValueOnce({
        id: 301,
        storyId: 42,
        chapterNo: 1,
        status: 'PLAN_APPROVED',
        planStatus: 'APPROVED',
      })
      .mockResolvedValueOnce({ taskId: 702, chapterId: 301, status: 'WAITING' })

    await createChapterPlan(42, 1, 1800)
    const approved = await approveChapterPlan(42, 1, 'plan-sha256')
    await generateChapter(42, 1)

    expect(apiClient.post).toHaveBeenNthCalledWith(
      1,
      '/api/stories/42/chapters/1/plan',
      { targetLength: 1800 },
    )
    expect(apiClient.post).toHaveBeenNthCalledWith(
      2,
      '/api/stories/42/chapters/1/plan/approve',
      { planHash: 'plan-sha256' },
    )
    expect(apiClient.post).toHaveBeenNthCalledWith(
      3,
      '/api/stories/42/chapters/1/generate',
      {},
    )
    expect(approved.planApproved).toBe(true)
  })

  it('saves against the base version hash instead of sending a new-content hash', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({
      id: 902,
      chapterId: 301,
      versionNo: 5,
      sourceType: 'USER_EDIT',
      content: '人工修改后的正文',
    })

    await saveChapterContent(301, {
      baseVersionId: '901',
      baseContentHash: 'base-sha256',
      content: '人工修改后的正文',
    })

    expect(apiClient.put).toHaveBeenCalledWith('/api/chapters/301/content', {
      baseVersionId: 901,
      baseContentHash: 'base-sha256',
      content: '人工修改后的正文',
    })
  })

  it('treats rewrite and chapter approval responses as asynchronous tasks', async () => {
    vi.mocked(apiClient.post)
      .mockResolvedValueOnce({ taskId: 703, chapterId: 301, status: 'WAITING' })
      .mockResolvedValueOnce({ taskId: 704, chapterId: 301, status: 'WAITING' })

    const rewrite = await rewriteChapterSelection(301, {
      chapterVersionId: 901,
      startOffset: 0,
      endOffset: 3,
      selectedText: '她推门',
      selectedTextHash: 'selection-sha256',
      action: 'ENHANCE_CONFLICT',
      customInstruction: '',
    })
    const approval = await approveChapter(301)

    expect(rewrite).toMatchObject({ taskId: 703, chapterId: 301 })
    expect(approval).toMatchObject({ taskId: 704, chapterId: 301 })
    expect(apiClient.post).toHaveBeenLastCalledWith('/api/chapters/301/approve', {
      approved: true,
    })
  })

  it('submits concrete review notes when asking AI to revise the chapter', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({
      taskId: 705,
      chapterId: 301,
      status: 'WAITING',
    })

    const task = await requestChapterChanges(301, '  第二场补足证据保全动作。  ')

    expect(apiClient.post).toHaveBeenCalledWith('/api/chapters/301/approve', {
      approved: false,
      notes: '第二场补足证据保全动作。',
    })
    expect(task).toMatchObject({ taskId: 705, chapterId: 301 })
  })

  it('accepts proposals and compares immutable versions with numeric query values', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({
      id: 903,
      chapterId: 301,
      versionNo: 6,
      sourceType: 'AI_REWRITE_ACCEPTED',
      content: '建议被接受',
    })
    vi.mocked(apiClient.get)
      .mockResolvedValueOnce([
        { id: 903, chapterId: 301, versionNo: 6, content: '建议被接受' },
      ])
      .mockResolvedValueOnce({
        fromVersion: { id: 901, chapterId: 301, versionNo: 4, content: '原文' },
        toVersion: { id: 903, chapterId: 301, versionNo: 6, content: '建议被接受' },
        fromChangedText: '原文',
        toChangedText: '建议被接受',
      })

    await acceptRewriteProposal(301, 808, '901')
    const versions = await listChapterVersions(301)
    const comparison = await compareChapterVersions(301, '901', '903')

    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/chapters/301/rewrite-proposals/808/accept',
      { baseVersionId: 901 },
    )
    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/api/chapters/301/versions')
    expect(apiClient.get).toHaveBeenNthCalledWith(
      2,
      '/api/chapters/301/versions/compare',
      { params: { fromVersionId: 901, toVersionId: 903 } },
    )
    expect(versions[0].versionNo).toBe(6)
    expect(comparison.fromVersion?.id).toBe(901)
  })

  it('lists ready rewrite proposals so refresh can restore pending review state', async () => {
    vi.mocked(apiClient.get).mockResolvedValue([
      {
        proposal_id: 808,
        chapter_id: 301,
        chapter_version_id: 901,
        start_offset: 0,
        end_offset: 3,
        original_text: '她推门',
        replacement_text: '她猛地撞开门',
        status: 'READY',
      },
    ])

    const proposals = await listRewriteProposals(301)

    expect(apiClient.get).toHaveBeenCalledWith('/api/chapters/301/rewrite-proposals')
    expect(proposals).toEqual([
      expect.objectContaining({
        proposalId: 808,
        chapterVersionId: 901,
        startOffset: 0,
        endOffset: 3,
        status: 'READY',
      }),
    ])
  })
})
