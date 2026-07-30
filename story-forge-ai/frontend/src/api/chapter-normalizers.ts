import type {
  ChapterDetail,
  ChapterEventType,
  ChapterListItem,
  ChapterPlan,
  ChapterPlanEnvelope,
  ChapterReview,
  ChapterReviewDimension,
  ChapterStatus,
  ChapterStreamEvent,
  ChapterTask,
  ChapterTaskStatus,
  ChapterVersion,
  ChapterVersionComparison,
  EntityId,
  MemoryUpdate,
  RewriteProposal,
  RewriteProposalStatus,
  ScenePlan,
} from '@/types'

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function record(value: unknown): UnknownRecord {
  const parsed = parseJson(value)
  return isRecord(parsed) ? parsed : {}
}

function parseJson(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const trimmed = value.trim()
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return value
  try {
    return JSON.parse(trimmed)
  } catch {
    return value
  }
}

function first(source: UnknownRecord, keys: string[]): unknown {
  for (const key of keys) {
    if (source[key] !== undefined && source[key] !== null) return source[key]
  }
  return undefined
}

function text(value: unknown, fallback = ''): string {
  return value === undefined || value === null ? fallback : String(value)
}

function integer(value: unknown, fallback = 0): number {
  const number = Number(value)
  return Number.isFinite(number) ? Math.trunc(number) : fallback
}

function booleanValue(value: unknown, fallback = false): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (typeof value === 'string') {
    if (['true', '1', 'yes', 'approved'].includes(value.toLowerCase())) return true
    if (['false', '0', 'no', 'pending'].includes(value.toLowerCase())) return false
  }
  return fallback
}

function array(value: unknown, nestedKeys: string[] = []): unknown[] {
  const parsed = parseJson(value)
  if (Array.isArray(parsed)) return parsed
  const source = record(parsed)
  for (const key of nestedKeys) {
    const nested = parseJson(source[key])
    if (Array.isArray(nested)) return nested
  }
  return []
}

function stringArray(value: unknown): string[] {
  return array(value, ['items', 'values']).map((item) => text(item)).filter(Boolean)
}

function optionalId(value: unknown): EntityId | undefined {
  return value === undefined || value === null || value === ''
    ? undefined
    : (value as EntityId)
}

export function normalizeChapterStatus(value: unknown): ChapterStatus {
  const status = text(value, 'EMPTY').trim().toUpperCase().replace(/[\s-]+/g, '_')
  if (['WAITING', 'QUEUED', 'PLANNING'].includes(status)) return 'PLANNING'
  if (['PLAN_READY', 'PLAN_REVIEW_REQUIRED'].includes(status)) return 'PLAN_READY'
  if (['PLAN_APPROVED', 'READY_TO_GENERATE'].includes(status)) return 'PLAN_APPROVED'
  if (['RUNNING', 'GENERATING', 'WRITING', 'REVISING', 'FINALIZING'].includes(status)) {
    return 'GENERATING'
  }
  if (['REVIEW_REQUIRED', 'HUMAN_REVIEW_REQUIRED'].includes(status)) return 'REVIEW_REQUIRED'
  if (['SUCCESS', 'FINAL', 'COMPLETED', 'APPROVED'].includes(status)) return 'APPROVED'
  if (['FAILED', 'ERROR'].includes(status)) return 'FAILED'
  if (['DRAFT', 'SAVED', 'EDITING'].includes(status)) return 'DRAFT'
  return 'EMPTY'
}

export function normalizeChapterTaskStatus(value: unknown): ChapterTaskStatus {
  const status = text(value, 'WAITING').trim().toUpperCase().replace(/[\s-]+/g, '_')
  if (['SUCCESS', 'COMPLETED', 'FINAL_READY', 'APPROVED'].includes(status)) return 'SUCCESS'
  if (['FAILED', 'ERROR', 'TASK_FAILED'].includes(status)) return 'FAILED'
  if (['REVIEW_REQUIRED', 'HUMAN_REVIEW_REQUIRED', 'PLAN_READY'].includes(status)) {
    return 'REVIEW_REQUIRED'
  }
  if (['RUNNING', 'PROCESSING', 'GENERATING', 'PLANNING'].includes(status)) return 'RUNNING'
  return 'WAITING'
}

