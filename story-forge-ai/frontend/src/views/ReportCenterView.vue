<script setup lang="ts">
import {
  Download,
  DocumentChecked,
  Refresh,
  Lock,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  createExport,
  createRelease,
  getLatestFinalReport,
  listExports,
  listReleases,
  runFinalReview,
} from '@/api/release'
import { API_BASE_URL } from '@/utils/request'
import type { ExportFormat, ExportTask, FinalIssue, FinalReportResponse, StoryRelease } from '@/types'

const route = useRoute()
const router = useRouter()
const storyId = computed(() => String(route.params.storyId))
const report = ref<FinalReportResponse>()
const releases = ref<StoryRelease[]>([])
const exports = ref<ExportTask[]>([])
const loading = ref(true)
const running = ref(false)
const locking = ref(false)
const exportFormat = ref<ExportFormat>('DOCX')
const includeReport = ref(false)

const issues = computed(() => [
  ...(report.value?.report.criticalIssues ?? []),
  ...(report.value?.report.normalIssues ?? []),
])
const latestRelease = computed(() => releases.value[0])

async function load() {
  loading.value = true
  try {
    report.value = await getLatestFinalReport(storyId.value)
  } catch {
    report.value = undefined
  }
  try {
    releases.value = await listReleases(storyId.value)
    exports.value = await listExports(storyId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '报告数据加载失败')
  } finally {
    loading.value = false
  }
}

async function rerun() {
  running.value = true
  try {
    report.value = await runFinalReview(storyId.value)
    ElMessage.success(`终审完成，综合分 ${report.value.total}`)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '终审失败，请先批准全部章节')
  } finally {
    running.value = false
  }
}

async function lockRelease() {
  if (!report.value) return
  locking.value = true
  try {
    const release = await createRelease(storyId.value, report.value.id)
    releases.value = [release, ...releases.value]
    ElMessage.success(`正式版本 V${release.releaseNo} 已锁定`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '正式版本锁定失败')
  } finally {
    locking.value = false
  }
}

async function exportRelease() {
  if (!latestRelease.value) {
    ElMessage.warning('请先锁定正式版本')
    return
  }
  try {
    const task = await createExport(storyId.value, latestRelease.value.id, exportFormat.value, includeReport.value)
    exports.value = [task, ...exports.value]
    if (task.downloadUrl) window.open(`${API_BASE_URL}${task.downloadUrl}`, '_blank', 'noopener,noreferrer')
    ElMessage.success('导出文件已生成')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
  }
}

function openIssue(issue: FinalIssue) {
  const chapter = issue.affectedChapters[0] || issue.evidence[0]?.chapterNo
  if (chapter) router.push({ name: 'chapter-workspace', params: { storyId: storyId.value, chapterNo: chapter }, query: { issue: issue.title } })
}

onMounted(load)
</script>

<template>
  <div class="report-page">
    <section v-if="loading" class="report-loading"><el-skeleton animated :rows="8" /></section>
    <template v-else>
      <header class="report-hero">
        <div>
          <span class="section-kicker">FINAL STORY REVIEW</span>
          <h2>全书终审与正式交付</h2>
          <p>只读取每章 APPROVED 版本，先处理问题清单，再锁定可导出的正式快照。</p>
        </div>
        <el-button type="primary" :icon="Refresh" :loading="running" @click="rerun">重新终审</el-button>
      </header>

      <el-empty v-if="!report" description="还没有终审报告，请先批准全部章节" />
      <template v-else>
        <section class="score-summary">
          <div class="total-score"><span>综合评分</span><strong>{{ report.total }}</strong><em>等级 {{ report.level }}</em><small>{{ report.report.disclaimer }}</small></div>
          <article v-for="section in [report.report.contentQuality, report.report.hitPotential, report.report.shortDramaAdaptation]" :key="section.summary" class="score-card">
            <span>{{ section === report.report.contentQuality ? '内容完成度' : section === report.report.hitPotential ? '爆款潜力' : '短剧适配度' }}</span>
            <strong>{{ section.score }}</strong>
            <p>{{ section.summary }}</p>
          </article>
        </section>

        <section class="report-grid">
          <div class="report-panel">
            <header><div><span class="section-kicker">ACTIONABLE ISSUES</span><h3>问题定位与修改计划</h3></div><span class="issue-count">{{ issues.length }} 项</span></header>
            <el-empty v-if="!issues.length" description="暂未发现结构化问题" />
            <button v-for="issue in issues" :key="`${issue.title}-${issue.affectedChapters.join('-')}`" class="issue-row" type="button" @click="openIssue(issue)">
              <span class="issue-icon" :class="issue.severity.toLowerCase()"><Warning /></span>
              <span><strong>{{ issue.title }}</strong><small>{{ issue.description }}</small><em>{{ issue.severity }} · 第{{ issue.affectedChapters.join('、') }}章</em></span>
            </button>
          </div>
          <div class="report-panel side-panel">
            <header><div><span class="section-kicker">RELEASE SNAPSHOT</span><h3>正式版本</h3></div><el-icon><DocumentChecked /></el-icon></header>
            <p v-if="latestRelease">V{{ latestRelease.releaseNo }} · {{ latestRelease.wordCount }} 字 · {{ latestRelease.status }}</p>
            <p v-else>终审完成后锁定一个不可覆盖的正式快照。</p>
            <el-button type="success" :icon="Lock" :loading="locking" :disabled="Boolean(latestRelease && latestRelease.reportId === report.id)" @click="lockRelease">锁定正式版本</el-button>
            <div v-if="latestRelease" class="export-box">
              <strong>导出作品</strong>
              <el-select v-model="exportFormat" size="small"><el-option label="DOCX" value="DOCX" /><el-option label="TXT" value="TXT" /><el-option label="Markdown" value="MARKDOWN" /><el-option label="JSON" value="JSON" /></el-select>
              <el-checkbox v-model="includeReport">附带分析报告</el-checkbox>
              <el-button size="small" :icon="Download" @click="exportRelease">生成下载文件</el-button>
            </div>
          </div>
        </section>
        <section v-if="exports.length" class="export-history"><span class="section-kicker">EXPORT HISTORY</span><h3>最近导出</h3><div v-for="item in exports.slice(0, 5)" :key="item.exportId" class="export-row"><span>{{ item.fileName || item.format }}</span><el-tag :type="item.status === 'SUCCESS' ? 'success' : item.status === 'FAILED' ? 'danger' : 'warning'">{{ item.status }}</el-tag><a v-if="item.downloadUrl" :href="`${API_BASE_URL}${item.downloadUrl}`" target="_blank" rel="noopener noreferrer">下载</a></div></section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.report-page { display: grid; gap: 24px; }
