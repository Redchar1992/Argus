import { webcrypto } from 'node:crypto'

import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as chapterEvents from '@/api/chapter-events'
import * as chapterApi from '@/api/chapter'
import { useChapterStore } from '@/stores/chapter'
import type {
  ChapterDetail,
  ChapterStreamEvent,
  RewriteProposal,
} from '@/types'
import {
  getChapterStreamCursor,
  saveChapterStreamCursor,
  setStoredAuth,
} from '@/utils/storage'

vi.mock('@/api/chapter', () => ({
  listStoryChapters: vi.fn(),
  getStoryChapter: vi.fn(),
  getChapter: vi.fn(),
  getChapterPlan: vi.fn(),
  createChapterPlan: vi.fn(),
  approveChapterPlan: vi.fn(),
  generateChapter: vi.fn(),
  saveChapterContent: vi.fn(),
  rewriteChapterSelection: vi.fn(),
  listRewriteProposals: vi.fn(),
  acceptRewriteProposal: vi.fn(),
  rejectRewriteProposal: vi.fn(),
  regenerateRewriteProposal: vi.fn(),
  listChapterVersions: vi.fn(),
  compareChapterVersions: vi.fn(),
  restoreChapterVersion: vi.fn(),
  approveChapter: vi.fn(),
  requestChapterChanges: vi.fn(),
}))

vi.mock('@/api/chapter-events', () => ({
  subscribeChapterEvents: vi.fn(() => ({
    close: vi.fn(),
    done: new Promise<void>(() => undefined),
    lastEventId: () => '',
  })),
}))

function detail(patch: Partial<ChapterDetail> = {}): ChapterDetail {
  return {
    id: 301,
    chapterId: 301,
    storyId: 42,
    chapterNo: 1,
    title: '雨夜归来',
    status: 'REVIEW_REQUIRED',
    wordCount: 0,
    currentVersionId: 901,
    currentVersionNo: 1,
    contentHash: 'base-sha256',
    planApproved: true,
    planStatus: 'APPROVED',
    content: '他推门。',
    mechanicalErrors: [],
    revisionCount: 0,
    maxRevisions: 2,
    ...patch,
  }
}

function event(
  type: string,
  sequence: number,
  data: Record<string, unknown> = {},
  patch: Partial<ChapterStreamEvent> = {},
): ChapterStreamEvent {
  return {
    eventId: `redis-${sequence}`,
    taskId: 700,
    storyId: 42,
    chapterId: 301,
    chapterNo: 1,
    type,
    sequence,
    status: 'RUNNING',
    progress: sequence,
    data,
    ...patch,
  }
}

function proposal(patch: Partial<RewriteProposal> = {}): RewriteProposal {
  return {
    proposalId: 808,
    chapterId: 301,
    chapterVersionId: 901,
    baseVersionId: 901,
    startOffset: 0,
    endOffset: 3,
    selectedTextHash: '',
    originalText: '他推门',
    replacementText: '他猛地撞开门',
    reason: '增强动作冲突',
    status: 'READY',
    stale: false,
    ...patch,
  }
}

