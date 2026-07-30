export function formatDate(value?: string) {
  if (!value) return '刚刚创建'

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(date)
}

export function statusLabel(status?: string) {
  const normalized = status?.toLowerCase()
  if (['generated', 'completed', 'done', 'success'].includes(normalized ?? '')) return '已生成'
  if (['generating', 'processing', 'running'].includes(normalized ?? '')) return '生成中'
  return '构思中'
}

export function scoreTone(score: number) {
  if (score >= 90) return 'excellent'
  if (score >= 80) return 'good'
  return 'normal'
}
