import type {
  WorkflowEventStatus,
  WorkflowProgressEvent,
  WorkflowTask,
  WorkflowTaskStatus,
} from '@/types'

export interface WorkflowTimelineItem {
  key: string
  node: string
  title: string
  description: string
  status: WorkflowEventStatus
  score?: number
  revisionNo?: number
}

const NODE_LABELS: Record<string, string> = {
  generate_characters: '生成人物卡',
  character: '生成人物卡',
  characters: '生成人物卡',
  generate_outline: '生成 20 节点大纲',
  outline: '生成 20 节点大纲',
  score_outline: '商业评分',
  score: '商业评分',
  revise_outline: '自动修改大纲',
  revise: '自动修改大纲',
  revision: '自动修改大纲',
  human_review: '等待人工审核',
  review: '等待人工审核',
  finish: '保存正式版本',
  completed: '保存正式版本',
}

const NODE_DESCRIPTIONS: Record<string, string> = {
  generate_characters: '建立 3–6 名角色的欲望、秘密与人物弧光',
  generate_outline: '以人物关系推动恰好 20 个连续剧情节点',
  score_outline: '从五个商业维度进行严格评估',
  revise_outline: '针对致命问题优化大纲，不改变核心设定',
  human_review: 'AI 已暂停，等待你的判断与修改意见',
  finish: '人物、大纲与评分已保存为正式版本',
}

const BASE_STEPS = [
  'generate_characters',
  'generate_outline',
  'score_outline',
  'human_review',
] as const

function canonicalNode(value: string) {
  const node = value.toLowerCase().replace(/[\s-]+/g, '_')
  if (node.includes('character')) return 'generate_characters'
  if (node.includes('outline') && (node.includes('revise') || node.includes('revision'))) {
    return 'revise_outline'
  }
  if (node.includes('outline')) return 'generate_outline'
  if (node.includes('score')) return 'score_outline'
  if (node.includes('review') || node.includes('interrupt')) return 'human_review'
  if (node.includes('finish') || node.includes('complete')) return 'finish'
  return node
}

export function workflowNodeLabel(node: string) {
  const canonical = canonicalNode(node)
  return NODE_LABELS[canonical] ?? NODE_LABELS[node] ?? '处理故事方案'
}

export function workflowStatusLabel(status: WorkflowTaskStatus) {
  return {
    WAITING: '等待执行',
    RUNNING: '正在生成',
    REVIEW_REQUIRED: '等待审核',
    SUCCESS: '已完成',
    FAILED: '执行失败',
  }[status]
}

export function workflowProgress(task: WorkflowTask) {
  if (task.progress > 0) return task.progress
  if (task.status === 'SUCCESS') return 100
  if (task.status === 'REVIEW_REQUIRED') return 88
  if (task.status === 'FAILED') return Math.max(8, inferredNodeProgress(task.currentNode))
  return inferredNodeProgress(task.currentNode)
}

function inferredNodeProgress(node: string) {
  const canonical = canonicalNode(node)
  return {
    generate_characters: 18,
    generate_outline: 42,
    score_outline: 65,
    revise_outline: 72,
    human_review: 88,
    finish: 100,
  }[canonical] ?? 6
}

function fromEvent(event: WorkflowProgressEvent, index: number): WorkflowTimelineItem {
  const node = canonicalNode(event.node)
  const revisionLabel = event.revisionNo ? ` · 第 ${event.revisionNo} 轮` : ''
  return {
    key: String(event.id ?? `${event.node}-${index}`),
    node,
    title: `${workflowNodeLabel(node)}${revisionLabel}`,
    description: event.message || NODE_DESCRIPTIONS[node] || '节点状态已更新',
    status: event.status,
    score: event.score,
    revisionNo: event.revisionNo,
  }
}

export function buildWorkflowTimeline(task: WorkflowTask): WorkflowTimelineItem[] {
  if (task.events.length) {
    const items = task.events.map(fromEvent)
    const current = canonicalNode(task.currentNode)
    const hasCurrent = items.some((item) => item.node === current && item.status !== 'completed')

    if (task.status === 'REVIEW_REQUIRED' && !items.some((item) => item.node === 'human_review')) {
      items.push({
        key: 'human-review-current',
        node: 'human_review',
        title: workflowNodeLabel('human_review'),
        description: NODE_DESCRIPTIONS.human_review,
        status: 'running',
      })
    } else if (task.status === 'FAILED' && !hasCurrent) {
      items.push({
        key: 'failed-current',
        node: current,
        title: workflowNodeLabel(current),
        description: task.errorMessage || '工作流在此节点停止',
        status: 'failed',
      })
    } else if (
      task.status === 'RUNNING' &&
      current &&
      !hasCurrent &&
      !items.some((item) => item.node === current && item.status === 'completed')
    ) {
      items.push({
        key: 'running-current',
        node: current,
        title: workflowNodeLabel(current),
        description: NODE_DESCRIPTIONS[current] || 'Agent 正在处理',
        status: 'running',
      })
    }
    return items
  }

  const current = canonicalNode(task.currentNode)
  const currentIndex = BASE_STEPS.indexOf(current as (typeof BASE_STEPS)[number])
  return BASE_STEPS.map((node, index) => {
    let status: WorkflowEventStatus = 'waiting'
    if (task.status === 'SUCCESS') status = 'completed'
    else if (task.status === 'REVIEW_REQUIRED') {
      status = node === 'human_review' ? 'running' : 'completed'
    } else if (task.status === 'FAILED') {
      status = index < currentIndex ? 'completed' : index === currentIndex ? 'failed' : 'waiting'
    } else if (currentIndex >= 0) {
      status = index < currentIndex ? 'completed' : index === currentIndex ? 'running' : 'waiting'
    } else if (index === 0 && task.status === 'RUNNING') status = 'running'

    return {
      key: node,
      node,
      title: workflowNodeLabel(node),
      description: NODE_DESCRIPTIONS[node],
      status,
      score: node === 'score_outline' ? task.score : undefined,
    }
  })
}