function normalizeScene(value: unknown, index: number): ScenePlan {
  const source = record(value)
  return {
    sceneNo: integer(first(source, ['sceneNo', 'scene_no', 'number']), index + 1),
    location: text(first(source, ['location', 'place'])),
    time: text(first(source, ['time', 'timeOfDay', 'time_of_day'])),
    characters: stringArray(first(source, ['characters', 'characterNames', 'character_names'])),
    protagonistGoal: text(first(source, ['protagonistGoal', 'protagonist_goal', 'goal'])),
    opposingForce: text(first(source, ['opposingForce', 'opposing_force', 'obstacle'])),
    visibleConflict: text(first(source, ['visibleConflict', 'visible_conflict', 'conflict'])),
    informationRevealed: text(
      first(source, ['informationRevealed', 'information_revealed', 'newInformation']),
    ),
    emotionalChange: text(first(source, ['emotionalChange', 'emotional_change'])),
    setupOrPayoff: text(first(source, ['setupOrPayoff', 'setup_or_payoff'])),
    exitHook: text(first(source, ['exitHook', 'exit_hook', 'hook'])),
    sceneFunction: text(first(source, ['sceneFunction', 'scene_function', 'function']), '推进'),
  }
}

export function normalizeChapterPlan(value: unknown): ChapterPlan | undefined {
  const source = record(value)
  const nested = record(first(source, ['plan', 'chapterPlan', 'chapter_plan']))
  const payload = Object.keys(nested).length ? nested : source
  const scenes = array(first(payload, ['scenes', 'scenePlans', 'scene_plans']), ['items']).map(
    normalizeScene,
  )
  if (!Object.keys(payload).length || (!scenes.length && !first(payload, ['chapterTitle', 'chapter_title']))) {
    return undefined
  }
  return {
    chapterTitle: text(first(payload, ['chapterTitle', 'chapter_title', 'title']), '未命名章节'),
    chapterGoal: text(first(payload, ['chapterGoal', 'chapter_goal', 'goal'])),
    openingHook: text(first(payload, ['openingHook', 'opening_hook'])),
    endingHook: text(first(payload, ['endingHook', 'ending_hook'])),
    targetLength: integer(first(payload, ['targetLength', 'target_length']), 1800),
    scenes,
  }
}

const REVIEW_DIMENSIONS: Array<[string, string, string[], number]> = [
  ['outline_completion', '大纲完成度', ['outlineCompletion', 'outline_completion'], 20],
  ['continuity', '人物与事实一致性', ['continuity'], 20],
  ['conflict_progression', '冲突与剧情推进', ['conflictProgression', 'conflict_progression'], 20],
  ['emotion_and_visuals', '情绪和画面感', ['emotionAndVisuals', 'emotion_and_visuals'], 15],
  ['hooks', '开头与结尾钩子', ['hooks'], 15],
  ['language_quality', '语言质量', ['languageQuality', 'language_quality'], 10],
]

function normalizeReviewDimension(
  value: unknown,
  key: string,
  label: string,
  maxScore: number,
): ChapterReviewDimension {
  const source = record(value)
  const rawScore = integer(first(source, ['score', 'value']), 0)
  return {
    key,
    label,
    score: Math.max(0, Math.min(maxScore, rawScore)),
    maxScore,
    evidence: stringArray(first(source, ['evidence', 'evidences'])),
    problems: stringArray(first(source, ['problems', 'issues'])),
    suggestions: stringArray(first(source, ['suggestions', 'recommendations'])),
  }
}

