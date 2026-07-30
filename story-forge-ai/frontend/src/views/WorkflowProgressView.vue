<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  CircleCheck,
  Connection,
  MagicStick,
  RefreshRight,
  Timer,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowTask } from '@/types'
import { getErrorMessage } from '@/utils/error'
import {
  buildWorkflowTimeline,
  workflowNodeLabel,
  workflowProgress,
  workflowStatusLabel,
} from '@/utils/workflow'

const route = useRoute()
const router = useRouter()
const workflowStore = useWorkflowStore()
const initialLoading = ref(true)
const connectionLost = ref(false)
const lastSyncedAt = ref<Date>()
const pollError = ref('')
let stopPolling: (() => void) | undefined

const taskId = computed(() => String(route.params.taskId))
const routeStoryId = computed(() => String(route.params.storyId))
const task = computed(() => workflowStore.tasks[taskId.value])
const timeline = computed(() => (task.value ? buildWorkflowTimeline(task.value) : []))
const progress = computed(() => (task.value ? workflowProgress(task.value) : 0))
const isReviewReady = computed(() => task.value?.status === 'REVIEW_REQUIRED')
const isCompleted = computed(() => task.value?.status === 'SUCCESS')
const isFailed = computed(() => task.value?.status === 'FAILED')
const storyId = computed(() => String(task.value?.storyId ?? routeStoryId.value))

const currentMessage = computed(() => {
  if (!task.value) return '正在连接工作流…'
  if (isReviewReady.value) return '人物、大纲与评分已经就绪，请进入人工审核。'
  if (isCompleted.value) return '故事方案已确认，所有产物已保存为正式版本。'
  if (isFailed.value) return task.value.errorMessage || '工作流执行失败，请返回故事后重试。'
  return `${workflowNodeLabel(task.value.currentNode)}，请稍候…`
})

function handleTaskUpdate(nextTask: WorkflowTask) {
  initialLoading.value = false
  connectionLost.value = false
  pollError.value = ''
  lastSyncedAt.value = new Date()
  if (['REVIEW_REQUIRED', 'SUCCESS', 'FAILED'].includes(nextTask.status)) {
    stopPolling?.()
  }
}

function handlePollError(error: unknown) {
  initialLoading.value = false
  connectionLost.value = true
  pollError.value = getErrorMessage(error, '暂时无法获取最新进度。')
}

function beginPolling() {
  stopPolling?.()
  stopPolling = workflowStore.startPolling(taskId.value, {
    onUpdate: handleTaskUpdate,
    onError: handlePollError,
  })
}

async function refreshNow() {
  try {
    handleTaskUpdate(await workflowStore.fetchTask(taskId.value))
    if (task.value && !['REVIEW_REQUIRED', 'SUCCESS', 'FAILED'].includes(task.value.status)) {
      beginPolling()
    }
  } catch (error) {
    handlePollError(error)
    ElMessage.error(pollError.value)
  }
}

function goToReview() {
  router.push({
    name: 'workflow-review',
    params: { storyId: storyId.value, taskId: taskId.value },
  })
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible' && task.value) {
    void refreshNow()
  }
}

