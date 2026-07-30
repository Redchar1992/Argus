type EntityId = number | string

export type ChapterStatus =
  | 'EMPTY'
  | 'PLANNING'
  | 'PLAN_READY'
  | 'PLAN_APPROVED'
  | 'GENERATING'
  | 'REVIEW_REQUIRED'
  | 'DRAFT'
  | 'APPROVED'
  | 'FAILED'

export type ChapterTaskStatus =
  | 'WAITING'
  | 'RUNNING'
  | 'REVIEW_REQUIRED'
  | 'SUCCESS'
  | 'FAILED'

export type SceneFunction = '建立' | '升级' | '反转' | '高潮' | '过渡' | '收束' | string

export interface ScenePlan {
  sceneNo: number
  location: string
  time: string
  characters: string[]
  protagonistGoal: string
  opposingForce: string
  visibleConflict: string
  informationRevealed: string
  emotionalChange: string
  setupOrPayoff: string
  exitHook: string
  sceneFunction: SceneFunction
}

export interface ChapterPlan {
  chapterTitle: string
  chapterGoal: string
  openingHook: string
  endingHook: string
  targetLength: number
  scenes: ScenePlan[]
}

export interface ChapterReviewDimension {
  key: string
  label: string
  score: number
  maxScore: number
  evidence: string[]
  problems: string[]
  suggestions: string[]
}

export interface ChapterReview {
  total: number
  dimensions: ChapterReviewDimension[]
  fatalProblems: string[]
  rewriteInstructions: string[]
  shouldRewrite: boolean
  mechanicalErrors: string[]
}

export interface ChapterSummary {
  chapterNo: number
  summary: string
  mainEvents: string[]
  characterChanges: string[]
  newFacts: string[]
  openedThreads: string[]
  resolvedThreads: string[]
  endingHook: string
}

export interface MemoryUpdate {
  newFacts: unknown[]
  changedRelationships: unknown[]
  openedThreads: unknown[]
  updatedThreads: unknown[]
  resolvedThreads: string[]
  newForeshadowing: unknown[]
  paidOffForeshadowing: string[]
  characterStateChanges: unknown[]
  continuityWarnings: string[]
}

export interface ChapterListItem {
  id?: EntityId
  chapterId?: EntityId
  storyId: EntityId
  chapterNo: number
  title: string
  status: ChapterStatus
  wordCount: number
  currentVersionId?: EntityId
  activeTaskId?: EntityId
  activeTaskStatus?: ChapterTaskStatus
  activeTaskType?: string
  planApproved: boolean
  planStatus?: string
  planHash?: string
  approvedTime?: string
  updatedTime?: string
}

export interface ChapterDetail extends ChapterListItem {
  chapterId: EntityId
  content: string
  contentHash?: string
  currentVersionNo?: number
  plan?: ChapterPlan
  review?: ChapterReview
  mechanicalErrors: string[]
  summary?: ChapterSummary
  memoryUpdate?: MemoryUpdate
  revisionCount: number
  maxRevisions: number
}

export interface ChapterVersion {
  id: EntityId
  chapterId: EntityId
  versionNo: number
  sourceType: string
  content: string
  contentHash?: string
  baseVersionId?: EntityId
  aiTaskId?: EntityId
  promptVersion?: string
  modelName?: string
  review?: ChapterReview
  changeSummary: string
  createdTime?: string
}

export interface ChapterVersionComparison {
  fromVersion?: ChapterVersion
  toVersion?: ChapterVersion
  changes: unknown
}

export interface ChapterPlanEnvelope {
  chapterId?: EntityId
  status: ChapterStatus | ChapterTaskStatus
  plan?: ChapterPlan
  approved: boolean
  planHash?: string
}

export interface ChapterTask {
  taskId: EntityId
  storyId?: EntityId
  chapterId?: EntityId
  chapterNo?: number
  status: ChapterTaskStatus
}

export type ChapterEventType =
  | 'TASK_STARTED'
  | 'CONTEXT_LOADED'
  | 'CHAPTER_PLAN_READY'
  | 'GENERATION_STARTED'
  | 'TOKEN_DELTA'
  | 'DRAFT_READY'
  | 'REVIEW_READY'
  | 'REVISION_STARTED'
  | 'REVISION_READY'
  | 'HUMAN_REVIEW_REQUIRED'
  | 'FINAL_READY'
  | 'REWRITE_PROPOSAL_READY'
  | 'TASK_FAILED'
  | string

export interface ChapterStreamEvent {
  eventId: string
  taskId: EntityId
  storyId?: EntityId
  chapterId?: EntityId
  chapterNo?: number
  type: ChapterEventType
  sequence: number
  status?: ChapterTaskStatus
  currentNode?: string
  progress?: number
  data: Record<string, unknown>
  errorCode?: string
  errorMessage?: string
  createdTime?: string
}

export type RewriteAction =
  | 'ENHANCE_CONFLICT'
  | 'ADD_VISUAL_DETAIL'
  | 'ADJUST_TONE'
  | 'REDUCE_AI_TONE'
  | 'COMPRESS'
  | 'EXPAND_DETAIL'
  | 'FIX_CHARACTER_LOGIC'
  | 'CUSTOM'

export interface RewriteSelectionInput {
  chapterVersionId: EntityId
  startOffset: number
  endOffset: number
  selectedText: string
  selectedTextHash: string
  action: RewriteAction
  customInstruction: string
}

export type RewriteProposalStatus =
  | 'PENDING'
  | 'READY'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'STALE'

export interface RewriteProposal {
  proposalId: EntityId
  chapterId?: EntityId
  chapterVersionId?: EntityId
  baseVersionId?: EntityId
  baseVersionNo?: number
  startOffset?: number
  endOffset?: number
  selectedTextHash?: string
  originalText: string
  replacementText: string
  reason: string
  status: RewriteProposalStatus
  stale: boolean
  staleReason?: string
}

export interface EditorSelection {
  start: number
  end: number
  text: string
  hash: string
  versionId?: EntityId
  editorRevision: number
}

export type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'

export type StreamConnectionState =
  | 'idle'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'closed'
  | 'failed'

export interface ChapterStreamCursor {
  taskId: EntityId
  lastEventId: string
  lastSequence: number
  updatedAt: string
  storyId?: EntityId
  chapterId?: EntityId
  chapterNo?: number
  purpose?: 'plan' | 'generate' | 'rewrite' | 'finalize'
}
