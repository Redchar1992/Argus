export type EntityId = number | string

export interface AuthResult {
  token: string
  userId: EntityId
  username?: string
}

export interface AuthCredentials {
  username: string
  password: string
}

export interface TopicOption {
  id: string
  title: string
  hook: string
  summary: string
  score: number
  reasons: string[]
  scoreDetails: TopicScoreDetail[]
  tags: string[]
  conflict?: string
  twist?: string
  emotionalValue?: string
}

export interface TopicScoreDetail {
  dimension: 'conflict' | 'reversal' | 'emotionalValue' | 'shortDramaFit' | string
  label: string
  score: number
  reason: string
}

export interface TopicSession {
  storyId: EntityId
  taskId?: EntityId
  topics: TopicOption[]
  selectedTopicId?: string
  generatedAt: string
  input: {
    title?: string
    genre: string
    audience: string
    keywords: string
  }
}

export interface StoryProject {
  id: EntityId
  userId?: EntityId
  title: string
  genre: string
  audience?: string
  keywords?: string
  status: string
  createdTime?: string
  updatedTime?: string
  topics?: TopicOption[]
  selectedTopicId?: string
}

export interface CreateStoryInput {
  title: string
  genre: string
  audience?: string
  keywords?: string
}

export interface GenerateTopicInput {
  storyId: EntityId
  genre: string
  audience: string
  keywords: string
}

export interface GenerateTopicResult {
  storyId?: EntityId
  taskId?: EntityId
  topics: TopicOption[]
}

export type WorkflowTaskStatus =
  | 'WAITING'
  | 'RUNNING'
  | 'REVIEW_REQUIRED'
  | 'SUCCESS'
  | 'FAILED'

export type WorkflowEventStatus = 'waiting' | 'running' | 'completed' | 'failed'

export interface WorkflowProgressEvent {
  id?: EntityId
  node: string
  status: WorkflowEventStatus
  message: string
  score?: number
  revisionNo?: number
  createdTime?: string
}

export interface WorkflowTask {
  taskId: EntityId
  storyId?: EntityId
  topicId?: EntityId
  threadId?: string
  status: WorkflowTaskStatus
  currentNode: string
  progress: number
  score?: number
  revisionCount: number
  maxRevisions: number
  events: WorkflowProgressEvent[]
  errorCode?: string
  errorMessage?: string
  createdTime?: string
  updatedTime?: string
}

export interface StartWorkflowInput {
  storyId: EntityId
  topicId: EntityId
}

export interface CharacterCard {
  id: string
  name: string
  role: string
  publicIdentity: string
  hiddenSecret: string
  coreDesire: string
  greatestFear: string
  personality: string[]
  relationshipToProtagonist: string
  characterArc: string
}

export interface OutlineNode {
  nodeNo: number
  stage: '开篇' | '发展' | '升级' | '高潮' | '结局' | string
  event: string
  conflict: string
  protagonistGoal: string
  emotionalTarget: string
  newInformation: string
  cliffhanger: string
  isTwist: boolean
  setupOrPayoff: string
}

export interface WorkflowScoreDimension {
  key: 'hook' | 'emotion' | 'conflict' | 'twist' | 'adaptation' | string
  label: string
  score: number
  reason: string
  majorProblem: string
  suggestion: string
}

export interface WorkflowScore {
  total: number
  level: string
  dimensions: WorkflowScoreDimension[]
  fatalProblem: string
  revisionPriority: string[]
}

export interface WorkflowReview {
  taskId?: EntityId
  storyId?: EntityId
  threadId?: string
  status?: WorkflowTaskStatus
  versionNo?: number
  revisionCount: number
  title: string
  coreConflict: string
  endingType: string
  characters: CharacterCard[]
  outline: OutlineNode[]
  score: WorkflowScore
  versions: WorkflowReviewVersion[]
  approved?: boolean
  reviewNotes?: string
  updatedTime?: string
}

export interface WorkflowReviewVersion {
  versionNo: number
  label?: string
  status?: string
  outline: OutlineNode[]
  score?: WorkflowScore
  createdTime?: string
}

export interface SubmitWorkflowReviewInput {
  approved: boolean
  notes: string
}

export interface WorkflowSession {
  taskId: EntityId
  storyId: EntityId
  topicId?: EntityId
  threadId?: string
  status: WorkflowTaskStatus
  currentNode: string
  progress: number
  updatedAt: string
}

export * from './chapter'
export * from './release'
