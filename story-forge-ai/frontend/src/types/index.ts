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
