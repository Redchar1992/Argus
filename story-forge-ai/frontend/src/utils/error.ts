import axios from 'axios'

const STATUS_MESSAGES: Record<number, string> = {
  400: '提交内容有误，请检查后重试。',
  401: '登录状态已失效，请重新登录。',
  403: '当前账号没有执行此操作的权限。',
  404: '没有找到请求的内容。',
  409: '该内容已存在，请勿重复提交。',
  429: '请求太频繁，请稍后再试。',
  500: '服务暂时开小差了，请稍后重试。',
  502: 'AI 服务暂时不可用，请稍后重试。',
  503: '服务正在恢复中，请稍后重试。',
}

export function getErrorMessage(error: unknown, fallback = '操作失败，请稍后重试。') {
  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED') return '请求超时，请检查服务状态后重试。'
    if (!error.response) return '无法连接服务器，请确认服务已启动。'

    const payload = error.response.data as
      | { message?: string; msg?: string; error?: string }
      | string
      | undefined

    if (typeof payload === 'string' && payload.trim()) return payload
    if (payload && typeof payload === 'object') {
      const message = payload.message || payload.msg || payload.error
      if (message) return message
    }

    return STATUS_MESSAGES[error.response.status] ?? fallback
  }

  if (error instanceof Error && error.message) return error.message
  return fallback
}

export function canUseOfflineFallback(error: unknown) {
  if (!axios.isAxiosError(error)) return false
  return !error.response || error.response.status >= 500
}
