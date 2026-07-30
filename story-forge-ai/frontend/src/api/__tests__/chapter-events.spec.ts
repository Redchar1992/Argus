import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  SseFrameParser,
  subscribeChapterEvents,
} from '@/api/chapter-events'
import { setStoredAuth } from '@/utils/storage'

function streamResponse(chunks: Uint8Array[], status = 200): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk))
      controller.close()
    },
  })
  return {
    ok: status >= 200 && status < 300,
    status,
    body,
  } as Response
}

function encode(value: string) {
  return new TextEncoder().encode(value)
}

describe('chapter SSE parser and subscription', () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.clearAllMocks()
  })

  it('preserves CRLF delimiters split between arbitrary network chunks', () => {
    const parser = new SseFrameParser()

    expect(
      parser.feed('id: redis-1\r\nevent: TOKEN_DELTA\r\ndata: first\r'),
    ).toEqual([])
    const frames = parser.feed('\n\r\n')

    expect(frames).toEqual([
      { id: 'redis-1', event: 'TOKEN_DELTA', data: 'first', retry: undefined },
    ])
  })

  it('joins multiline data fields even when each line arrives in a different chunk', () => {
    const parser = new SseFrameParser()

    expect(parser.feed('event: message\ndata: first line\n')).toEqual([])
    expect(parser.feed('data: second')).toEqual([])
    expect(parser.feed(' line\n\n')).toEqual([
      {
        id: undefined,
        event: 'message',
        data: 'first line\nsecond line',
        retry: undefined,
      },
    ])
  })

  it('decodes UTF-8 characters split inside a multibyte code point', async () => {
    const payload = [
      'id: 1',
      'event: TOKEN_DELTA',
      'data: {"eventId":"1","taskId":700,"type":"TOKEN_DELTA","sequence":1,"data":{"text":"雨夜"}}',
      '',
      'id: 2',
      'event: TASK_FAILED',
      'data: {"eventId":"2","taskId":700,"type":"TASK_FAILED","sequence":2,"status":"FAILED","errorMessage":"停止测试"}',
      '',
      '',
    ].join('\n')
    const bytes = encode(payload)
    const multibyteStart = bytes.findIndex((byte) => byte > 0x7f)
    const fetchImpl = vi.fn().mockResolvedValue(
      streamResponse([
        bytes.slice(0, multibyteStart + 1),
        bytes.slice(multibyteStart + 1, multibyteStart + 2),
        bytes.slice(multibyteStart + 2),
      ]),
    )
    const events: Array<{ type: string; text?: unknown }> = []

    const subscription = subscribeChapterEvents(700, {
      fetchImpl: fetchImpl as typeof fetch,
      onEvent: (event) => {
        events.push({ type: event.type, text: event.data.text })
      },
    })
    await subscription.done

    expect(events).toEqual([
      { type: 'TOKEN_DELTA', text: '雨夜' },
      { type: 'TASK_FAILED', text: undefined },
    ])
  })

  it('sends bearer auth and the supplied Last-Event-ID without query-string tokens', async () => {
    setStoredAuth({ token: 'jwt-secret', userId: 10001 })
    const fetchImpl = vi.fn().mockResolvedValue(
      streamResponse([
        encode(
          'id: redis-10\nevent: TASK_FAILED\ndata: {"eventId":"redis-10","taskId":701,"type":"TASK_FAILED","sequence":10,"status":"FAILED"}\n\n',
        ),
      ]),
    )

    const subscription = subscribeChapterEvents(701, {
      lastEventId: 'redis-9',
      fetchImpl: fetchImpl as typeof fetch,
      onEvent: vi.fn(),
    })
    await subscription.done

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [url, options] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/api/ai-tasks/701/events')
    expect(url).not.toContain('token=')
    expect(options.headers).toMatchObject({
      Authorization: 'Bearer jwt-secret',
      'Last-Event-ID': 'redis-9',
      Accept: 'text/event-stream',
    })
    expect(subscription.lastEventId()).toBe('redis-10')
  })

  it('reconnects after an early EOF and resumes from the latest event ID', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(
        streamResponse([
          encode(
            'id: redis-21\nevent: TOKEN_DELTA\ndata: {"eventId":"redis-21","taskId":702,"type":"TOKEN_DELTA","sequence":21,"data":{"text":"A"}}\n\n',
          ),
        ]),
      )
      .mockResolvedValueOnce(
        streamResponse([
          encode(
            'id: redis-22\nevent: HUMAN_REVIEW_REQUIRED\ndata: {"eventId":"redis-22","taskId":702,"type":"HUMAN_REVIEW_REQUIRED","sequence":22,"status":"REVIEW_REQUIRED","data":{"content":"AB"}}\n\n',
          ),
        ]),
      )
    const received: string[] = []

    const subscription = subscribeChapterEvents(702, {
      reconnectDelayMs: 250,
      maxReconnectDelayMs: 250,
      fetchImpl: fetchImpl as typeof fetch,
      onEvent: (event) => {
        received.push(event.eventId)
      },
    })
    await subscription.done

    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(fetchImpl.mock.calls[1][1].headers).toMatchObject({
      'Last-Event-ID': 'redis-21',
    })
    expect(received).toEqual(['redis-21', 'redis-22'])
  })

  it('does not close on a terminal event name while its status is still running', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(
        streamResponse([
          encode(
            'id: redis-30\nevent: CHAPTER_PLAN_READY\ndata: {"eventId":"redis-30","taskId":703,"type":"CHAPTER_PLAN_READY","sequence":1,"status":"RUNNING"}\n\n',
          ),
        ]),
      )
      .mockResolvedValueOnce(
        streamResponse([
          encode(
            'id: redis-31\nevent: CHAPTER_PLAN_READY\ndata: {"eventId":"redis-31","taskId":703,"type":"CHAPTER_PLAN_READY","sequence":2,"status":"SUCCESS"}\n\n',
          ),
        ]),
      )
    const received: string[] = []

    const subscription = subscribeChapterEvents(703, {
      reconnectDelayMs: 250,
      maxReconnectDelayMs: 250,
      fetchImpl: fetchImpl as typeof fetch,
      onEvent: (event) => {
        received.push(`${event.type}:${event.status}`)
      },
    })
    await subscription.done

    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(received).toEqual([
      'CHAPTER_PLAN_READY:RUNNING',
      'CHAPTER_PLAN_READY:SUCCESS',
    ])
  })
})
