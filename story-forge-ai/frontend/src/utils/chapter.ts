import type { ChapterStatus, ChapterTaskStatus } from '@/types'

export function chapterStatusLabel(status: ChapterStatus) {
  return (
    {
      EMPTY: '待规划',
      PLANNING: '规划中',
      PLAN_READY: '计划待确认',
      PLAN_APPROVED: '计划已确认',
      GENERATING: '正文生成中',
      REVIEW_REQUIRED: '正文待审核',
      DRAFT: '编辑中',
      APPROVED: '已批准',
      FAILED: '生成失败',
    } satisfies Record<ChapterStatus, string>
  )[status]
}

export function chapterTaskStatusLabel(status: ChapterTaskStatus) {
  return (
    {
      WAITING: '等待执行',
      RUNNING: '正在执行',
      REVIEW_REQUIRED: '等待人工确认',
      SUCCESS: '已完成',
      FAILED: '执行失败',
    } satisfies Record<ChapterTaskStatus, string>
  )[status]
}

export function countChapterWords(content: string) {
  const chineseCharacters = content.match(/[\u3400-\u9fff]/g)?.length ?? 0
  const latinWords = content.match(/[A-Za-z0-9]+(?:['’-][A-Za-z0-9]+)*/g)?.length ?? 0
  return chineseCharacters + latinWords
}

export async function sha256Hex(value: string): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error('当前浏览器不支持安全文本摘要，无法提交局部改写。')
  }
  const digest = await globalThis.crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(value),
  )
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export interface DiffSegment {
  value: string
  changed: boolean
}

export interface SideBySideDiff {
  original: DiffSegment[]
  replacement: DiffSegment[]
}

/**
 * A bounded, readable diff for prose selections. It preserves the common
 * prefix/suffix and highlights the changed middle without an expensive full
 * document diff in the browser.
 */
export function buildSideBySideDiff(original: string, replacement: string): SideBySideDiff {
  const left = Array.from(original)
  const right = Array.from(replacement)
  let prefix = 0
  while (prefix < left.length && prefix < right.length && left[prefix] === right[prefix]) {
    prefix += 1
  }

  let suffix = 0
  while (
    suffix < left.length - prefix &&
    suffix < right.length - prefix &&
    left[left.length - suffix - 1] === right[right.length - suffix - 1]
  ) {
    suffix += 1
  }

  const segments = (characters: string[]): DiffSegment[] => {
    const values: DiffSegment[] = []
    const start = characters.slice(0, prefix).join('')
    const end = suffix ? characters.slice(characters.length - suffix).join('') : ''
    const middle = characters.slice(prefix, suffix ? characters.length - suffix : undefined).join('')
    if (start) values.push({ value: start, changed: false })
    if (middle) values.push({ value: middle, changed: true })
    if (end) values.push({ value: end, changed: false })
    return values
  }

  return { original: segments(left), replacement: segments(right) }
}
