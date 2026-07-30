import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as chapterApi from '@/api/chapter'
import { normalizeChapterPlan, normalizeChapterReview, normalizeRewriteProposal } from '@/api/chapter-normalizers'
import {
  subscribeChapterEvents,
  type ChapterEventSubscription,
} from '@/api/chapter-events'
import type {
  ChapterDetail,
  ChapterListItem,
  ChapterPlan,
  ChapterStreamEvent,
  ChapterTask,
  ChapterVersion,
  ChapterVersionComparison,
  EditorSelection,
  EntityId,
  RewriteAction,
  RewriteProposal,
  RewriteSelectionInput,
  SaveState,
  StreamConnectionState,
} from '@/types'
import { countChapterWords, sha256Hex } from '@/utils/chapter'
import {
  findChapterStreamCursor,
  getChapterStreamCursor,
  removeChapterStreamCursor,
  saveChapterStreamCursor,
} from '@/utils/storage'

const AUTOSAVE_DELAY_MS = 1_200

type StreamPurpose = 'plan' | 'generate' | 'rewrite' | 'finalize'

interface RewriteSnapshot {
  selection: EditorSelection
  input: RewriteSelectionInput
}

function emptyChapter(storyId: EntityId, chapterNo: number): ChapterDetail {
  return {
    id: undefined,
    chapterId: '',
    storyId,
    chapterNo,
    title: `第 ${chapterNo} 章`,
    status: 'EMPTY',
    wordCount: 0,
    planApproved: false,
    content: '',
    mechanicalErrors: [],
    revisionCount: 0,
    maxRevisions: 2,
  }
}

function isChapterTask(value: ChapterTask | RewriteProposal): value is ChapterTask {
  return 'taskId' in value
}

