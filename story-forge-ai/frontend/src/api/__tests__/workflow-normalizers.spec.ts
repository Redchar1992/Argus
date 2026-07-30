import { describe, expect, it } from 'vitest'

import {
  normalizeWorkflowReview,
  normalizeWorkflowTask,
} from '@/api/workflow-normalizers'

function outlineNodes() {
  return Array.from({ length: 20 }, (_, index) => ({
    nodeNo: index + 1,
    stage: index < 3 ? '开篇' : index < 15 ? '升级' : index < 19 ? '高潮' : '结局',
    event: `剧情事件 ${index + 1}`,
    conflict: `冲突 ${index + 1}`,
    protagonistGoal: '查明真相',
    emotionalTarget: '紧张',
    newInformation: `线索 ${index + 1}`,
    cliffhanger: `悬念 ${index + 1}`,
    isTwist: [4, 8, 13, 17].includes(index + 1),
    setupOrPayoff: index < 10 ? '埋下证据' : '回收证据',
  }))
}

const score = {
  hook: { score: 18, reason: '开场冲突直接', majorProblem: '', suggestion: '保留' },
  emotion: { score: 17, reason: '情绪递进明确', majorProblem: '', suggestion: '加强释放' },
  conflict: { score: 16, reason: '冲突持续升级', majorProblem: '', suggestion: '补强动机' },
  twist: { score: 17, reason: '反转能重释线索', majorProblem: '', suggestion: '提前伏笔' },
  adaptation: { score: 18, reason: '可见事件密集', majorProblem: '', suggestion: '保留' },
  fatalProblem: '节点 12 的因果略弱',
  revisionPriority: ['补强反派动机', '提前铺设关键证据'],
}

describe('workflow normalizers', () => {
  it('normalizes the task contract and progress events', () => {
    const task = normalizeWorkflowTask({
      taskId: 90001,
      storyId: 5001,
      topicId: 1001,
      status: 'REVIEW_REQUIRED',
      currentNode: 'human_review',
      progress: 85,
      threadId: 'thread-1',
      score: 84,
      revisionCount: 1,
      maxRevisions: 2,
      progressEvents: [
        {
          node: 'generate_characters',
          status: 'completed',
          message: '人物设定已生成',
          timestamp: '2026-07-30T10:00:00Z',
        },
      ],
    })

    expect(task).toMatchObject({
      taskId: 90001,
      storyId: 5001,
      topicId: 1001,
      status: 'REVIEW_REQUIRED',
      currentNode: 'human_review',
      progress: 85,
      score: 84,
      revisionCount: 1,
    })
    expect(task.events[0]).toMatchObject({
      node: 'generate_characters',
      status: 'completed',
      message: '人物设定已生成',
    })
  })

  it('normalizes 3 characters, exactly 20 nodes, five scores, and versions', () => {
    const review = normalizeWorkflowReview({
      taskId: 90001,
      storyId: 5001,
      status: 'REVIEW_REQUIRED',
      revisionCount: 1,
      characters: [
        {
          name: '林晚',
          role: '主角',
          publicIdentity: '普通设计师',
          hiddenSecret: '集团继承人',
          coreDesire: '夺回母亲留下的公司',
          greatestFear: '再次被家人背叛',
          personality: ['克制', '敏锐'],
          relationshipToProtagonist: '本人',
          characterArc: '从隐忍到主动掌控命运',
        },
        { name: '陈宇', role: '反派', personality: ['自负', '多疑'] },
        { name: '林雪', role: '关键配角', personality: ['摇摆', '重情'] },
      ],
      outline: {
        title: '归来的继承人',
        coreConflict: '林晚必须在前夫吞并集团前找回证据',
        endingType: '复仇后的自我和解',
        nodes: outlineNodes(),
      },
      score,
      versions: [
        {
          versionNo: 1,
          status: 'ARCHIVED',
          createdTime: '2026-07-30T10:00:00Z',
          outline: { nodes: outlineNodes() },
          score,
        },
      ],
    })

    expect(review.characters).toHaveLength(3)
    expect(review.outline).toHaveLength(20)
    expect(review.outline.map((node) => node.nodeNo)).toEqual(
      Array.from({ length: 20 }, (_, index) => index + 1),
    )
    expect(review.score.total).toBe(86)
    expect(review.score.dimensions).toHaveLength(5)
    expect(review.versions[0].outline).toHaveLength(20)
  })
})