export function normalizeChapterReview(value: unknown): ChapterReview | undefined {
  const source = record(value)
  const nested = record(first(source, ['review', 'chapterReview', 'chapter_review']))
  const payload = Object.keys(nested).length ? nested : source
  if (!Object.keys(payload).length) return undefined

  const explicitDimensions = array(first(payload, ['dimensions', 'scores']))
  const dimensions = explicitDimensions.length
    ? explicitDimensions.map((dimension, index) => {
        const item = record(dimension)
        const fallback = REVIEW_DIMENSIONS[index] ?? [`dimension_${index + 1}`, `维度 ${index + 1}`, [], 20]
        return normalizeReviewDimension(
          item,
          text(first(item, ['key', 'dimension']), fallback[0] as string),
          text(first(item, ['label', 'name']), fallback[1] as string),
          integer(first(item, ['maxScore', 'max_score']), fallback[3] as number),
        )
      })
    : REVIEW_DIMENSIONS.map(([key, label, aliases, maxScore]) =>
        normalizeReviewDimension(first(payload, aliases), key, label, maxScore),
      )
  const calculatedTotal = dimensions.reduce((sum, dimension) => sum + dimension.score, 0)
  return {
    total: integer(first(payload, ['total', 'totalScore', 'total_score']), calculatedTotal),
    dimensions,
    fatalProblems: stringArray(first(payload, ['fatalProblems', 'fatal_problems'])),
    rewriteInstructions: stringArray(
      first(payload, ['rewriteInstructions', 'rewrite_instructions']),
    ),
    shouldRewrite: booleanValue(first(payload, ['shouldRewrite', 'should_rewrite'])),
    mechanicalErrors: stringArray(
      first(payload, ['mechanicalErrors', 'mechanical_errors', 'validationErrors']),
    ),
  }
}

function normalizeChapterSummary(value: unknown) {
  const source = record(value)
  if (!Object.keys(source).length) return undefined
  return {
    chapterNo: integer(first(source, ['chapterNo', 'chapter_no'])),
    summary: text(first(source, ['summary', 'text'])),
    mainEvents: stringArray(first(source, ['mainEvents', 'main_events'])),
    characterChanges: stringArray(first(source, ['characterChanges', 'character_changes'])),
    newFacts: stringArray(first(source, ['newFacts', 'new_facts'])),
    openedThreads: stringArray(first(source, ['openedThreads', 'opened_threads'])),
    resolvedThreads: stringArray(first(source, ['resolvedThreads', 'resolved_threads'])),
    endingHook: text(first(source, ['endingHook', 'ending_hook'])),
  }
}

function normalizeMemoryUpdate(value: unknown): MemoryUpdate | undefined {
  const source = record(value)
  if (!Object.keys(source).length) return undefined
  return {
    newFacts: array(first(source, ['newFacts', 'new_facts'])),
    changedRelationships: array(first(source, ['changedRelationships', 'changed_relationships'])),
    openedThreads: array(first(source, ['openedThreads', 'opened_threads'])),
    updatedThreads: array(first(source, ['updatedThreads', 'updated_threads'])),
    resolvedThreads: stringArray(first(source, ['resolvedThreads', 'resolved_threads'])),
    newForeshadowing: array(first(source, ['newForeshadowing', 'new_foreshadowing'])),
    paidOffForeshadowing: stringArray(
      first(source, ['paidOffForeshadowing', 'paid_off_foreshadowing']),
    ),
    characterStateChanges: array(
      first(source, ['characterStateChanges', 'character_state_changes']),
    ),
    continuityWarnings: stringArray(
      first(source, ['continuityWarnings', 'continuity_warnings']),
    ),
  }
}

