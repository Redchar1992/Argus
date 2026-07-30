import type {
  AuthResult,
  EntityId,
  GenerateTopicResult,
  StoryProject,
  TopicOption,
  TopicScoreDetail,
} from '@/types'

type UnknownRecord = Record<string, unknown>

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function firstValue(record: UnknownRecord, keys: string[]) {
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) return record[key]
  }
  return undefined
}

function text(value: unknown, fallback = '') {
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number') return String(value)
  return fallback
}

function stringList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value
      .map((item) => (isRecord(item) ? text(firstValue(item, ['reason', 'text', 'label'])) : text(item)))
      .filter(Boolean)
  }

  if (typeof value === 'string') {
    return value
      .split(/\n|[；;、]/)
      .map((item) => item.replace(/^\s*[-•\d.)、]+\s*/, '').trim())
      .filter(Boolean)
  }

  return []
}

function parsePossibleJson(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const cleaned = value.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '')
  if (!cleaned) return value

  try {
    return JSON.parse(cleaned)
  } catch {
    return value
  }
}

function findArray(value: unknown, depth = 0): unknown[] {
  const parsed = parsePossibleJson(value)
  if (Array.isArray(parsed)) return parsed
  if (!isRecord(parsed) || depth > 3) return []

  const keys = [
    'topics',
    'generatedTopics',
    'generated_topics',
    'results',
    'result',
    'options',
    'items',
    'list',
    'records',
    'content',
    'data',
  ]
  for (const key of keys) {
    if (parsed[key] === undefined) continue
    const result = findArray(parsed[key], depth + 1)
    if (result.length) return result
  }
  return []
}

function numericScore(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number.parseFloat(text(value))
  if (!Number.isFinite(parsed)) return 0
  return Math.min(100, Math.max(0, Math.round(parsed)))
}

const SCORE_DIMENSIONS = [
  { dimension: 'conflict', label: '冲突强度' },
  { dimension: 'reversal', label: '反转张力' },
  { dimension: 'emotionalValue', label: '情绪价值' },
  { dimension: 'shortDramaFit', label: '短剧适配' },
] as const

function dimensionLabel(dimension: string) {
  return SCORE_DIMENSIONS.find((item) => item.dimension === dimension)?.label ?? dimension
}

function normalizeScoreDetails(value: unknown): TopicScoreDetail[] {
  const details: TopicScoreDetail[] = []

  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      if (!isRecord(item)) return
      const fallback = SCORE_DIMENSIONS[index]
      const dimension = text(
        firstValue(item, ['dimension', 'type', 'key', 'name']),
        fallback?.dimension ?? `dimension-${index + 1}`,
      )
      details.push({
        dimension,
        label: text(firstValue(item, ['label', 'name']), dimensionLabel(dimension)),
        score: numericScore(firstValue(item, ['score', 'value'])),
        reason: text(firstValue(item, ['reason', 'text', 'description'])),
      })
    })
    return details
  }

  if (isRecord(value)) {
    for (const [dimension, item] of Object.entries(value)) {
      if (!isRecord(item)) continue
      details.push({
        dimension,
        label: text(firstValue(item, ['label', 'name']), dimensionLabel(dimension)),
        score: numericScore(firstValue(item, ['score', 'value'])),
        reason: text(firstValue(item, ['reason', 'text', 'description'])),
      })
    }
  }

  return details
}

export function normalizeTopic(value: unknown, index: number): TopicOption {
  const topic = isRecord(value) ? value : {}
  const conflict = text(firstValue(topic, ['conflict', 'openingConflict', 'opening_conflict']))
  const twist = text(firstValue(topic, ['twist', 'reversal', 'identityTwist', 'identity_twist']))
  const emotionalValue = text(
    firstValue(topic, ['emotionalValue', 'emotional_value', 'emotion', 'emotionValue']),
  )
  const rawScoreReasons = firstValue(topic, ['scoreReasons', 'score_reasons'])
  const scoreDetails = normalizeScoreDetails(rawScoreReasons)
  const reasons = stringList(firstValue(topic, ['reasons', 'reason', 'highlights']))
  scoreDetails
    .map((detail) => detail.reason)
    .filter(Boolean)
    .forEach((reason) => reasons.push(reason))

  if (!reasons.length) {
    ;[conflict, twist, emotionalValue].filter(Boolean).forEach((item) => reasons.push(item))
  }

  const explicitScore = numericScore(
    firstValue(topic, ['score', 'commercialScore', 'commercial_score', 'totalScore']),
  )
  const detailAverage = scoreDetails.length
    ? Math.round(
        scoreDetails.reduce((total, detail) => total + detail.score, 0) / scoreDetails.length,
      )
    : 0

  return {
    id: text(firstValue(topic, ['id', 'topicId', 'topic_id']), `topic-${index + 1}`),
    title: text(firstValue(topic, ['title', 'topic', 'name']), `故事方案 ${index + 1}`),
    hook: text(
      firstValue(topic, ['hook', 'sellingPoint', 'selling_point', 'coreHook', 'core_hook']),
      '高能开场，悬念持续升级',
    ),
    summary: text(
      firstValue(topic, ['summary', 'description', 'story', 'outline', 'synopsis']),
      '围绕核心冲突展开，在关键节点完成身份与关系反转。',
    ),
    score: explicitScore || detailAverage,
    reasons: [...new Set(reasons)],
    scoreDetails,
    tags: stringList(firstValue(topic, ['tags', 'keywords', 'labels'])),
    conflict: conflict || undefined,
    twist: twist || undefined,
    emotionalValue: emotionalValue || undefined,
  }
}

