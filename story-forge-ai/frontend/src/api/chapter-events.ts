import { normalizeChapterStreamEvent } from '@/api/chapter-normalizers'
import type {
  ChapterStreamEvent,
  EntityId,
  StreamConnectionState,
} from '@/types'
import { API_BASE_URL } from '@/utils/request'
import { getStoredAuth } from '@/utils/storage'

export interface SseFrame {
  id?: string
  event?: string
  data: string
  retry?: number
}

export class SseFrameParser {
  private buffer = ''

  feed(chunk: string): SseFrame[] {
    this.buffer += chunk
    const frames: SseFrame[] = []
    let boundary = /\r\n\r\n|\n\n|\r\r/.exec(this.buffer)
    while (boundary?.index !== undefined) {
      const block = this.buffer.slice(0, boundary.index)
      this.buffer = this.buffer.slice(boundary.index + boundary[0].length)
      const frame = parseSseBlock(block)
      if (frame) frames.push(frame)
      boundary = /\r\n\r\n|\n\n|\r\r/.exec(this.buffer)
    }
    return frames
  }

  flush(): SseFrame[] {
    const frame = parseSseBlock(this.buffer)
    this.buffer = ''
    return frame ? [frame] : []
  }
}

export function parseSseBlock(block: string): SseFrame | null {
  if (!block.trim()) return null
  const data: string[] = []
  let id: string | undefined
  let event: string | undefined
  let retry: number | undefined

  for (const line of block.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    const rawValue = separator < 0 ? '' : line.slice(separator + 1)
    const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue
    if (field === 'data') data.push(value)
    if (field === 'id' && !value.includes('\0')) id = value
    if (field === 'event') event = value
    if (field === 'retry' && /^\d+$/.test(value)) retry = Number(value)
  }

  if (!data.length && !event && !id) return null
  return { id, event, data: data.join('\n'), retry }
}

function eventFromFrame(frame: SseFrame): ChapterStreamEvent {
  let payload: unknown = {}
  if (frame.data.trim()) {
    try {
      payload = JSON.parse(frame.data)
    } catch {
      payload = {
        type: frame.event || 'MESSAGE',
        data: { text: frame.data },
      }
    }
  }
  return normalizeChapterStreamEvent(payload, { id: frame.id, event: frame.event })
}

const TERMINAL_EVENTS = new Set([
  'CHAPTER_PLAN_READY',
  'HUMAN_REVIEW_REQUIRED',
  'FINAL_READY',
  'REWRITE_PROPOSAL_READY',
  'TASK_FAILED',
])
const TERMINAL_STATUSES = new Set(['SUCCESS', 'REVIEW_REQUIRED', 'FAILED'])

function isTerminalEvent(event: ChapterStreamEvent) {
  return TERMINAL_EVENTS.has(event.type) && Boolean(event.status && TERMINAL_STATUSES.has(event.status))
}

export interface ChapterEventSubscriptionOptions {
  lastEventId?: string
  signal?: AbortSignal
  reconnectDelayMs?: number
  maxReconnectDelayMs?: number
  fetchImpl?: typeof fetch
  onEvent: (event: ChapterStreamEvent) => void | Promise<void>
  onStateChange?: (state: StreamConnectionState) => void
  onError?: (error: unknown) => void
}

export interface ChapterEventSubscription {
  close: () => void
  done: Promise<void>
  lastEventId: () => string
}

function abortableDelay(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    const timer = window.setTimeout(resolve, milliseconds)
    signal.addEventListener(
      'abort',
      () => {
        window.clearTimeout(timer)
        resolve()
      },
      { once: true },
    )
  })
}

export function subscribeChapterEvents(
  taskId: EntityId,
  options: ChapterEventSubscriptionOptions,
): ChapterEventSubscription {
  const controller = new AbortController()
  const fetchImpl = options.fetchImpl ?? fetch
  let cursor = options.lastEventId ?? ''
  let terminal = false
  let serverRetry = options.reconnectDelayMs ?? 800

  const abortFromParent = () => controller.abort()
  options.signal?.addEventListener('abort', abortFromParent, { once: true })

  const done = (async () => {
    let retryAttempt = 0
    while (!controller.signal.aborted && !terminal) {
      options.onStateChange?.(retryAttempt ? 'reconnecting' : 'connecting')
      try {
        const token = getStoredAuth()?.token
        const headers: Record<string, string> = {
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
        }
        if (token) headers.Authorization = `Bearer ${token}`
        if (cursor) headers['Last-Event-ID'] = cursor

        const response = await fetchImpl(`${API_BASE_URL}/api/ai-tasks/${taskId}/events`, {
          method: 'GET',
          headers,
          signal: controller.signal,
        })
        if (response.status === 401) {
          window.dispatchEvent(new CustomEvent('story-forge:unauthorized'))
        }
        if (!response.ok) {
          throw new Error(`SSE连接失败（HTTP ${response.status}）`)
        }
        if (!response.body) throw new Error('浏览器未收到可读取的SSE响应流')

        options.onStateChange?.('connected')
        retryAttempt = 0
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        const parser = new SseFrameParser()

        const handleFrames = async (frames: SseFrame[]) => {
          for (const frame of frames) {
            if (frame.retry !== undefined) serverRetry = frame.retry
            const event = eventFromFrame(frame)
            if (frame.id) cursor = frame.id
            else if (event.eventId) cursor = event.eventId
            await options.onEvent(event)
            if (isTerminalEvent(event)) terminal = true
          }
        }

        try {
          while (!controller.signal.aborted && !terminal) {
            const { value, done: streamDone } = await reader.read()
            if (streamDone) {
              const trailingText = decoder.decode()
              if (trailingText) await handleFrames(parser.feed(trailingText))
              await handleFrames(parser.flush())
              break
            }
            await handleFrames(parser.feed(decoder.decode(value, { stream: true })))
          }
        } finally {
          reader.releaseLock()
        }
      } catch (error) {
        if (controller.signal.aborted) break
        options.onError?.(error)
      }

      if (!controller.signal.aborted && !terminal) {
        retryAttempt += 1
        options.onStateChange?.('reconnecting')
        const baseDelay = Math.max(250, serverRetry)
        const maxDelay = options.maxReconnectDelayMs ?? 10_000
        await abortableDelay(Math.min(maxDelay, baseDelay * 2 ** Math.min(retryAttempt - 1, 4)), controller.signal)
      }
    }
    options.signal?.removeEventListener('abort', abortFromParent)
    options.onStateChange?.(terminal || controller.signal.aborted ? 'closed' : 'failed')
  })()

  return {
    close: () => controller.abort(),
    done,
    lastEventId: () => cursor,
  }
}