onMounted(() => {
  workflowStore.restoreTask(taskId.value, routeStoryId.value)
  beginPolling()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stopPolling?.()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div class="workflow-progress-page">
    <header class="progress-hero">
      <button
        class="back-button"
        type="button"
        @click="router.push({ name: 'story-detail', params: { id: storyId } })"
      >
        <el-icon><ArrowLeft /></el-icon>
        返回故事方案
      </button>

      <div class="hero-content">
        <div class="agent-orbit" :class="{ complete: isCompleted, failed: isFailed }">
          <span class="orbit orbit-one" />
          <span class="orbit orbit-two" />
          <el-icon v-if="isCompleted"><CircleCheck /></el-icon>
          <el-icon v-else-if="isFailed"><Warning /></el-icon>
          <el-icon v-else><MagicStick /></el-icon>
        </div>
        <div class="hero-copy">
          <span>AGENT WORKFLOW · TASK #{{ taskId }}</span>
          <h2>
            {{
              isReviewReady
                ? '方案已就绪，等待你的审核'
                : isCompleted
                  ? '故事创作方案已确认'
                  : isFailed
                    ? '工作流未能完成'
                    : 'AI 编剧团队正在协作'
            }}
          </h2>
          <p>{{ currentMessage }}</p>
        </div>
        <div class="progress-number">
          <strong>{{ progress }}</strong>
          <span>%</span>
        </div>
      </div>

      <div class="progress-track">
        <span :style="{ width: `${progress}%` }" />
      </div>
    </header>

    <div v-if="connectionLost" class="connection-banner">
      <el-icon><Connection /></el-icon>
      <div>
        <strong>连接暂时中断</strong>
        <span>{{ pollError }} 系统会每 2 秒自动重连，刷新页面也不会丢失任务。</span>
      </div>
      <el-button text :icon="RefreshRight" @click="refreshNow">立即重试</el-button>
    </div>

    <section class="workflow-card">
      <header class="card-header">
        <div>
          <span>WORKFLOW TIMELINE</span>
          <h3>创作进度</h3>
        </div>
        <div class="task-status" :class="task?.status.toLowerCase()">
          <i />
          {{ task ? workflowStatusLabel(task.status) : '正在连接' }}
        </div>
      </header>

      <div v-if="initialLoading && !task" class="timeline-skeleton">
        <el-skeleton v-for="index in 4" :key="index" animated>
          <template #template>
            <div class="skeleton-row">
              <el-skeleton-item variant="circle" style="width: 36px; height: 36px" />
              <div>
                <el-skeleton-item variant="h3" style="width: 150px" />
                <el-skeleton-item variant="text" style="width: 280px; margin-top: 8px" />
              </div>
            </div>
          </template>
        </el-skeleton>
      </div>

      <div v-else class="timeline">
        <article
          v-for="(item, index) in timeline"
          :key="item.key"
          class="timeline-item"
          :class="item.status"
        >
          <div class="step-rail">
            <span class="step-icon">
              <el-icon v-if="item.status === 'completed'"><CircleCheck /></el-icon>
              <el-icon v-else-if="item.status === 'failed'"><Warning /></el-icon>
              <el-icon v-else-if="item.status === 'running'"><MagicStick /></el-icon>
              <span v-else>{{ index + 1 }}</span>
            </span>
            <i v-if="index < timeline.length - 1" />
          </div>
          <div class="step-copy">
            <div>
              <h4>{{ item.title }}</h4>
              <span v-if="item.score !== undefined" class="score-chip">
                {{ item.score }} 分
              </span>
              <span v-if="item.status === 'running'" class="running-chip">进行中</span>
            </div>
            <p>{{ item.description }}</p>
          </div>
        </article>
      </div>

      <footer class="card-footer">
        <div>
          <el-icon><Timer /></el-icon>
          <span>
            {{
              lastSyncedAt
                ? `最近同步 ${lastSyncedAt.toLocaleTimeString('zh-CN', { hour12: false })}`
                : '任务状态会自动同步'
            }}
          </span>
        </div>
        <el-button
          v-if="isReviewReady || isCompleted"
          type="primary"
          size="large"
          @click="goToReview"
        >
          {{ isCompleted ? '查看正式方案' : '进入大纲审核' }}
          <el-icon><ArrowRight /></el-icon>
        </el-button>
        <el-button
          v-else-if="isFailed"
          size="large"
          @click="router.push({ name: 'story-detail', params: { id: storyId } })"
        >
          返回故事重新开始
        </el-button>
        <span v-else class="leave-hint">可以离开此页面，任务会继续在后台运行</span>
      </footer>
    </section>

    <aside class="recovery-note">
      <strong>可暂停、可恢复</strong>
      <p>
        任务 ID 已保存在当前账号下。即使网络中断或刷新浏览器，也会从同一工作流线程恢复。
      </p>
    </aside>
  </div>
</template>

<style scoped>
.workflow-progress-page {
  display: grid;
  max-width: 920px;
  gap: 18px;
  margin: 0 auto;
}

.progress-hero {
  position: relative;
  overflow: hidden;
  padding: 23px 31px 28px;
  border-radius: 24px;
  color: #fff;
  background:
    radial-gradient(circle at 84% 0%, rgba(139, 117, 255, 0.32), transparent 30%),
    linear-gradient(125deg, #201a45 0%, #302558 100%);
  box-shadow: 0 18px 48px rgba(36, 26, 79, 0.16);
}

.progress-hero::after {
  position: absolute;
  right: -70px;
  bottom: -170px;
  width: 340px;
  height: 340px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 50%;
  box-shadow: 0 0 0 45px rgba(255, 255, 255, 0.018);
  content: '';
}

.back-button {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  color: #aaa3bf;
  background: transparent;
  cursor: pointer;
  font-size: 9px;
}

.back-button:hover {
  color: #fff;
}

.hero-content {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  gap: 22px;
  align-items: center;
  margin: 30px 0 26px;
}

.agent-orbit {
  position: relative;
  display: grid;
  width: 64px;
  height: 64px;
  place-items: center;
  border-radius: 50%;
  color: #ffcd91;
  background: rgba(255, 255, 255, 0.08);
}

.agent-orbit > .el-icon {
  font-size: 24px;
  animation: pulse 1.8s ease-in-out infinite;
}

.agent-orbit.complete {
  color: #86dfba;
}

.agent-orbit.failed {
  color: #ff9d8e;
}

.agent-orbit.complete > .el-icon,
.agent-orbit.failed > .el-icon {
  animation: none;
}

.orbit {
  position: absolute;
  border: 1px solid rgba(155, 137, 255, 0.4);
  border-radius: 50%;
}

.orbit-one {
  inset: -7px;
  border-right-color: transparent;
  animation: spin 3s linear infinite;
}

.orbit-two {
  inset: 7px;
  border-bottom-color: transparent;
  animation: spin 2s linear infinite reverse;
}

.complete .orbit,
.failed .orbit {
  animation-play-state: paused;
}

.hero-copy > span {
  color: #b8afdf;
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 2px;
}

.hero-copy h2 {
  margin: 7px 0;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(23px, 3vw, 31px);
  font-weight: 550;
}

.hero-copy p {
  margin: 0;
  color: #aaa4bd;
  font-size: 10px;
  line-height: 1.6;
}

.progress-number {
  display: flex;
  align-items: baseline;
  color: #fff;
}

.progress-number strong {
  font-family: Georgia, serif;
  font-size: 37px;
  font-weight: 500;
}

.progress-number span {
  margin-left: 2px;
  color: #9b93b3;
  font-size: 12px;
}

.progress-track {
  position: relative;
  z-index: 1;
  overflow: hidden;
  height: 5px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #7360e7, #f0aa70);
  box-shadow: 0 0 14px rgba(224, 158, 108, 0.36);
  transition: width 500ms ease;
}

.connection-banner {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  gap: 11px;
  align-items: center;
  padding: 13px 15px;
  border: 1px solid #eccfba;
  border-radius: 13px;
  color: #9b563e;
  background: #fff7ef;
}

.connection-banner > .el-icon {
  font-size: 20px;
}

.connection-banner div {
  display: grid;
  gap: 2px;
}

.connection-banner strong {
  font-size: 10px;
}

.connection-banner span {
  color: #95776c;
  font-size: 9px;
}

.workflow-card {
  overflow: hidden;
  border: 1px solid var(--sf-line);
  border-radius: 21px;
  background: #fff;
  box-shadow: 0 10px 35px rgba(42, 32, 79, 0.05);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22px 26px 19px;
  border-bottom: 1px solid #efecf2;
}

.card-header > div:first-child {
  display: grid;
  gap: 3px;
}

.card-header div > span {
  color: var(--sf-accent);
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 1.8px;
}

.card-header h3 {
  margin: 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.task-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 9px;
  border-radius: 999px;
  color: #8a7089;
  background: #f4f1f6;
  font-size: 8px;
  font-weight: 750;
}

.task-status i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #a69aaa;
}

.task-status.running,
.task-status.waiting {
  color: #6452c7;
  background: #f0edff;
}

.task-status.running i {
  background: #6955d9;
  box-shadow: 0 0 0 3px rgba(105, 85, 217, 0.12);
  animation: blink 1.2s ease-in-out infinite;
}

.task-status.review_required {
  color: #a85c43;
  background: #fff0e8;
}

.task-status.success {
  color: #267b5b;
  background: #eaf7f1;
}

.task-status.failed {
  color: #b34848;
  background: #fff0ef;
}

.timeline,
.timeline-skeleton {
  padding: 26px 30px 16px;
}

.timeline-item {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  min-height: 76px;
}

.step-rail {
  display: flex;
  align-items: center;
  flex-direction: column;
}

.step-icon {
  display: grid;
  width: 33px;
  height: 33px;
  flex: 0 0 33px;
  place-items: center;
  border: 1px solid #ded9e6;
  border-radius: 10px;
  color: #aaa4b4;
  background: #faf9fb;
  font-family: Georgia, serif;
  font-size: 10px;
}

.step-rail > i {
  width: 1px;
  flex: 1;
  min-height: 32px;
  background: #e7e3eb;
}

.timeline-item.completed .step-icon {
  border-color: #bfe2d2;
  color: #2d9670;
  background: #edf8f3;
}

.timeline-item.completed .step-rail > i {
  background: #bfe2d2;
}

.timeline-item.running .step-icon {
  border-color: var(--sf-primary);
  color: #fff;
  background: var(--sf-primary);
  box-shadow: 0 7px 17px rgba(83, 64, 200, 0.22);
}

.timeline-item.running .step-icon .el-icon {
  animation: pulse 1.4s ease-in-out infinite;
}

.timeline-item.failed .step-icon {
  border-color: #e7b7b1;
  color: #c8564c;
  background: #fff0ef;
}

.step-copy {
  padding: 3px 0 20px 8px;
}

.step-copy > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-copy h4 {
  margin: 0;
  color: var(--sf-ink-strong);
  font-size: 12px;
}

.timeline-item.waiting .step-copy h4 {
  color: #aaa5b1;
}

.step-copy p {
  margin: 6px 0 0;
  color: var(--sf-ink-muted);
  font-size: 9px;
  line-height: 1.6;
}

.score-chip,
.running-chip {
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 7px;
  font-weight: 800;
}

.score-chip {
  color: #b85e49;
  background: #fff0e8;
}

.running-chip {
  color: var(--sf-primary);
  background: #f0edff;
}

.skeleton-row {
  display: flex;
  min-height: 72px;
  gap: 15px;
}

.skeleton-row > div {
  display: grid;
  align-content: start;
}

.card-footer {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 12px 17px 12px 26px;
  border-top: 1px solid #efecf2;
  background: #faf9fb;
}

.card-footer > div {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--sf-ink-muted);
  font-size: 8px;
}

.card-footer > div .el-icon {
  color: var(--sf-primary);
}

.card-footer .el-button {
  min-width: 155px;
  border-radius: 11px;
}

.leave-hint {
  color: #aaa5b0;
  font-size: 8px;
}

.recovery-note {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 1px dashed #dcd7e5;
  border-radius: 12px;
}

.recovery-note strong {
  flex: 0 0 auto;
  color: var(--sf-primary);
  font-size: 9px;
}

.recovery-note p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 8px;
  line-height: 1.5;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes pulse {
  50% {
    transform: scale(1.12);
  }
}

@keyframes blink {
  50% {
    opacity: 0.45;
  }
}

@media (max-width: 650px) {
  .progress-hero {
    padding: 20px 19px 23px;
  }

  .hero-content {
    grid-template-columns: 57px minmax(0, 1fr);
    gap: 14px;
  }

  .agent-orbit {
    width: 53px;
    height: 53px;
  }

  .progress-number {
    display: none;
  }

  .connection-banner {
    grid-template-columns: 28px 1fr;
  }

  .connection-banner .el-button {
    grid-column: 1 / -1;
  }

  .timeline,
  .timeline-skeleton {
    padding-inline: 18px;
  }

  .card-footer {
    align-items: stretch;
    flex-direction: column;
    padding: 16px 18px;
  }

  .card-footer .el-button {
    width: 100%;
  }
}
</style>