export function normalizeChapterListItem(value: unknown): ChapterListItem {
  const source = record(value)
  const chapter = record(first(source, ['chapter']))
  const payload = Object.keys(chapter).length ? { ...source, ...chapter } : source
  const chapterId = optionalId(first(payload, ['chapterId', 'chapter_id', 'id']))
  const planStatus = text(first(payload, ['planStatus', 'plan_status'])).toUpperCase()
  return {
    id: chapterId,
    chapterId,
    storyId: (first(payload, ['storyId', 'story_id']) ?? '') as EntityId,
    chapterNo: integer(first(payload, ['chapterNo', 'chapter_no', 'number'])),
    title: text(first(payload, ['title', 'chapterTitle', 'chapter_title']), '待创作章节'),
    status: normalizeChapterStatus(first(payload, ['status', 'chapterStatus', 'chapter_status'])),
    wordCount: integer(first(payload, ['wordCount', 'word_count'])),
    currentVersionId: optionalId(
      first(payload, ['currentVersionId', 'current_version_id', 'chapterVersionId']),
    ),
    activeTaskId: optionalId(first(payload, ['activeTaskId', 'active_task_id', 'taskId'])),
    activeTaskStatus:
      first(payload, ['activeTaskStatus', 'active_task_status']) === undefined
        ? undefined
        : normalizeChapterTaskStatus(
            first(payload, ['activeTaskStatus', 'active_task_status']),
          ),
    activeTaskType:
      text(first(payload, ['activeTaskType', 'active_task_type'])) || undefined,
    planApproved:
      booleanValue(first(payload, ['planApproved', 'plan_approved'])) ||
      planStatus === 'APPROVED',
    planStatus: planStatus || undefined,
    planHash: text(first(payload, ['planHash', 'plan_hash'])) || undefined,
    approvedTime:
      text(first(payload, ['approvedTime', 'approved_time'])) || undefined,
    updatedTime: text(first(payload, ['updatedTime', 'updated_time'])) || undefined,
  }
}

export function normalizeChapterDetail(value: unknown): ChapterDetail {
  const source = record(value)
  const base = normalizeChapterListItem(source)
  const chapter = record(first(source, ['chapter']))
  const version = record(
    first(source, ['currentVersion', 'current_version', 'version', 'chapterVersion']),
  )
  const payload = Object.keys(chapter).length ? { ...source, ...chapter } : source
  const chapterId =
    base.chapterId ?? optionalId(first(version, ['chapterId', 'chapter_id'])) ?? ''
  const content = text(
    first(payload, ['content', 'finalContent', 'final_content', 'draftContent', 'draft_content']),
    text(first(version, ['content', 'text'])),
  )
  const rawReview =
    first(payload, ['review', 'chapterReview', 'chapter_review']) ??
    first(version, ['review', 'reviewJson', 'review_json'])
  const review = normalizeChapterReview(rawReview)
  const responseMechanicalErrors = stringArray(
    first(payload, ['mechanicalErrors', 'mechanical_errors', 'validationErrors']),
  )
  return {
    ...base,
    chapterId,
    content,
    contentHash:
      text(first(payload, ['contentHash', 'content_hash']), text(first(version, ['contentHash', 'content_hash']))) ||
      undefined,
    currentVersionNo: integer(
      first(payload, ['currentVersionNo', 'current_version_no']),
      integer(first(version, ['versionNo', 'version_no'])) || undefined,
    ),
    plan: normalizeChapterPlan(first(payload, ['plan', 'chapterPlan', 'chapter_plan'])),
    review,
    mechanicalErrors: responseMechanicalErrors.length
      ? responseMechanicalErrors
      : review?.mechanicalErrors ?? [],
    summary: normalizeChapterSummary(first(payload, ['summary', 'chapterSummary', 'chapter_summary'])),
    memoryUpdate: normalizeMemoryUpdate(first(payload, ['memoryUpdate', 'memory_update'])),
    revisionCount: integer(first(payload, ['revisionCount', 'revision_count'])),
    maxRevisions: integer(first(payload, ['maxRevisions', 'max_revisions']), 2),
  }
}

export function normalizeChapterList(value: unknown): ChapterListItem[] {
  return array(value, ['chapters', 'items', 'content'])
    .map(normalizeChapterListItem)
    .filter((chapter) => chapter.chapterNo > 0)
    .sort((left, right) => left.chapterNo - right.chapterNo)
}