export function normalizeTopics(value: unknown): TopicOption[] {
  return findArray(value).map(normalizeTopic)
}

export function normalizeGenerateResult(value: unknown): GenerateTopicResult {
  const record = isRecord(value) ? value : {}
  return {
    storyId: firstValue(record, ['storyId', 'story_id']) as EntityId | undefined,
    taskId: firstValue(record, ['taskId', 'task_id', 'id']) as EntityId | undefined,
    topics: normalizeTopics(value),
  }
}

export function normalizeStory(value: unknown): StoryProject {
  const story = isRecord(value) ? value : {}
  const topics = normalizeTopics(
    firstValue(story, [
      'topics',
      'results',
      'topicResults',
      'topic_results',
      'aiResults',
      'generatedTopics',
      'generated_topics',
    ]),
  )
  const rawSelectedTopic = parsePossibleJson(
    firstValue(story, ['selectedTopic', 'selected_topic']),
  )
  const selectedTopic = isRecord(rawSelectedTopic)
    ? normalizeTopic(rawSelectedTopic, topics.length)
    : null

  if (
    selectedTopic &&
    !topics.some(
      (topic) => topic.id === selectedTopic.id || topic.title === selectedTopic.title,
    )
  ) {
    topics.unshift(selectedTopic)
  }

  const explicitSelectedId = text(
    firstValue(story, ['selectedTopicId', 'selected_topic_id']),
  )
  const primitiveSelectedId =
    typeof rawSelectedTopic === 'string' || typeof rawSelectedTopic === 'number'
      ? text(rawSelectedTopic)
      : ''

  return {
    id: (firstValue(story, ['id', 'storyId', 'story_id']) ?? '') as EntityId,
    userId: firstValue(story, ['userId', 'user_id']) as EntityId | undefined,
    title: text(firstValue(story, ['title', 'name']), '未命名故事'),
    genre: text(firstValue(story, ['genre', 'category']), '未分类'),
    audience: text(firstValue(story, ['audience', 'targetAudience', 'target_audience'])) || undefined,
    keywords: text(firstValue(story, ['keywords', 'direction'])) || undefined,
    status: text(firstValue(story, ['status']), topics.length ? 'generated' : 'draft'),
    createdTime:
      text(firstValue(story, ['createdTime', 'created_time', 'createdAt', 'created_at'])) || undefined,
    updatedTime:
      text(firstValue(story, ['updatedTime', 'updated_time', 'updatedAt', 'updated_at'])) || undefined,
    topics: topics.length ? topics : undefined,
    selectedTopicId:
      explicitSelectedId ||
      primitiveSelectedId ||
      selectedTopic?.id ||
      undefined,
  }
}

export function normalizeStoryList(value: unknown): StoryProject[] {
  return findArray(value).map(normalizeStory)
}

export function normalizeAuth(value: unknown, username?: string): AuthResult {
  const parsed = parsePossibleJson(value)
  const auth = isRecord(parsed) ? parsed : {}
  const nested = isRecord(auth.data) ? auth.data : auth
  const token = text(firstValue(nested, ['token', 'accessToken', 'access_token', 'jwt']))
  const user = isRecord(nested.user) ? nested.user : {}
  const userId = (firstValue(nested, ['userId', 'user_id']) ??
    firstValue(user, ['id', 'userId'])) as EntityId | undefined

  if (!token) throw new Error('登录成功，但服务未返回访问令牌。')

  return {
    token,
    userId: userId ?? '',
    username: text(firstValue(nested, ['username']), text(user.username, username)),
  }
}
