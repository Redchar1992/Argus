import type {
  CharacterCard,
  EntityId,
  OutlineNode,
  WorkflowEventStatus,
  WorkflowProgressEvent,
  WorkflowReview,
  WorkflowReviewVersion,
  WorkflowScore,
  WorkflowScoreDimension,
  WorkflowTask,
  WorkflowTaskStatus,
} from '@/types'

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function parseJson(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const cleaned = value.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '')
  if (!cleaned) return value
  try {
    return JSON.parse(cleaned)
  } catch {
    return value
  }
}

function record(value: unknown): UnknownRecord {
  const parsed = parseJson(value)
  return isRecord(parsed) ? parsed : {}
}

function first(source: UnknownRecord, keys: string[]) {
  for (const key of keys) {
    if (source[key] !== undefined && source[key] !== null) return source[key]
  }
  return undefined
}

function text(value: unknown, fallback = '') {
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return fallback
}

function numberValue(value: unknown, fallback = 0) {
  const parsed = typeof value === 'number' ? value : Number.parseFloat(text(value))
  return Number.isFinite(parsed) ? parsed : fallback
}

function integer(value: unknown, fallback = 0) {
  return Math.round(numberValue(value, fallback))
}

function percentage(value: unknown) {
  return Math.min(100, Math.max(0, integer(value)))
}

function booleanValue(value: unknown) {
  if (typeof value === 'boolean') return value
  return ['true', '1', 'yes'].includes(text(value).toLowerCase())
}

function arrayValue(value: unknown, nestedKeys: string[] = []): unknown[] {
  const parsed = parseJson(value)
  if (Array.isArray(parsed)) return parsed
  if (!isRecord(parsed)) return []
  for (const key of nestedKeys) {
    const nested = arrayValue(parsed[key])
    if (nested.length) return nested
  }
  return []
}

function stringArray(value: unknown) {
  const parsed = parseJson(value)
  if (Array.isArray(parsed)) return parsed.map((item) => text(item)).filter(Boolean)
  if (typeof parsed === 'string') {
    return parsed
      .split(/\n|[；;、]/)
      .map((item) => item.replace(/^\s*[-•\d.)、]+\s*/, '').trim())
      .filter(Boolean)
  }
  return []
}

export function normalizeWorkflowStatus(value: unknown): WorkflowTaskStatus {
  const status = text(value, 'WAITING').toUpperCase().replace(/[\s-]+/g, '_')
  if (
    ['REVIEW_REQUIRED', 'WAITING_REVIEW', 'HUMAN_REVIEW', 'INTERRUPTED', 'PAUSED'].includes(
      status,
    )
  ) {
    return 'REVIEW_REQUIRED'
  }
  if (['SUCCESS', 'SUCCEEDED', 'COMPLETED', 'FINISHED', 'APPROVED'].includes(status)) {
    return 'SUCCESS'
  }
  if (['FAILED', 'ERROR', 'CANCELLED', 'CANCELED'].includes(status)) return 'FAILED'
  if (['RUNNING', 'PROCESSING', 'IN_PROGRESS', 'RETRYING'].includes(status)) return 'RUNNING'
  return 'WAITING'
}

function normalizeEventStatus(value: unknown): WorkflowEventStatus {
  const status = text(value).toLowerCase().replace(/[\s-]+/g, '_')
  if (['completed', 'complete', 'success', 'succeeded', 'done'].includes(status)) {
    return 'completed'
  }
  if (['failed', 'error'].includes(status)) return 'failed'
  if (['running', 'processing', 'in_progress'].includes(status)) return 'running'
  return 'waiting'
}

function normalizeProgressEvent(value: unknown, index: number): WorkflowProgressEvent {
  const source = record(value)
  const rawScore = first(source, ['score', 'totalScore', 'total_score'])
  const rawRevision = first(source, ['revisionNo', 'revision_no', 'revisionCount'])
  return {
    id: first(source, ['id', 'eventId', 'event_id']) as EntityId | undefined,
    node: text(first(source, ['node', 'nodeName', 'node_name', 'type']), `step-${index + 1}`),
    status: normalizeEventStatus(first(source, ['status', 'state'])),
    message: text(first(source, ['message', 'text', 'description']), '工作流节点已更新'),
    score: rawScore === undefined ? undefined : percentage(rawScore),
    revisionNo: rawRevision === undefined ? undefined : integer(rawRevision),
    createdTime:
      text(first(source, ['createdTime', 'created_time', 'createdAt', 'timestamp'])) || undefined,
  }
}