export function normalizeChapterPlanEnvelope(value: unknown): ChapterPlanEnvelope {
  const source = record(value)
  const planStatus = text(first(source, ['planStatus', 'plan_status'])).toUpperCase()
  return {
    chapterId: optionalId(first(source, ['chapterId', 'chapter_id', 'id'])),
    status: normalizeChapterStatus(first(source, ['status'])),
    plan: normalizeChapterPlan(first(source, ['plan', 'chapterPlan', 'chapter_plan'])),
    approved:
      booleanValue(first(source, ['approved', 'planApproved', 'plan_approved'])) ||
      planStatus === 'APPROVED',
    planHash: text(first(source, ['planHash', 'plan_hash'])) || undefined,
  }
}

export function normalizeChapterTask(value: unknown): ChapterTask {
  const source = record(value)
  const nested = record(first(source, ['task']))
  const payload = Object.keys(nested).length ? nested : source
  return {
    taskId: (first(payload, ['taskId', 'task_id', 'id']) ?? '') as EntityId,
    storyId: optionalId(first(payload, ['storyId', 'story_id'])),
    chapterId: optionalId(first(payload, ['chapterId', 'chapter_id'])),
    chapterNo:
      first(payload, ['chapterNo', 'chapter_no']) === undefined
        ? undefined
        : integer(first(payload, ['chapterNo', 'chapter_no'])),
    status: normalizeChapterTaskStatus(first(payload, ['status', 'taskStatus', 'task_status'])),
  }
}

export function normalizeChapterVersion(value: unknown): ChapterVersion {
  const source = record(value)
  const nested = record(first(source, ['version', 'chapterVersion', 'chapter_version']))
  const payload = Object.keys(nested).length ? nested : source
  return {
    id: (first(payload, ['id', 'versionId', 'version_id']) ?? '') as EntityId,
    chapterId: (first(payload, ['chapterId', 'chapter_id']) ?? '') as EntityId,
    versionNo: integer(first(payload, ['versionNo', 'version_no'])),
    sourceType: text(first(payload, ['sourceType', 'source_type']), 'UNKNOWN'),
    content: text(first(payload, ['content', 'text'])),
    contentHash: text(first(payload, ['contentHash', 'content_hash'])) || undefined,
    baseVersionId: optionalId(first(payload, ['baseVersionId', 'base_version_id'])),
    aiTaskId: optionalId(first(payload, ['aiTaskId', 'ai_task_id', 'taskId'])),
    promptVersion: text(first(payload, ['promptVersion', 'prompt_version'])) || undefined,
    modelName: text(first(payload, ['modelName', 'model_name'])) || undefined,
    review: normalizeChapterReview(first(payload, ['review', 'reviewJson', 'review_json'])),
    changeSummary: text(first(payload, ['changeSummary', 'change_summary'])),
    createdTime: text(first(payload, ['createdTime', 'created_time'])) || undefined,
  }
}

export function normalizeChapterVersions(value: unknown): ChapterVersion[] {
  return array(value, ['versions', 'items', 'content'])
    .map(normalizeChapterVersion)
    .filter((version) => version.id !== '')
    .sort((left, right) => right.versionNo - left.versionNo)
}

export function normalizeVersionComparison(value: unknown): ChapterVersionComparison {
  const source = record(value)
  const fromValue = first(source, ['fromVersion', 'from_version', 'from'])
  const toValue = first(source, ['toVersion', 'to_version', 'to'])
  return {
    fromVersion: fromValue === undefined ? undefined : normalizeChapterVersion(fromValue),
    toVersion: toValue === undefined ? undefined : normalizeChapterVersion(toValue),
    changes: first(source, ['changes', 'diff', 'patch']) ?? [],
  }
}

function normalizeProposalStatus(value: unknown): RewriteProposalStatus {
  const status = text(value, 'PENDING').toUpperCase()
  if (status === 'READY') return 'READY'
  if (status === 'ACCEPTED') return 'ACCEPTED'
  if (status === 'REJECTED') return 'REJECTED'
  if (status === 'STALE' || status === 'EXPIRED') return 'STALE'
  return 'PENDING'
}