export const useChapterStore = defineStore('chapter', () => {
  const catalogs = ref<Record<string, ChapterListItem[]>>({})
  const currentChapter = ref<ChapterDetail>()
  const currentPlan = ref<ChapterPlan>()
  const currentTask = ref<ChapterTask>()
  const versions = ref<ChapterVersion[]>([])
  const versionComparison = ref<ChapterVersionComparison>()
  const proposal = ref<RewriteProposal>()
  const selection = ref<EditorSelection>()

  const editorContent = ref('')
  const persistedContent = ref('')
  const editorRevision = ref(0)
  const saveState = ref<SaveState>('idle')
  const saveError = ref('')

  const loadingCatalog = ref(false)
  const loadingChapter = ref(false)
  const planning = ref(false)
  const approvingPlan = ref(false)
  const generating = ref(false)
  const rewriting = ref(false)
  const loadingVersions = ref(false)
  const comparingVersions = ref(false)
  const restoringVersion = ref(false)
  const approvingChapter = ref(false)
  const requestingChanges = ref(false)

  const streamState = ref<StreamConnectionState>('idle')
  const streamPurpose = ref<StreamPurpose>()
  const streamProgress = ref(0)
  const streamCurrentNode = ref('')
  const streamBuffer = ref('')
  const streamDetached = ref(false)
  const streamError = ref('')
  const streamEvents = ref<ChapterStreamEvent[]>([])
  const pendingGeneratedContent = ref('')

  let autosaveTimer: ReturnType<typeof setTimeout> | undefined
  let saveInFlight: Promise<ChapterVersion | null> | null = null
  let subscription: ChapterEventSubscription | undefined
  let subscriptionToken: symbol | undefined
  let streamStartEditorRevision = 0
  let streamCanMirror = false
  let rewriteSnapshot: RewriteSnapshot | undefined
  let chapterLoadGeneration = 0
  let chapterLoadRequestGeneration = 0
  const seenEventIds = new Set<string>()
  const lastSequenceByTask = new Map<string, number>()

  const wordCount = computed(() => countChapterWords(editorContent.value))
  const isDirty = computed(
    () =>
      ['dirty', 'saving', 'error'].includes(saveState.value) &&
      editorContent.value !== persistedContent.value,
  )
  const canEdit = computed(() => Boolean(currentChapter.value?.currentVersionId))
  const canRetryGeneration = computed(
    () =>
      currentChapter.value?.status === 'FAILED' &&
      currentChapter.value.planApproved &&
      Boolean(currentChapter.value.currentVersionId),
  )
  const isStreaming = computed(() =>
    ['connecting', 'connected', 'reconnecting'].includes(streamState.value),
  )

  async function loadCatalog(storyId: EntityId) {
    loadingCatalog.value = true
    try {
      const chapters = await chapterApi.listStoryChapters(storyId)
      catalogs.value[String(storyId)] = chapters
      return chapters
    } finally {
      loadingCatalog.value = false
    }
  }

  function updateCatalogEntry(detail: ChapterDetail) {
    const key = String(detail.storyId)
    const items = [...(catalogs.value[key] ?? [])]
    const index = items.findIndex((item) => item.chapterNo === detail.chapterNo)
    const summary: ChapterListItem = {
      id: detail.chapterId,
      chapterId: detail.chapterId,
      storyId: detail.storyId,
      chapterNo: detail.chapterNo,
      title: detail.title,
      status: detail.status,
      wordCount: detail.wordCount || countChapterWords(detail.content),
      currentVersionId: detail.currentVersionId,
      activeTaskId: detail.activeTaskId,
      activeTaskStatus: detail.activeTaskStatus,
      activeTaskType: detail.activeTaskType,
      planApproved: detail.planApproved,
      planStatus: detail.planStatus,
      planHash: detail.planHash,
      approvedTime: detail.approvedTime,
      updatedTime: detail.updatedTime,
    }
    if (index >= 0) items.splice(index, 1, summary)
    else items.push(summary)
    catalogs.value[key] = items.sort((left, right) => left.chapterNo - right.chapterNo)
  }

  function applyAuthoritativeContent(content: string, force = false) {
    if (
      force ||
      (!isDirty.value && !streamDetached.value && editorRevision.value === streamStartEditorRevision)
    ) {
      editorContent.value = content
      persistedContent.value = content
      pendingGeneratedContent.value = ''
      saveState.value = content ? 'saved' : 'idle'
      return
    }
    if (content && content !== editorContent.value) {
      pendingGeneratedContent.value = content
      streamDetached.value = true
    }
  }

  function applyChapter(detail: ChapterDetail, forceContent = false) {
    currentChapter.value = detail
    currentPlan.value = detail.plan ?? currentPlan.value
    applyAuthoritativeContent(detail.content, forceContent)
    updateCatalogEntry(detail)
  }

  async function refreshCurrentChapter(forceContent = false) {
    const current = currentChapter.value
    if (!current) return undefined
    const refreshGeneration = chapterLoadGeneration
    const expectedStoryId = String(current.storyId)
    const expectedChapterNo = current.chapterNo
    const expectedChapterId = String(current.chapterId ?? '')
    const detail = current.chapterId
      ? await chapterApi.getChapter(current.chapterId)
      : await chapterApi.getStoryChapter(current.storyId, current.chapterNo)
    const active = currentChapter.value
    if (
      refreshGeneration !== chapterLoadGeneration ||
      !active ||
      String(active.storyId) !== expectedStoryId ||
      active.chapterNo !== expectedChapterNo ||
      String(active.chapterId ?? '') !== expectedChapterId
    ) {
      return undefined
    }
    if (detail) applyChapter(detail, forceContent)
    return detail
  }

  async function loadChapter(storyId: EntityId, chapterNo: number) {
    const requestGeneration = ++chapterLoadRequestGeneration
    if (currentChapter.value && isDirty.value) {
      await saveNow()
      if (requestGeneration !== chapterLoadRequestGeneration) return undefined
      if (isDirty.value) {
        throw new Error('当前章节尚未保存，不能切换章节。')
      }
    }
    if (requestGeneration !== chapterLoadRequestGeneration) return undefined
    const loadGeneration = ++chapterLoadGeneration
    stopStream()
    clearAutosave()
    loadingChapter.value = true
    resetEditorState()
    currentChapter.value = undefined
    try {
      const [detail, planEnvelope] = await Promise.all([
        chapterApi.getStoryChapter(storyId, chapterNo),
        chapterApi.getChapterPlan(storyId, chapterNo),
      ])
      if (
        requestGeneration !== chapterLoadRequestGeneration ||
        loadGeneration !== chapterLoadGeneration
      ) {
        return undefined
      }
      const chapter = detail ?? emptyChapter(storyId, chapterNo)
      if (planEnvelope?.chapterId && !chapter.chapterId) {
        chapter.chapterId = planEnvelope.chapterId
        chapter.id = planEnvelope.chapterId
      }
      if (planEnvelope?.plan) chapter.plan = planEnvelope.plan
      if (planEnvelope?.approved) chapter.planApproved = true
      if (planEnvelope?.planHash) chapter.planHash = planEnvelope.planHash
      currentChapter.value = chapter
      currentPlan.value = chapter.plan
      editorContent.value = chapter.content
      persistedContent.value = chapter.content
      saveState.value = chapter.content ? 'saved' : 'idle'
      updateCatalogEntry(chapter)

      if (chapter.chapterId && chapter.currentVersionId !== undefined) {
        const proposals = await chapterApi.listRewriteProposals(chapter.chapterId)
        if (
          requestGeneration !== chapterLoadRequestGeneration ||
          loadGeneration !== chapterLoadGeneration
        ) {
          return undefined
        }
        const readyProposal = proposals.find((candidate) => {
          const proposalVersion =
            candidate.chapterVersionId ?? candidate.baseVersionId
          return (
            candidate.status === 'READY' &&
            candidate.startOffset !== undefined &&
            candidate.endOffset !== undefined &&
            String(proposalVersion) === String(chapter.currentVersionId) &&
            chapter.content.slice(candidate.startOffset, candidate.endOffset) ===
              candidate.originalText
          )
        })
        if (readyProposal) applyRewriteProposal(readyProposal)
      }

      const savedCursor = findChapterStreamCursor(storyId, chapterNo)
      const resumablePurpose = savedCursor?.purpose
      const activeTaskId = savedCursor?.taskId ?? chapter.activeTaskId
      const backendPurpose: StreamPurpose | undefined =
        ['WAITING', 'RUNNING'].includes(chapter.activeTaskStatus ?? '')
          ? chapter.activeTaskType === 'CHAPTER_PLAN'
            ? 'plan'
            : chapter.activeTaskType === 'CHAPTER_GENERATE'
              ? 'generate'
              : chapter.activeTaskType === 'CHAPTER_FINALIZE'
                ? 'finalize'
                : undefined
          : undefined
      const cursorPurpose: StreamPurpose | undefined =
        resumablePurpose === 'plan' && chapter.status === 'PLANNING'
          ? 'plan'
          : resumablePurpose === 'generate' && chapter.status === 'GENERATING'
            ? 'generate'
            : resumablePurpose === 'rewrite' &&
                chapter.status !== 'APPROVED' &&
                !proposal.value
              ? 'rewrite'
              : resumablePurpose === 'finalize' && chapter.status !== 'APPROVED'
                ? 'finalize'
                : undefined
      const purpose: StreamPurpose | undefined =
        cursorPurpose ??
        backendPurpose ??
        (chapter.status === 'PLANNING'
          ? 'plan'
          : chapter.status === 'GENERATING'
            ? 'generate'
            : undefined)
      if (activeTaskId && purpose) {
        subscribeToTask(
          {
            taskId: activeTaskId,
            storyId,
            chapterId: chapter.chapterId || undefined,
            chapterNo,
            status: 'RUNNING',
          },
          purpose,
        )
      } else if (savedCursor) {
        removeChapterStreamCursor(savedCursor.taskId)
      }
      return chapter
    } finally {
      if (
        requestGeneration === chapterLoadRequestGeneration &&
        loadGeneration === chapterLoadGeneration
      ) {
        loadingChapter.value = false
      }
    }
  }

  async function createPlan(targetLength?: number) {
    const chapter = requireCurrentChapter()
    planning.value = true
    streamError.value = ''
    try {
      const task = await chapterApi.createChapterPlan(
        chapter.storyId,
        chapter.chapterNo,
        targetLength,
      )
      currentTask.value = task
      currentChapter.value = {
        ...chapter,
        chapterId: task.chapterId ?? chapter.chapterId,
        activeTaskId: task.taskId,
        status: 'PLANNING',
      }
      updateCatalogEntry(currentChapter.value)
      subscribeToTask(task, 'plan')
      return task
    } finally {
      planning.value = false
    }
  }

  async function approvePlan() {
    const chapter = requireCurrentChapter()
    approvingPlan.value = true
    try {
      const detail = await chapterApi.approveChapterPlan(
        chapter.storyId,
        chapter.chapterNo,
        chapter.planHash,
      )
      detail.planApproved = true
      applyChapter(detail, false)
      return detail
    } finally {
      approvingPlan.value = false
    }
  }

  async function generate() {
    const chapter = requireCurrentChapter()
    generating.value = true
    streamBuffer.value = ''
    streamDetached.value = false
    pendingGeneratedContent.value = ''
    streamError.value = ''
    streamStartEditorRevision = editorRevision.value
    streamCanMirror = !editorContent.value.trim()
    try {
      const task = await chapterApi.generateChapter(chapter.storyId, chapter.chapterNo)
      currentTask.value = task
      currentChapter.value = {
        ...chapter,
        chapterId: task.chapterId ?? chapter.chapterId,
        activeTaskId: task.taskId,
        status: 'GENERATING',
      }
      updateCatalogEntry(currentChapter.value)
      subscribeToTask(task, 'generate')
      return task
    } finally {
      generating.value = false
    }
  }

  function subscribeToTask(task: ChapterTask, purpose: StreamPurpose) {
    stopStream()
    currentTask.value = task
    streamPurpose.value = purpose
    streamState.value = 'connecting'
    streamError.value = ''
    const token = Symbol(String(task.taskId))
    subscriptionToken = token
    const cursor = getChapterStreamCursor(task.taskId)
    if (cursor) lastSequenceByTask.set(String(task.taskId), cursor.lastSequence)
    const chapter = currentChapter.value
    saveChapterStreamCursor({
      taskId: task.taskId,
      lastEventId: cursor?.lastEventId ?? '',
      lastSequence: cursor?.lastSequence ?? 0,
      updatedAt: new Date().toISOString(),
      storyId: task.storyId ?? chapter?.storyId,
      chapterId: task.chapterId ?? chapter?.chapterId,
      chapterNo: task.chapterNo ?? chapter?.chapterNo,
      purpose,
    })

    subscription = subscribeChapterEvents(task.taskId, {
      lastEventId: cursor?.lastEventId,
      onStateChange: (state) => {
        if (subscriptionToken === token) streamState.value = state
      },
      onError: (error) => {
        if (subscriptionToken === token) {
          streamError.value = error instanceof Error ? error.message : '章节事件流连接中断'
        }
      },
      onEvent: async (event) => {
        if (subscriptionToken !== token) return
        await handleStreamEvent(event)
      },
    })
    void subscription.done.finally(() => {
      if (subscriptionToken === token) {
        subscription = undefined
        subscriptionToken = undefined
      }
    })
  }

  function shouldAcceptEvent(event: ChapterStreamEvent) {
    const taskKey = String(event.taskId)
    if (event.eventId && seenEventIds.has(event.eventId)) return false
    const lastSequence = lastSequenceByTask.get(taskKey) ?? 0
    if (event.sequence > 0 && event.sequence <= lastSequence) return false
    if (event.eventId) seenEventIds.add(event.eventId)
    if (event.sequence > 0) lastSequenceByTask.set(taskKey, event.sequence)
    saveChapterStreamCursor({
      taskId: event.taskId,
      lastEventId: event.eventId,
      lastSequence: Math.max(lastSequence, event.sequence),
      updatedAt: new Date().toISOString(),
      storyId: event.storyId ?? currentChapter.value?.storyId,
      chapterId: event.chapterId ?? currentChapter.value?.chapterId,
      chapterNo: event.chapterNo ?? currentChapter.value?.chapterNo,
      purpose: streamPurpose.value,
    })
    return true
  }

  async function handleStreamEvent(event: ChapterStreamEvent) {
    if (!shouldAcceptEvent(event)) return
    streamEvents.value = [...streamEvents.value.slice(-99), event]
    if (event.progress !== undefined) streamProgress.value = event.progress
    if (event.currentNode) streamCurrentNode.value = event.currentNode
    if (event.status && currentTask.value) currentTask.value.status = event.status

    if (event.type === 'REVISION_STARTED') {
      streamBuffer.value = ''
      streamCanMirror =
        streamCanMirror &&
        !streamDetached.value &&
        editorRevision.value === streamStartEditorRevision &&
        !isDirty.value
      return
    }

    if (event.type === 'TOKEN_DELTA') {
      const delta = String(event.data.text ?? '')
      streamBuffer.value += delta
      if (
        streamCanMirror &&
        !streamDetached.value &&
        editorRevision.value === streamStartEditorRevision &&
        !isDirty.value
      ) {
        editorContent.value = streamBuffer.value
      }
      return
    }

    if (event.type === 'CHAPTER_PLAN_READY' && event.status !== 'RUNNING') {
      const plan = normalizeChapterPlan(event.data.plan ?? event.data)
      if (plan) currentPlan.value = plan
      if (currentChapter.value) {
        currentChapter.value.plan = plan ?? currentChapter.value.plan
        currentChapter.value.status = 'PLAN_READY'
        currentChapter.value.activeTaskId = undefined
        updateCatalogEntry(currentChapter.value)
      }
      await refreshAfterTerminal(event)
      return
    }

    if (event.type === 'REVIEW_READY' || event.type === 'HUMAN_REVIEW_REQUIRED') {
      const review = normalizeChapterReview(event.data.review)
      if (review && currentChapter.value) currentChapter.value.review = review
      if (currentChapter.value) {
        currentChapter.value.mechanicalErrors = Array.isArray(event.data.mechanicalErrors)
          ? event.data.mechanicalErrors.map(String)
          : currentChapter.value.mechanicalErrors
      }
    }

    if (['DRAFT_READY', 'REVISION_READY', 'HUMAN_REVIEW_REQUIRED', 'FINAL_READY'].includes(event.type)) {
      const content = String(event.data.content ?? '')
      if (content) {
        streamBuffer.value = content
        applyAuthoritativeContent(content)
      }
    }

    if (event.type === 'HUMAN_REVIEW_REQUIRED') {
      if (currentChapter.value) {
        currentChapter.value.status = 'REVIEW_REQUIRED'
        currentChapter.value.activeTaskId = undefined
      }
      await refreshAfterTerminal(event)
      return
    }

    if (event.type === 'REWRITE_PROPOSAL_READY') {
      applyRewriteProposal(normalizeRewriteProposal(event.data))
      rewriting.value = false
      await refreshAfterTerminal(event, false)
      return
    }

    if (event.type === 'FINAL_READY') {
      if (currentChapter.value) {
        currentChapter.value.status = 'APPROVED'
        currentChapter.value.activeTaskId = undefined
      }
      await refreshAfterTerminal(event)
      return
    }

    if (event.type === 'TASK_FAILED') {
      streamError.value = event.errorMessage || 'AI 章节任务执行失败'
      if (
        currentChapter.value &&
        (streamPurpose.value === 'plan' || streamPurpose.value === 'generate')
      ) {
        currentChapter.value.status = 'FAILED'
        currentChapter.value.activeTaskId = undefined
        updateCatalogEntry(currentChapter.value)
      }
      planning.value = false
      generating.value = false
      rewriting.value = false
      approvingChapter.value = false
      requestingChanges.value = false
      await refreshAfterTerminal(event)
    }
  }

  async function refreshAfterTerminal(event: ChapterStreamEvent, refreshChapter = true) {
    finishStream(event.taskId)
    if (refreshChapter) {
      try {
        await refreshCurrentChapter(false)
        if (currentChapter.value) {
          await loadCatalog(currentChapter.value.storyId)
        }
      } catch (error) {
        streamError.value = error instanceof Error ? error.message : '刷新章节最终状态失败'
      }
    }
    planning.value = false
    generating.value = false
    rewriting.value = false
    approvingChapter.value = false
    requestingChanges.value = false
  }

  function finishStream(taskId: EntityId) {
    removeChapterStreamCursor(taskId)
    subscription?.close()
    subscription = undefined
    subscriptionToken = undefined
    streamState.value = 'closed'
    streamPurpose.value = undefined
    currentTask.value = undefined
  }

  function updateEditorContent(content: string) {
    if (content === editorContent.value) return
    editorContent.value = content
    editorRevision.value += 1
    saveState.value = content === persistedContent.value ? 'saved' : 'dirty'
    saveError.value = ''
    if (isStreaming.value) streamDetached.value = true
    if (proposal.value && ['PENDING', 'READY'].includes(proposal.value.status)) {
      proposal.value = {
        ...proposal.value,
        stale: true,
        status: 'STALE',
        staleReason: 'Proposal 返回后正文又被修改，请重新选择文本生成建议。',
      }
    }
    scheduleAutosave()
  }

  function scheduleAutosave() {
    clearAutosave()
    if (!currentChapter.value?.chapterId || !currentChapter.value.currentVersionId) return
    autosaveTimer = setTimeout(() => {
      void saveNow().catch(() => undefined)
    }, AUTOSAVE_DELAY_MS)
  }

  function clearAutosave() {
    if (autosaveTimer) clearTimeout(autosaveTimer)
    autosaveTimer = undefined
  }

  async function saveNow(): Promise<ChapterVersion | null> {
    clearAutosave()
    if (saveInFlight) {
      await saveInFlight
      if (!isDirty.value) return null
    }
    const chapter = requireCurrentChapter()
    const capturedChapterId = chapter.chapterId
    const capturedLoadGeneration = chapterLoadGeneration
    const baseVersionId = chapter.currentVersionId
    if (!chapter.chapterId || baseVersionId === undefined) return null
    if (editorContent.value === persistedContent.value) {
      saveState.value = editorContent.value ? 'saved' : 'idle'
      return null
    }

    const capturedContent = editorContent.value
    const capturedRevision = editorRevision.value
    saveState.value = 'saving'
    saveError.value = ''
    saveInFlight = (async () => {
      try {
        const version = await chapterApi.saveChapterContent(chapter.chapterId, {
          baseVersionId,
          content: capturedContent,
          baseContentHash: chapter.contentHash,
        })
        const stillCurrentChapter =
          capturedLoadGeneration === chapterLoadGeneration &&
          String(currentChapter.value?.chapterId) === String(capturedChapterId)
        if (stillCurrentChapter && currentChapter.value) {
          currentChapter.value.currentVersionId = version.id
          currentChapter.value.currentVersionNo = version.versionNo
          currentChapter.value.contentHash = version.contentHash
          currentChapter.value.content = capturedContent
          currentChapter.value.wordCount = countChapterWords(capturedContent)
          currentChapter.value.status = 'REVIEW_REQUIRED'
          updateCatalogEntry(currentChapter.value)
          persistedContent.value = capturedContent
          if (editorRevision.value === capturedRevision) {
            saveState.value = 'saved'
          } else {
            saveState.value = 'dirty'
            scheduleAutosave()
          }
        }
        return version
      } catch (error) {
        if (
          capturedLoadGeneration === chapterLoadGeneration &&
          String(currentChapter.value?.chapterId) === String(capturedChapterId)
        ) {
          saveState.value = 'error'
          saveError.value = error instanceof Error ? error.message : '自动保存失败'
        }
        throw error
      } finally {
        saveInFlight = null
      }
    })()
    return saveInFlight
  }

  async function captureSelection(start: number, end: number) {
    const content = editorContent.value
    const boundedStart = Math.max(0, Math.min(start, content.length))
    const boundedEnd = Math.max(boundedStart, Math.min(end, content.length))
    const selectedText = content.slice(boundedStart, boundedEnd)
    const revision = editorRevision.value
    if (!selectedText.trim()) {
      selection.value = undefined
      return undefined
    }
    const hash = await sha256Hex(selectedText)
    if (revision !== editorRevision.value) return undefined
    const next: EditorSelection = {
      start: boundedStart,
      end: boundedEnd,
      text: selectedText,
      hash,
      versionId: currentChapter.value?.currentVersionId,
      editorRevision: revision,
    }
    selection.value = next
    return next
  }

  async function requestRewrite(action: RewriteAction, customInstruction = '') {
    if (isStreaming.value || streamPurpose.value) {
      throw new Error('当前 AI 任务尚未完成，请等待后再发起局部改写。')
    }
    const chapter = requireCurrentChapter()
    const selected = selection.value
    const selectedFromDirtyEditor = isDirty.value
    if (!selected?.text.trim()) throw new Error('请先在正文中选中需要改写的文字。')
    if (selected.editorRevision !== editorRevision.value) {
      throw new Error('选区已经过期，请重新选择文字。')
    }
    if (isDirty.value) {
      await saveNow()
      if (isDirty.value) throw new Error('当前正文尚未保存，不能调用AI改写。')
    }
    const versionId = chapter.currentVersionId
    if (!chapter.chapterId || versionId === undefined) {
      throw new Error('请等待正文保存为版本后再调用AI改写。')
    }
    if (
      !selectedFromDirtyEditor &&
      String(selected.versionId) !== String(versionId)
    ) {
      throw new Error('选区已经过期，请重新选择文字。')
    }
    if (editorContent.value.slice(selected.start, selected.end) !== selected.text) {
      throw new Error('选区已经过期，请重新选择文字。')
    }
    const currentSelection: EditorSelection = { ...selected, versionId }
    selection.value = currentSelection
    const input: RewriteSelectionInput = {
      chapterVersionId: versionId,
      startOffset: currentSelection.start,
      endOffset: currentSelection.end,
      selectedText: currentSelection.text,
      selectedTextHash: currentSelection.hash,
      action,
      customInstruction: action === 'CUSTOM' ? customInstruction.trim() : '',
    }
    rewriteSnapshot = { selection: { ...currentSelection }, input }
    rewriting.value = true
    proposal.value = undefined
    try {
      const result = await chapterApi.rewriteChapterSelection(chapter.chapterId, input)
      if (isChapterTask(result)) {
        subscribeToTask(result, 'rewrite')
        return result
      }
      applyRewriteProposal(result)
      return result
    } finally {
      if (!currentTask.value || streamPurpose.value !== 'rewrite') rewriting.value = false
    }
  }

  function applyRewriteProposal(nextProposal: RewriteProposal) {
    const snapshot = rewriteSnapshot
    if (snapshot) {
      nextProposal.startOffset ??= snapshot.selection.start
      nextProposal.endOffset ??= snapshot.selection.end
      nextProposal.selectedTextHash ??= snapshot.selection.hash
      nextProposal.chapterVersionId ??= snapshot.selection.versionId
      const currentText = editorContent.value.slice(
        snapshot.selection.start,
        snapshot.selection.end,
      )
      const stale =
        snapshot.selection.editorRevision !== editorRevision.value ||
        String(snapshot.selection.versionId) !== String(currentChapter.value?.currentVersionId) ||
        currentText !== snapshot.selection.text ||
        (nextProposal.selectedTextHash !== undefined &&
          nextProposal.selectedTextHash !== snapshot.selection.hash)
      if (stale) {
        nextProposal.stale = true
        nextProposal.status = 'STALE'
        nextProposal.staleReason = '正文或版本已在AI返回前变化，建议已过期，不能自动替换。'
      }
    } else if (nextProposal.status === 'READY') {
      const start = nextProposal.startOffset
      const end = nextProposal.endOffset
      const proposalVersion =
        nextProposal.chapterVersionId ?? nextProposal.baseVersionId
      const currentVersion = currentChapter.value?.currentVersionId
      const stale =
        start === undefined ||
        end === undefined ||
        String(proposalVersion) !== String(currentVersion) ||
        editorContent.value.slice(start, end) !== nextProposal.originalText
      if (stale) {
        nextProposal.stale = true
        nextProposal.status = 'STALE'
        nextProposal.staleReason = '建议对应的正文版本或选区已经变化，请重新选择文本。'
      }
    }
    proposal.value = nextProposal
  }

  function proposalStillApplies() {
    const current = proposal.value
    if (isDirty.value || !current || current.stale || current.status !== 'READY') return false
    const start = current.startOffset
    const end = current.endOffset
    if (start === undefined || end === undefined) return false
    const proposalVersion = current.chapterVersionId ?? current.baseVersionId
    return (
      String(proposalVersion) === String(currentChapter.value?.currentVersionId) &&
      editorContent.value.slice(start, end) === current.originalText
    )
  }

  async function acceptProposal() {
    const chapter = requireCurrentChapter()
    const current = proposal.value
    if (!current || !proposalStillApplies()) {
      if (current) {
        current.stale = true
        current.status = 'STALE'
        current.staleReason = '当前正文已经变化，请重新选择文本生成建议。'
      }
      throw new Error('AI建议已过期，不能覆盖当前正文。')
    }
    const version = await chapterApi.acceptRewriteProposal(
      chapter.chapterId,
      current.proposalId,
      chapter.currentVersionId,
    )
    current.status = 'ACCEPTED'
    const detail = await chapterApi.getChapter(chapter.chapterId)
    applyChapter(detail, true)
    await loadVersions()
    return version
  }

  async function rejectProposal() {
    const chapter = requireCurrentChapter()
    const current = proposal.value
    if (!current) return undefined
    const rejected = await chapterApi.rejectRewriteProposal(
      chapter.chapterId,
      current.proposalId,
    )
    proposal.value = { ...current, ...rejected, status: 'REJECTED' }
    return proposal.value
  }

  async function regenerateProposal() {
    const chapter = requireCurrentChapter()
    const current = proposal.value
    if (!current || !proposalStillApplies()) {
      throw new Error('当前建议已过期，请重新选择文本。')
    }
    rewriting.value = true
    try {
      const result = await chapterApi.regenerateRewriteProposal(
        chapter.chapterId,
        current.proposalId,
      )
      if (isChapterTask(result)) {
        subscribeToTask(result, 'rewrite')
        return result
      }
      applyRewriteProposal(result)
      return result
    } finally {
      if (!currentTask.value || streamPurpose.value !== 'rewrite') rewriting.value = false
    }
  }

  async function loadVersions() {
    const chapter = requireCurrentChapter()
    if (!chapter.chapterId) return []
    loadingVersions.value = true
    try {
      versions.value = await chapterApi.listChapterVersions(chapter.chapterId)
      return versions.value
    } finally {
      loadingVersions.value = false
    }
  }

  async function compareVersions(fromVersionId: EntityId, toVersionId: EntityId) {
    const chapter = requireCurrentChapter()
    comparingVersions.value = true
    try {
      versionComparison.value = await chapterApi.compareChapterVersions(
        chapter.chapterId,
        fromVersionId,
        toVersionId,
      )
      return versionComparison.value
    } finally {
      comparingVersions.value = false
    }
  }

  async function restoreVersion(versionId: EntityId) {
    const chapter = requireCurrentChapter()
    if (chapter.status === 'APPROVED') {
      throw new Error('已批准章节不可恢复；长期记忆已经更新，请继续下一章。')
    }
    restoringVersion.value = true
    try {
      if (isDirty.value) await saveNow()
      if (isDirty.value) throw new Error('当前编辑尚未保存，不能恢复历史版本。')
      const version = await chapterApi.restoreChapterVersion(chapter.chapterId, versionId)
      const detail = await chapterApi.getChapter(chapter.chapterId)
      applyChapter(detail, true)
      proposal.value = undefined
      selection.value = undefined
      await loadVersions()
      return version
    } finally {
      restoringVersion.value = false
    }
  }

  async function approveCurrentChapter() {
    const chapter = requireCurrentChapter()
    approvingChapter.value = true
    try {
      if (isDirty.value) await saveNow()
      if (isDirty.value) throw new Error('正文尚未保存，暂时不能批准。')
      const result = await chapterApi.approveChapter(chapter.chapterId)
      if ('taskId' in result) {
        subscribeToTask(result, 'finalize')
        return result
      }
      applyChapter(result, true)
      await loadCatalog(result.storyId)
      return result
    } finally {
      if (streamPurpose.value !== 'finalize') approvingChapter.value = false
    }
  }

  async function requestAiChanges(notes: string) {
    const chapter = requireCurrentChapter()
    const normalizedNotes = notes.trim()
    if (chapter.status !== 'REVIEW_REQUIRED') {
      throw new Error('章节尚未进入人工审核，不能要求 AI 修改。')
    }
    if (!normalizedNotes) throw new Error('请填写具体修改要求。')
    requestingChanges.value = true
    try {
      if (isDirty.value) await saveNow()
      if (isDirty.value) throw new Error('正文尚未保存，暂时不能要求 AI 修改。')
      const result = await chapterApi.requestChapterChanges(chapter.chapterId, normalizedNotes)
      if ('taskId' in result) {
        currentChapter.value = {
          ...chapter,
          status: 'GENERATING',
          activeTaskId: result.taskId,
          activeTaskStatus: result.status,
          activeTaskType: 'CHAPTER_FINALIZE',
        }
        updateCatalogEntry(currentChapter.value)
        subscribeToTask(result, 'finalize')
        return result
      }
      applyChapter(result, true)
      return result
    } finally {
      if (streamPurpose.value !== 'finalize') requestingChanges.value = false
    }
  }

  function useGeneratedDraft() {
    if (!pendingGeneratedContent.value) return
    updateEditorContent(pendingGeneratedContent.value)
    pendingGeneratedContent.value = ''
    streamDetached.value = false
  }

  function dismissGeneratedDraft() {
    pendingGeneratedContent.value = ''
  }

  function stopStream() {
    subscription?.close()
    subscription = undefined
    subscriptionToken = undefined
    streamState.value = 'idle'
    streamPurpose.value = undefined
  }

  function requireCurrentChapter() {
    if (!currentChapter.value) throw new Error('请先打开一个章节。')
    return currentChapter.value
  }

  function resetEditorState() {
    editorContent.value = ''
    persistedContent.value = ''
    editorRevision.value = 0
    saveState.value = 'idle'
    saveError.value = ''
    currentPlan.value = undefined
    currentTask.value = undefined
    versions.value = []
    versionComparison.value = undefined
    proposal.value = undefined
    selection.value = undefined
    streamProgress.value = 0
    streamCurrentNode.value = ''
    streamBuffer.value = ''
    streamDetached.value = false
    streamError.value = ''
    streamEvents.value = []
    pendingGeneratedContent.value = ''
    rewriteSnapshot = undefined
    streamStartEditorRevision = 0
    streamCanMirror = false
  }

  function reset() {
    chapterLoadRequestGeneration += 1
    chapterLoadGeneration += 1
    stopStream()
    clearAutosave()
    catalogs.value = {}
    currentChapter.value = undefined
    resetEditorState()
    loadingCatalog.value = false
    loadingChapter.value = false
    planning.value = false
    approvingPlan.value = false
    generating.value = false
    rewriting.value = false
    loadingVersions.value = false
    comparingVersions.value = false
    restoringVersion.value = false
    approvingChapter.value = false
    requestingChanges.value = false
    seenEventIds.clear()
    lastSequenceByTask.clear()
  }

  return {
    catalogs,
    currentChapter,
    currentPlan,
    currentTask,
    versions,
    versionComparison,
    proposal,
    selection,
    editorContent,
    editorRevision,
    saveState,
    saveError,
    loadingCatalog,
    loadingChapter,
    planning,
    approvingPlan,
    generating,
    rewriting,
    loadingVersions,
    comparingVersions,
    restoringVersion,
    approvingChapter,
    requestingChanges,
    streamState,
    streamPurpose,
    streamProgress,
    streamCurrentNode,
    streamBuffer,
    streamDetached,
    streamError,
    streamEvents,
    pendingGeneratedContent,
    wordCount,
    isDirty,
    canEdit,
    canRetryGeneration,
    isStreaming,
    loadCatalog,
    loadChapter,
    createPlan,
    approvePlan,
    generate,
    handleStreamEvent,
    updateEditorContent,
    saveNow,
    captureSelection,
    requestRewrite,
    acceptProposal,
    rejectProposal,
    regenerateProposal,
    loadVersions,
    compareVersions,
    restoreVersion,
    approveCurrentChapter,
    requestAiChanges,
    useGeneratedDraft,
    dismissGeneratedDraft,
    stopStream,
    reset,
  }
})
