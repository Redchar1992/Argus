import type { EntityId } from './index'

export interface EvidenceLocation {
  chapterNo: number
  description: string
  excerpt?: string
}

export type FinalIssueType =
  | 'CONTINUITY'
  | 'CHARACTER'
  | 'TIMELINE'
  | 'PLOT_THREAD'
  | 'FORESHADOWING'
  | 'PACING'
  | 'REPETITION'
  | 'LANGUAGE'
  | 'ENDING'
  | 'COMMERCIAL'

export type FinalIssueSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'

export interface FinalIssue {
  issueType: FinalIssueType
  severity: FinalIssueSeverity
  title: string
  description: string
  evidence: EvidenceLocation[]
  suggestedFix: string
  affectedChapters: number[]
}

export interface ScoreSection {
  score: number
  summary: string
  strengths: string[]
  weaknesses: string[]
}

export interface FinalStoryReport {
  contentQuality: ScoreSection
  hitPotential: ScoreSection
  shortDramaAdaptation?: ScoreSection
  novelAdaptation?: ScoreSection
  criticalIssues: FinalIssue[]
  normalIssues: FinalIssue[]
  unresolvedThreads: string[]
  unresolvedForeshadowing: string[]
  strongestChapters: number[]
  weakestChapters: number[]
  suggestedTitles: string[]
  suggestedTags: string[]
  revisionOrder: string[]
  total: number
  level: 'S' | 'A' | 'B' | 'C' | 'D' | string
  disclaimer: string
}

export interface FinalReportResponse {
  id: EntityId
  storyId: EntityId
  versionNo: number
  status: string
  report: FinalStoryReport
  total: number
  level: string
  wordCount: number
  contentHash: string
  promptVersion?: string
  modelName?: string
  createdTime?: string
}

export interface StoryRelease {
  id: EntityId
  storyId: EntityId
  releaseNo: number
  title: string
  summary?: string
  tags?: string[]
  outlineVersionId?: EntityId
  reportId?: EntityId
  chapterVersions: Array<{
    chapterNo: number
    chapterId: EntityId
    versionId: EntityId
    title: string
    wordCount: number
  }>
  wordCount: number
  contentHash: string
  status: 'DRAFT' | 'LOCKED' | 'EXPORTED' | 'ARCHIVED' | string
  createdTime?: string
}

export type ExportFormat = 'TXT' | 'MARKDOWN' | 'DOCX' | 'JSON'

export interface ExportTask {
  exportId: EntityId
  storyId: EntityId
  releaseId: EntityId
  format: ExportFormat
  status: 'WAITING' | 'SUCCESS' | 'FAILED' | string
  fileName?: string
  fileSize?: number
  contentType?: string
  downloadUrl?: string
  expiresAt?: string
  errorMessage?: string
  createdTime?: string
  updatedTime?: string
}

export interface AiWallet {
  userId: EntityId
  availableCredits: number
  frozenCredits: number
  consumedCredits: number
  updatedTime?: string
  planCode: string
  dailyLimit: number
  monthlyLimit: number
  dailyRemaining: number
  monthlyRemaining: number
  maxConcurrentTasks: number
}

export interface AiCreditLog {
  id: EntityId
  operationType: string
  amount: number
  balanceBefore: number
  balanceAfter: number
  description?: string
  createdTime?: string
}