export function normalizeRewriteProposal(value: unknown): RewriteProposal {
  const source = record(value)
  const nested = record(first(source, ['proposal', 'rewriteProposal', 'rewrite_proposal']))
  const payload = Object.keys(nested).length ? nested : source
  const replacementText = text(first(payload, ['replacementText', 'replacement_text']))
  const status = normalizeProposalStatus(
    first(payload, ['status']) ?? (replacementText ? 'READY' : 'PENDING'),
  )
  return {
    proposalId: (first(payload, ['proposalId', 'proposal_id', 'id']) ?? '') as EntityId,
    chapterId: optionalId(first(payload, ['chapterId', 'chapter_id'])),
    chapterVersionId: optionalId(
      first(payload, [
        'chapterVersionId',
        'chapter_version_id',
        'baseVersionId',
        'base_version_id',
      ]),
    ),
    baseVersionId: optionalId(first(payload, ['baseVersionId', 'base_version_id'])),
    baseVersionNo:
      first(payload, ['baseVersionNo', 'base_version_no']) === undefined
        ? undefined
        : integer(first(payload, ['baseVersionNo', 'base_version_no'])),
    startOffset:
      first(payload, ['startOffset', 'start_offset']) === undefined
        ? undefined
        : integer(first(payload, ['startOffset', 'start_offset'])),
    endOffset:
      first(payload, ['endOffset', 'end_offset']) === undefined
        ? undefined
        : integer(first(payload, ['endOffset', 'end_offset'])),
    selectedTextHash:
      text(
        first(payload, [
          'selectedTextHash',
          'selected_text_hash',
          'originalTextHash',
          'original_text_hash',
        ]),
      ) || undefined,
    originalText: text(first(payload, ['originalText', 'original_text'])),
    replacementText,
    reason: text(first(payload, ['reason', 'explanation'])),
    status,
    stale: status === 'STALE',
    staleReason: text(first(payload, ['staleReason', 'stale_reason'])) || undefined,
  }
}

export function normalizeRewriteProposals(value: unknown): RewriteProposal[] {
  return array(value, ['proposals', 'items', 'content'])
    .map(normalizeRewriteProposal)
    .filter((proposal) => proposal.proposalId !== '')
}

export function normalizeChapterStreamEvent(
  value: unknown,
  metadata: { id?: string; event?: string } = {},
): ChapterStreamEvent {
  const source = record(value)
  const eventData = record(first(source, ['data', 'payload']))
  const type = text(first(source, ['type', 'eventType', 'event_type']), metadata.event || 'MESSAGE')
  return {
    eventId: text(first(source, ['eventId', 'event_id']), metadata.id || ''),
    taskId: (first(source, ['taskId', 'task_id']) ?? '') as EntityId,
    storyId: optionalId(first(source, ['storyId', 'story_id'])),
    chapterId: optionalId(first(source, ['chapterId', 'chapter_id'])),
    chapterNo:
      first(source, ['chapterNo', 'chapter_no']) === undefined
        ? undefined
        : integer(first(source, ['chapterNo', 'chapter_no'])),
    type: type.toUpperCase() as ChapterEventType,
    sequence: integer(first(source, ['sequence', 'seq']), integer(metadata.id)),
    status:
      first(source, ['status']) === undefined
        ? undefined
        : normalizeChapterTaskStatus(first(source, ['status'])),
    currentNode: text(first(source, ['currentNode', 'current_node'])) || undefined,
    progress:
      first(source, ['progress']) === undefined
        ? undefined
        : Math.max(0, Math.min(100, integer(first(source, ['progress'])))),
    data: eventData,
    errorCode: text(first(source, ['errorCode', 'error_code'])) || undefined,
    errorMessage: text(first(source, ['errorMessage', 'error_message'])) || undefined,
    createdTime: text(first(source, ['createdTime', 'created_time'])) || undefined,
  }
}