export function normalizeWorkflowTask(value: unknown): WorkflowTask {
  const source = record(value)
  const nestedTask = isRecord(source.task) ? source.task : source
  const scoreValue = first(nestedTask, ['score', 'latestScore', 'latest_score'])
  const scoreRecord = record(scoreValue)
  const score = isRecord(parseJson(scoreValue))
    ? first(scoreRecord, ['total', 'totalScore', 'total_score'])
    : scoreValue
  const rawEvents = first(nestedTask, [
    'events',
    'progressEvents',
    'progress_events',
    'eventLog',
    'event_log',
  ])
  const events = arrayValue(rawEvents, ['events', 'items']).map(normalizeProgressEvent)
  const status = normalizeWorkflowStatus(first(nestedTask, ['status', 'taskStatus', 'task_status']))

  return {
    taskId: (first(nestedTask, ['taskId', 'task_id', 'id']) ?? '') as EntityId,
    storyId: first(nestedTask, ['storyId', 'story_id']) as EntityId | undefined,
    topicId: first(nestedTask, ['topicId', 'topic_id']) as EntityId | undefined,
    threadId: text(first(nestedTask, ['threadId', 'thread_id'])) || undefined,
    status,
    currentNode: text(first(nestedTask, ['currentNode', 'current_node', 'node'])),
    progress: percentage(first(nestedTask, ['progress', 'progressPercent', 'progress_percent'])),
    score: score === undefined || score === null ? undefined : percentage(score),
    revisionCount: integer(first(nestedTask, ['revisionCount', 'revision_count', 'attemptNo']), 0),
    maxRevisions: integer(first(nestedTask, ['maxRevisions', 'max_revisions']), 2),
    events,
    errorCode: text(first(nestedTask, ['errorCode', 'error_code'])) || undefined,
    errorMessage:
      text(first(nestedTask, ['errorMessage', 'error_message', 'message'])) || undefined,
    createdTime:
      text(first(nestedTask, ['createdTime', 'created_time', 'createdAt'])) || undefined,
    updatedTime:
      text(first(nestedTask, ['updatedTime', 'updated_time', 'updatedAt'])) || undefined,
  }
}

function normalizeCharacter(value: unknown, index: number): CharacterCard {
  const source = record(value)
  return {
    id: text(first(source, ['id', 'characterId', 'character_id']), `character-${index + 1}`),
    name: text(first(source, ['name']), `角色 ${index + 1}`),
    role: text(first(source, ['role', 'roleType', 'role_type']), '关键角色'),
    publicIdentity: text(first(source, ['publicIdentity', 'public_identity', 'identity'])),
    hiddenSecret: text(first(source, ['hiddenSecret', 'hidden_secret', 'secret'])),
    coreDesire: text(first(source, ['coreDesire', 'core_desire', 'desire', 'goal'])),
    greatestFear: text(first(source, ['greatestFear', 'greatest_fear', 'fear'])),
    personality: stringArray(first(source, ['personality', 'traits', 'personalityTraits'])),
    relationshipToProtagonist: text(
      first(source, [
        'relationshipToProtagonist',
        'relationship_to_protagonist',
        'relationship',
      ]),
    ),
    characterArc: text(first(source, ['characterArc', 'character_arc', 'arc'])),
  }
}

function normalizeOutlineNode(value: unknown, index: number): OutlineNode {
  const source = record(value)
  return {
    nodeNo: integer(first(source, ['nodeNo', 'node_no', 'number', 'index']), index + 1),
    stage: text(first(source, ['stage', 'phase']), '发展'),
    event: text(first(source, ['event', 'title', 'plot']), `剧情节点 ${index + 1}`),
    conflict: text(first(source, ['conflict'])),
    protagonistGoal: text(first(source, ['protagonistGoal', 'protagonist_goal', 'goal'])),
    emotionalTarget: text(first(source, ['emotionalTarget', 'emotional_target', 'emotion'])),
    newInformation: text(first(source, ['newInformation', 'new_information', 'information'])),
    cliffhanger: text(first(source, ['cliffhanger', 'hook'])),
    isTwist: booleanValue(first(source, ['isTwist', 'is_twist', 'twist'])),
    setupOrPayoff: text(first(source, ['setupOrPayoff', 'setup_or_payoff', 'setupPayoff'])),
  }
}

const SCORE_DIMENSIONS = [
  { key: 'hook', label: '开篇吸引力' },
  { key: 'emotion', label: '情绪强度' },
  { key: 'conflict', label: '冲突升级' },
  { key: 'twist', label: '反转有效性' },
  { key: 'adaptation', label: '短剧适配度' },
] as const