describe('chapter store editor and stream safety', () => {
  beforeEach(() => {
    Object.defineProperty(globalThis, 'crypto', {
      value: webcrypto,
      configurable: true,
    })
    window.localStorage.clear()
    window.sessionStorage.clear()
    setStoredAuth({ token: 'jwt', userId: 10001 })
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(chapterApi.listStoryChapters).mockResolvedValue([])
    vi.mocked(chapterApi.getChapterPlan).mockResolvedValue(null)
    vi.mocked(chapterApi.listRewriteProposals).mockResolvedValue([])
    vi.mocked(chapterApi.listChapterVersions).mockResolvedValue([])
  })

  it('keeps the newest chapter when rapid switches resolve out of order', async () => {
    let resolveFirstChapter!: (value: ChapterDetail) => void
    vi.mocked(chapterApi.getStoryChapter).mockImplementation((_storyId, chapterNo) => {
      if (chapterNo === 1) {
        return new Promise((resolve) => {
          resolveFirstChapter = resolve
        })
      }
      return Promise.resolve(
        detail({
          id: 302,
          chapterId: 302,
          chapterNo: 2,
          title: '第二章',
          currentVersionId: 902,
          currentVersionNo: 2,
          contentHash: 'chapter-2-hash',
          content: '第二章正文',
        }),
      )
    })

    const store = useChapterStore()
    const firstLoad = store.loadChapter(42, 1)
    const secondLoad = store.loadChapter(42, 2)
    await secondLoad

    resolveFirstChapter(detail({ content: '迟到的第一章正文' }))
    await firstLoad

    expect(store.currentChapter).toMatchObject({ chapterId: 302, chapterNo: 2 })
    expect(store.editorContent).toBe('第二章正文')
    expect(store.loadingChapter).toBe(false)
    store.reset()
  })

  it('ignores an old terminal refresh that finishes after switching chapters', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockImplementation(async (_storyId, chapterNo) =>
      chapterNo === 1
        ? detail()
        : detail({
            id: 302,
            chapterId: 302,
            chapterNo: 2,
            title: '第二章',
            currentVersionId: 902,
            currentVersionNo: 2,
            contentHash: 'chapter-2-hash',
            content: '第二章正文',
          }),
    )
    let resolveOldRefresh!: (value: ChapterDetail) => void
    vi.mocked(chapterApi.getChapter).mockReturnValue(
      new Promise((resolve) => {
        resolveOldRefresh = resolve
      }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    const terminalRefresh = store.handleStreamEvent(
      event(
        'HUMAN_REVIEW_REQUIRED',
        8,
        { content: '第一章 AI 终稿' },
        { status: 'REVIEW_REQUIRED' },
      ),
    )
    await store.loadChapter(42, 2)

    resolveOldRefresh(detail({ content: '迟到的第一章服务端终稿' }))
    await terminalRefresh

    expect(store.currentChapter).toMatchObject({ chapterId: 302, chapterNo: 2 })
    expect(store.editorContent).toBe('第二章正文')
    store.reset()
  })

  it('restores a ready rewrite proposal for the current immutable version', async () => {
    saveChapterStreamCursor({
      taskId: 799,
      storyId: 42,
      chapterId: 301,
      chapterNo: 1,
      purpose: 'rewrite',
      lastEventId: 'redis-19',
      lastSequence: 19,
      updatedAt: '2026-07-30T10:00:00.000Z',
    })
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.listRewriteProposals).mockResolvedValue([proposal()])

    const store = useChapterStore()
    await store.loadChapter(42, 1)

    expect(chapterApi.listRewriteProposals).toHaveBeenCalledWith(301)
    expect(store.proposal).toMatchObject({
      proposalId: 808,
      chapterVersionId: 901,
      status: 'READY',
      stale: false,
    })
    expect(chapterEvents.subscribeChapterEvents).not.toHaveBeenCalled()
    expect(getChapterStreamCursor(799)).toBeNull()
    store.reset()
  })

  it('never lets later stream tokens or terminal refresh overwrite a user edit', async () => {
    const initial = detail({
      status: 'PLAN_APPROVED',
      currentVersionId: undefined,
      currentVersionNo: undefined,
      contentHash: undefined,
      content: '',
    })
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(initial)
    vi.mocked(chapterApi.generateChapter).mockResolvedValue({
      taskId: 700,
      chapterId: 301,
      storyId: 42,
      chapterNo: 1,
      status: 'WAITING',
    })
    vi.mocked(chapterApi.getChapter).mockResolvedValue(
      detail({ content: 'AI 完整稿', wordCount: 5 }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.generate()
    await store.handleStreamEvent(event('TOKEN_DELTA', 1, { text: 'AI 初稿' }))
    expect(store.editorContent).toBe('AI 初稿')

    store.updateEditorContent('这是我的人工修改')
    await store.handleStreamEvent(event('TOKEN_DELTA', 2, { text: '继续生成' }))
    await store.handleStreamEvent(
      event(
        'HUMAN_REVIEW_REQUIRED',
        3,
        { content: 'AI 完整稿' },
        { status: 'REVIEW_REQUIRED' },
      ),
    )

    expect(store.editorContent).toBe('这是我的人工修改')
    expect(store.pendingGeneratedContent).toBe('AI 完整稿')
    expect(store.streamDetached).toBe(true)
    store.reset()
  })

  it('resets the token buffer when a revision phase starts', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(
      detail({ status: 'PLAN_APPROVED', currentVersionId: undefined, content: '' }),
    )
    vi.mocked(chapterApi.generateChapter).mockResolvedValue({
      taskId: 700,
      chapterId: 301,
      status: 'WAITING',
    })

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.generate()
    await store.handleStreamEvent(event('TOKEN_DELTA', 1, { text: '初稿' }))
    await store.handleStreamEvent(event('DRAFT_READY', 2, { content: '初稿' }))
    await store.handleStreamEvent(event('REVISION_STARTED', 3))
    await store.handleStreamEvent(event('TOKEN_DELTA', 4, { text: '修订' }))
    await store.handleStreamEvent(event('TOKEN_DELTA', 5, { text: '稿' }))

    expect(store.streamBuffer).toBe('修订稿')
    expect(store.editorContent).toBe('修订稿')
    expect(store.editorContent).not.toContain('初稿修订稿')
    store.reset()
  })

  it('deduplicates replayed events by both event id and monotonic sequence', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(
      detail({ status: 'PLAN_APPROVED', currentVersionId: undefined, content: '' }),
    )
    vi.mocked(chapterApi.generateChapter).mockResolvedValue({
      taskId: 700,
      chapterId: 301,
      status: 'WAITING',
    })

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.generate()
    await store.handleStreamEvent(event('TOKEN_DELTA', 1, { text: 'A' }))
    await store.handleStreamEvent(event('TOKEN_DELTA', 1, { text: 'A' }))
    await store.handleStreamEvent(
      event('TOKEN_DELTA', 1, { text: 'B' }, { eventId: 'another-id' }),
    )

    expect(store.streamBuffer).toBe('A')
    expect(store.streamEvents).toHaveLength(1)
    store.reset()
  })

  it('captures Monaco offsets and hashes the exact selection sent to rewrite', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.rewriteChapterSelection).mockImplementation(
      async (_chapterId, input) =>
        proposal({ selectedTextHash: input.selectedTextHash }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    const selected = await store.captureSelection(0, 3)
    await store.requestRewrite('ENHANCE_CONFLICT')

    expect(selected).toMatchObject({
      start: 0,
      end: 3,
      text: '他推门',
      versionId: 901,
    })
    expect(selected?.hash).toMatch(/^[a-f0-9]{64}$/)
    expect(chapterApi.rewriteChapterSelection).toHaveBeenCalledWith(
      301,
      expect.objectContaining({
        chapterVersionId: 901,
        startOffset: 0,
        endOffset: 3,
        selectedText: '他推门',
        selectedTextHash: selected?.hash,
        action: 'ENHANCE_CONFLICT',
      }),
    )
    expect(store.proposal?.status).toBe('READY')
    store.reset()
  })

  it('marks a delayed AI proposal stale if the Monaco document changed meanwhile', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    let resolveProposal!: (value: RewriteProposal) => void
    vi.mocked(chapterApi.rewriteChapterSelection).mockReturnValue(
      new Promise((resolve) => {
        resolveProposal = resolve
      }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    const selected = await store.captureSelection(0, 3)
    const rewrite = store.requestRewrite('ENHANCE_CONFLICT')
    store.updateEditorContent('她推门。')
    resolveProposal(proposal({ selectedTextHash: selected?.hash }))
    await rewrite

    expect(store.proposal).toMatchObject({
      status: 'STALE',
      stale: true,
    })
    await expect(store.acceptProposal()).rejects.toThrow('已过期')
    store.reset()
  })

  it('saves dirty editor content before requesting a rewrite against the new version', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.saveChapterContent).mockResolvedValue({
      id: 902,
      chapterId: 301,
      versionNo: 2,
      sourceType: 'USER_EDIT',
      content: '他推门。雨落。',
      contentHash: 'saved-before-rewrite-hash',
      changeSummary: '人工修改',
    })
    vi.mocked(chapterApi.rewriteChapterSelection).mockImplementation(
      async (_chapterId, input) =>
        proposal({
          chapterVersionId: 902,
          baseVersionId: 902,
          selectedTextHash: input.selectedTextHash,
        }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    store.updateEditorContent('他推门。雨落。')
    await store.captureSelection(0, 3)
    await store.requestRewrite('ENHANCE_CONFLICT')

    expect(chapterApi.saveChapterContent).toHaveBeenCalledWith(
      301,
      expect.objectContaining({
        baseVersionId: 901,
        content: '他推门。雨落。',
      }),
    )
    expect(chapterApi.rewriteChapterSelection).toHaveBeenCalledWith(
      301,
      expect.objectContaining({ chapterVersionId: 902 }),
    )
    expect(store.selection?.versionId).toBe(902)
    expect(store.proposal?.status).toBe('READY')
    store.reset()
  })

  it('keeps the editor in review after saving instead of demoting it to draft', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.saveChapterContent).mockResolvedValue({
      id: 902,
      chapterId: 301,
      versionNo: 2,
      sourceType: 'USER_EDIT',
      content: '人工修改后的正文',
      contentHash: 'human-edit-hash',
      changeSummary: '人工修改',
    })

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    store.updateEditorContent('人工修改后的正文')
    await store.saveNow()

    expect(store.currentChapter?.status).toBe('REVIEW_REQUIRED')
    expect(store.currentChapter?.currentVersionId).toBe(902)
    expect(store.saveState).toBe('saved')
    store.reset()
  })

  it('waits for a dirty chapter save before switching to chapter B', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockImplementation(async (_storyId, chapterNo) =>
      chapterNo === 1
        ? detail({ chapterNo: 1, chapterId: 301, id: 301, currentVersionId: 901 })
        : detail({
            chapterNo: 2,
            chapterId: 302,
            id: 302,
            title: '第二章',
            currentVersionId: 902,
            currentVersionNo: 2,
            contentHash: 'chapter-b-hash',
            content: '第二章原文',
          }),
    )
    let resolveOldSave!: (value: Awaited<ReturnType<typeof chapterApi.saveChapterContent>>) => void
    vi.mocked(chapterApi.saveChapterContent).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOldSave = resolve
      }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    store.updateEditorContent('第一章延迟保存的修改')
    const oldSave = store.saveNow()
    const switchingChapter = store.loadChapter(42, 2)

    await Promise.resolve()
    expect(store.currentChapter?.chapterNo).toBe(1)
    expect(store.editorContent).toBe('第一章延迟保存的修改')
    expect(chapterApi.getStoryChapter).toHaveBeenCalledTimes(1)
    resolveOldSave({
      id: 999,
      chapterId: 301,
      versionNo: 99,
      sourceType: 'USER_EDIT',
      content: '第一章延迟保存的修改',
      contentHash: 'chapter-a-new-hash',
      changeSummary: '第一章修改',
    })
    await Promise.all([oldSave, switchingChapter])

    expect(store.currentChapter).toMatchObject({
      chapterId: 302,
      chapterNo: 2,
      currentVersionId: 902,
      currentVersionNo: 2,
      contentHash: 'chapter-b-hash',
    })
    expect(store.editorContent).toBe('第二章原文')
    expect(store.saveState).toBe('saved')
    store.reset()
  })

  it('refreshes authoritative review state after a rewrite task fails', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.rewriteChapterSelection).mockResolvedValue({
      taskId: 700,
      chapterId: 301,
      storyId: 42,
      chapterNo: 1,
      status: 'WAITING',
    })
    vi.mocked(chapterApi.getChapter).mockResolvedValue(
      detail({ status: 'REVIEW_REQUIRED' }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.captureSelection(0, 3)
    await store.requestRewrite('ENHANCE_CONFLICT')
    await store.handleStreamEvent(
      event('TASK_FAILED', 21, {}, { status: 'FAILED', errorMessage: '改写服务失败' }),
    )

    expect(chapterApi.getChapter).toHaveBeenCalledWith(301)
    expect(store.currentChapter?.status).toBe('REVIEW_REQUIRED')
    expect(store.streamError).toBe('改写服务失败')
    store.reset()
  })

  it('keeps a partial generation draft retryable after task failure', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.generateChapter).mockResolvedValue({
      taskId: 700,
      chapterId: 301,
      storyId: 42,
      chapterNo: 1,
      status: 'WAITING',
    })
    vi.mocked(chapterApi.getChapter).mockResolvedValue(
      detail({ status: 'FAILED', content: '已保留的部分草稿' }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.generate()
    await store.handleStreamEvent(
      event('TASK_FAILED', 22, {}, { status: 'FAILED', errorMessage: '生成服务失败' }),
    )

    expect(store.currentChapter).toMatchObject({
      status: 'FAILED',
      currentVersionId: 901,
      content: '已保留的部分草稿',
    })
    expect(store.editorContent).toBe('已保留的部分草稿')
    expect(store.canRetryGeneration).toBe(true)
    store.reset()
  })

  it('resumes an interrupted rewrite from its persisted SSE cursor after reload', async () => {
    saveChapterStreamCursor({
      taskId: 799,
      storyId: 42,
      chapterId: 301,
      chapterNo: 1,
      purpose: 'rewrite',
      lastEventId: 'redis-19',
      lastSequence: 19,
      updatedAt: '2026-07-30T10:00:00.000Z',
    })
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())

    const store = useChapterStore()
    await store.loadChapter(42, 1)

    expect(chapterEvents.subscribeChapterEvents).toHaveBeenCalledWith(
      799,
      expect.objectContaining({ lastEventId: 'redis-19' }),
    )
    expect(store.streamPurpose).toBe('rewrite')
    expect(getChapterStreamCursor(799)?.lastSequence).toBe(19)
    store.reset()
  })

  it('keeps mechanical errors after reload and a terminal authoritative refresh', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(
      detail({ mechanicalErrors: ['重载后仍存在的标点错误'] }),
    )
    vi.mocked(chapterApi.getChapter).mockResolvedValue(
      detail({ mechanicalErrors: ['服务端保存的未闭合引号'] }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    expect(store.currentChapter?.mechanicalErrors).toEqual(['重载后仍存在的标点错误'])

    await store.handleStreamEvent(
      event(
        'HUMAN_REVIEW_REQUIRED',
        20,
        { content: '他推门。', mechanicalErrors: ['事件中的临时错误'] },
        { status: 'REVIEW_REQUIRED' },
      ),
    )
    expect(store.currentChapter?.mechanicalErrors).toEqual(['服务端保存的未闭合引号'])
    store.reset()
  })

  it('submits review notes and resumes the revision task stream', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.requestChapterChanges).mockResolvedValue({
      taskId: 755,
      chapterId: 301,
      storyId: 42,
      chapterNo: 1,
      status: 'WAITING',
    })

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.requestAiChanges('  补足第二场的证据保全动作。  ')

    expect(chapterApi.requestChapterChanges).toHaveBeenCalledWith(
      301,
      '补足第二场的证据保全动作。',
    )
    expect(store.currentChapter).toMatchObject({
      status: 'GENERATING',
      activeTaskId: 755,
      activeTaskType: 'CHAPTER_FINALIZE',
    })
    expect(store.streamPurpose).toBe('finalize')
    expect(chapterEvents.subscribeChapterEvents).toHaveBeenCalledWith(755, expect.any(Object))
    store.reset()
  })

  it('blocks local rewrite while a full-chapter AI revision is streaming', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail())
    vi.mocked(chapterApi.requestChapterChanges).mockResolvedValue({
      taskId: 755,
      chapterId: 301,
      storyId: 42,
      chapterNo: 1,
      status: 'WAITING',
    })

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.captureSelection(0, 3)
    await store.requestAiChanges('补足第二场的证据保全动作。')

    await expect(store.requestRewrite('ENHANCE_CONFLICT')).rejects.toThrow(
      '当前 AI 任务尚未完成',
    )
    expect(chapterApi.rewriteChapterSelection).not.toHaveBeenCalled()
    store.reset()
  })

  it('clears terminal plan stream state so planning can be retried', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(
      detail({
        status: 'EMPTY',
        currentVersionId: undefined,
        currentVersionNo: undefined,
        contentHash: undefined,
        content: '',
      }),
    )
    vi.mocked(chapterApi.createChapterPlan)
      .mockResolvedValueOnce({
        taskId: 700,
        chapterId: 301,
        storyId: 42,
        chapterNo: 1,
        status: 'WAITING',
      })
      .mockResolvedValueOnce({
        taskId: 701,
        chapterId: 301,
        storyId: 42,
        chapterNo: 1,
        status: 'WAITING',
      })
    vi.mocked(chapterApi.getChapter).mockResolvedValue(
      detail({
        status: 'FAILED',
        currentVersionId: undefined,
        currentVersionNo: undefined,
        contentHash: undefined,
        content: '',
      }),
    )

    const store = useChapterStore()
    await store.loadChapter(42, 1)
    await store.createPlan()
    await store.handleStreamEvent(
      event('TASK_FAILED', 30, {}, { status: 'FAILED', errorMessage: '计划生成失败' }),
    )

    expect(store.currentTask).toBeUndefined()
    expect(store.streamPurpose).toBeUndefined()
    expect(store.streamState).toBe('closed')
    expect(store.isStreaming).toBe(false)
    expect(store.planning).toBe(false)

    await store.createPlan()
    expect(chapterApi.createChapterPlan).toHaveBeenCalledTimes(2)
    expect(store.streamPurpose).toBe('plan')
    store.reset()
  })

  it('blocks restore after approval without calling the backend', async () => {
    vi.mocked(chapterApi.getStoryChapter).mockResolvedValue(detail({ status: 'APPROVED' }))

    const store = useChapterStore()
    await store.loadChapter(42, 1)

    await expect(store.restoreVersion(900)).rejects.toThrow('已批准章节不可恢复')
    expect(chapterApi.restoreChapterVersion).not.toHaveBeenCalled()
    store.reset()
  })
})