.report-loading, .report-panel, .export-history { padding: 26px; border: 1px solid var(--sf-line); border-radius: 20px; background: #fff; }
.report-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 30px 36px; border-radius: 24px; color: #fff; background: linear-gradient(120deg, #292154, #51409a); }
.report-hero h2 { margin: 9px 0; font-family: 'STSong', 'Songti SC', serif; font-size: 30px; font-weight: 600; }
.report-hero p { margin: 0; color: #c9c3e6; font-size: 12px; }
.report-hero .section-kicker { color: #c9c3e6; }
.score-summary { display: grid; grid-template-columns: 1.15fr repeat(3, 1fr); gap: 14px; }
.total-score, .score-card { min-height: 150px; padding: 23px; border: 1px solid var(--sf-line); border-radius: 18px; background: #fff; }
.total-score { display: grid; grid-template-columns: auto auto; align-content: center; gap: 5px 13px; background: #fff8ed; border-color: #f1dec6; }
.total-score span, .score-card span { color: var(--sf-ink-muted); font-size: 11px; font-weight: 700; }
.total-score strong { color: var(--sf-ink-strong); font-family: Georgia, serif; font-size: 48px; line-height: 1; }
.total-score em { align-self: end; color: #b96548; font-size: 14px; font-style: normal; font-weight: 800; }
.total-score small { grid-column: 1 / -1; color: #8f8176; font-size: 10px; line-height: 1.5; }
.score-card { display: grid; align-content: center; gap: 6px; }
.score-card strong { color: var(--sf-primary); font-family: Georgia, serif; font-size: 36px; }
.score-card p { margin: 0; color: var(--sf-ink-muted); font-size: 11px; line-height: 1.55; }
.report-grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, .8fr); gap: 18px; }
.report-panel header, .export-history { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.report-panel h3, .export-history h3 { margin: 6px 0 0; color: var(--sf-ink-strong); font-family: 'STSong', 'Songti SC', serif; font-size: 21px; }
.issue-count { padding: 5px 9px; border-radius: 20px; color: #b96548; background: #fff0e9; font-size: 11px; font-weight: 700; }
.issue-row { display: flex; width: 100%; gap: 12px; margin-top: 16px; padding: 14px; border: 1px solid var(--sf-line); border-radius: 13px; text-align: left; background: #fff; cursor: pointer; }
.issue-row:hover { border-color: #b9afff; background: #fbfaff; }
.issue-row > span:last-child { display: grid; gap: 4px; }
.issue-row strong { color: var(--sf-ink-strong); font-size: 13px; }
.issue-row small, .issue-row em { color: var(--sf-ink-muted); font-size: 11px; line-height: 1.45; }
.issue-row em { color: #b96548; font-style: normal; font-weight: 700; }
.issue-icon { display: grid; width: 28px; height: 28px; flex: 0 0 28px; place-items: center; border-radius: 9px; color: #c16b4e; background: #fff0e9; }
.issue-icon.critical { color: #c32942; background: #ffe9ed; }
.side-panel { display: grid; align-content: start; gap: 18px; }
.side-panel > p { margin: 0; color: var(--sf-ink-muted); font-size: 12px; line-height: 1.6; }
.export-box { display: grid; gap: 10px; padding-top: 16px; border-top: 1px solid var(--sf-line); }
.export-box strong { color: var(--sf-ink-strong); font-size: 13px; }
.export-history { display: grid; gap: 12px; }
.export-history h3 { grid-column: 1 / -1; }
.export-row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--sf-line); color: var(--sf-ink-muted); font-size: 12px; }
.export-row span { flex: 1; }
.export-row a { color: var(--sf-primary); text-decoration: none; font-weight: 700; }
@media (max-width: 980px) { .score-summary, .report-grid { grid-template-columns: 1fr 1fr; } .total-score { grid-column: 1 / -1; } }
@media (max-width: 650px) { .report-hero { display: grid; padding: 24px; } .score-summary, .report-grid { grid-template-columns: 1fr; } .total-score { grid-column: auto; } }
</style>