function normalizeWorkflowScore(value: unknown): WorkflowScore {
  const source = record(value)
  const dimensions: WorkflowScoreDimension[] = SCORE_DIMENSIONS.map(({ key, label }) => {
    const dimension = record(source[key])
    return {
      key,
      label,
      score: Math.min(20, Math.max(0, integer(first(dimension, ['score', 'value'])))),
      reason: text(first(dimension, ['reason', 'evidence'])),
      majorProblem: text(first(dimension, ['majorProblem', 'major_problem', 'problem'])),
      suggestion: text(first(dimension, ['suggestion', 'revisionSuggestion'])),
    }
  })
  const calculatedTotal = dimensions.reduce((total, dimension) => total + dimension.score, 0)
  const explicitTotal = first(source, ['total', 'totalScore', 'total_score'])
  const total = explicitTotal === undefined ? calculatedTotal : percentage(explicitTotal)
  const level =
    text(first(source, ['level', 'grade'])) ||
    (total >= 90 ? 'S' : total >= 80 ? 'A' : total >= 70 ? 'B' : 'C')

  return {
    total,
    level,
    dimensions,
    fatalProblem: text(first(source, ['fatalProblem', 'fatal_problem'])),
    revisionPriority: stringArray(
      first(source, ['revisionPriority', 'revision_priority', 'priorities']),
    ),
  }
}

export function normalizeWorkflowReview(value: unknown): WorkflowReview {
  const source = record(value)
  const payload = isRecord(source.review) ? source.review : source
  const characterValue = first(payload, ['characters', 'characterPack', 'character_pack'])
  const outlineValue = parseJson(first(payload, ['outline', 'outlineResult', 'outline_result']))
  const outlineRecord = record(outlineValue)
  const outlineItems = Array.isArray(outlineValue)
    ? outlineValue
    : arrayValue(first(outlineRecord, ['nodes', 'outline', 'items']))
  const scoreValue = first(payload, ['score', 'storyScore', 'story_score'])
  const versions: WorkflowReviewVersion[] = arrayValue(
    first(payload, ['versions', 'outlineVersions', 'outline_versions']),
  ).map((item, index) => {
    const version = record(item)
    const versionOutlineValue = parseJson(first(version, ['outline', 'content', 'nodes']))
    const versionOutlineRecord = record(versionOutlineValue)
    const versionNodes = Array.isArray(versionOutlineValue)
      ? versionOutlineValue
      : arrayValue(first(versionOutlineRecord, ['nodes', 'outline', 'items']))
    const rawVersionScore = first(version, ['score', 'storyScore', 'story_score'])
    return {
      versionNo: integer(first(version, ['versionNo', 'version_no', 'version']), index + 1),
      label: text(first(version, ['label', 'name'])) || undefined,
      status: text(first(version, ['status'])) || undefined,
      outline: versionNodes
        .map(normalizeOutlineNode)
        .sort((left, right) => left.nodeNo - right.nodeNo),
      score: rawVersionScore === undefined ? undefined : normalizeWorkflowScore(rawVersionScore),
      createdTime:
        text(first(version, ['createdTime', 'created_time', 'createdAt'])) || undefined,
    }
  })
  const rawVersionNo = first(payload, ['versionNo', 'version_no', 'outlineVersion'])
  const inferredVersionNo = versions.length
    ? Math.max(...versions.map((version) => version.versionNo))
    : undefined

  return {
    taskId: first(payload, ['taskId', 'task_id']) as EntityId | undefined,
    storyId: first(payload, ['storyId', 'story_id']) as EntityId | undefined,
    threadId: text(first(payload, ['threadId', 'thread_id'])) || undefined,
    status:
      first(payload, ['status', 'taskStatus']) === undefined
        ? undefined
        : normalizeWorkflowStatus(first(payload, ['status', 'taskStatus'])),
    versionNo: rawVersionNo === undefined ? inferredVersionNo : integer(rawVersionNo),
    revisionCount: integer(first(payload, ['revisionCount', 'revision_count']), 0),
    title: text(first(outlineRecord, ['title']), text(first(payload, ['title']))),
    coreConflict: text(
      first(outlineRecord, ['coreConflict', 'core_conflict']),
      text(first(payload, ['coreConflict', 'core_conflict'])),
    ),
    endingType: text(
      first(outlineRecord, ['endingType', 'ending_type']),
      text(first(payload, ['endingType', 'ending_type'])),
    ),
    characters: arrayValue(characterValue, ['characters', 'items']).map(normalizeCharacter),
    outline: outlineItems.map(normalizeOutlineNode).sort((left, right) => left.nodeNo - right.nodeNo),
    score: normalizeWorkflowScore(scoreValue),
    versions,
    approved:
      first(payload, ['approved']) === undefined
        ? undefined
        : booleanValue(first(payload, ['approved'])),
    reviewNotes: text(first(payload, ['reviewNotes', 'review_notes', 'notes'])) || undefined,
    updatedTime:
      text(first(payload, ['updatedTime', 'updated_time', 'createdTime'])) || undefined,
  }
}
