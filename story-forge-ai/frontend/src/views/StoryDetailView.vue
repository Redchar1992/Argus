<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleCheck,
  EditPen,
  MagicStick,
  Refresh,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getStory, saveTopicSelection } from '@/api/story'
import EmptyState from '@/components/EmptyState.vue'
import TopicCard from '@/components/TopicCard.vue'
import { useStoryStore } from '@/stores/story'
import { useWorkflowStore } from '@/stores/workflow'
import type {
  StoryProject,
  TopicOption,
  TopicSession,
  WorkflowSession,
  WorkflowTask,
} from '@/types'
import { canUseOfflineFallback, getErrorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import {
  getTopicSession,
  saveTopicSession,
} from '@/utils/storage'
import { workflowStatusLabel } from '@/utils/workflow'

const route = useRoute()
const router = useRouter()
const storyStore = useStoryStore()
const workflowStore = useWorkflowStore()
const loading = ref(true)
const loadError = ref('')
const story = ref<StoryProject>()
const cachedSession = ref<TopicSession | null>(null)
const choosing = ref(false)
const saving = ref(false)
const pendingTopicId = ref('')
const latestWorkflow = ref<WorkflowSession | WorkflowTask | null>(null)

const storyId = computed(() => String(route.params.id))
const topics = computed(() => story.value?.topics ?? cachedSession.value?.topics ?? [])
const selectedTopicId = computed(
  () => story.value?.selectedTopicId || cachedSession.value?.selectedTopicId || '',
)
const selectedTopic = computed(() =>
  topics.value.find((topic) => topic.id === selectedTopicId.value),
)
const matchingWorkflow = computed(() => latestWorkflow.value)
const workflowLocked = computed(() => Boolean(latestWorkflow.value))
const lockedTopic = computed(() => {
  const topicId = latestWorkflow.value?.topicId
  if (topicId === undefined) return undefined
  return topics.value.find((topic) => String(topic.id) === String(topicId))
})
const workflowTopicMatchesSelection = computed(() => {
  const topicId = latestWorkflow.value?.topicId
  if (topicId === undefined || !selectedTopic.value) return true
  return String(topicId) === String(selectedTopic.value.id)
})
const workflowActionLabel = computed(() => {
  const session = matchingWorkflow.value
  if (!session) return '启动 AI 创作工作流'
  if (session.status === 'FAILED') return '重试 AI 创作工作流'
  if (session.status === 'REVIEW_REQUIRED') return '继续审核大纲'
  if (session.status === 'SUCCESS') return '查看正式创作方案'
  return '查看生成进度'
})
const canOpenChapterStudio = computed(
  () => matchingWorkflow.value?.status === 'SUCCESS',
)

function buildCachedStory(session: TopicSession): StoryProject {
  return {
    id: session.storyId,
    title: session.input.title || '已保存的故事项目',
    genre: session.input.genre,
    audience: session.input.audience,
    keywords: session.input.keywords,
    status: 'generated',
    createdTime: session.generatedAt,
    topics: session.topics,
    selectedTopicId: session.selectedTopicId,
  }
}

function mergeWithCache(remote: StoryProject, cache: TopicSession | null): StoryProject {
  if (!cache) return { ...remote, id: remote.id || storyId.value }

  return {
    ...remote,
    id: remote.id || cache.storyId,
    title:
      remote.title === '未命名故事'
        ? cache.input.title || remote.title
        : remote.title,
    genre: remote.genre === '未分类' ? cache.input.genre : remote.genre,
    audience: remote.audience || cache.input.audience,
    keywords: remote.keywords || cache.input.keywords,
    topics: remote.topics?.length ? remote.topics : cache.topics,
    selectedTopicId: remote.selectedTopicId || cache.selectedTopicId,
  }
}

async function loadStory() {
  loading.value = true
  loadError.value = ''
  cachedSession.value = getTopicSession(storyId.value)

  try {
    const remote = await getStory(storyId.value)
    story.value = mergeWithCache(remote, cachedSession.value)
    const cachedWorkflow = workflowStore.latestStorySession(storyId.value)
    try {
      const latestTask = await workflowStore.fetchLatestStoryTask(storyId.value)
      latestWorkflow.value = latestTask
    } catch (error) {
      if (!canUseOfflineFallback(error)) throw error
      latestWorkflow.value = cachedWorkflow
      ElMessage.warning(
        cachedWorkflow
          ? '暂时无法同步工作流状态，已显示本机最近记录'
          : '暂时无法同步工作流状态，请稍后重试',
      )
    }
  } catch (error) {
    if (cachedSession.value && canUseOfflineFallback(error)) {
      story.value = buildCachedStory(cachedSession.value)
      latestWorkflow.value = workflowStore.latestStorySession(storyId.value)
      ElMessage.warning('当前为离线视图；服务恢复后可同步最新结果')
    } else {
      loadError.value = getErrorMessage(error, '故事详情加载失败。')
    }
  } finally {
    loading.value = false
  }
}

async function startOrResumeWorkflow() {
  if (!story.value) return

  const existing = matchingWorkflow.value
  if (existing && existing.status !== 'FAILED') {
    await router.push({
      name:
        existing.status === 'REVIEW_REQUIRED' || existing.status === 'SUCCESS'
          ? 'workflow-review'
          : 'workflow-progress',
      params: {
        storyId: story.value.id,
        taskId: existing.taskId,
      },
    })
    return
  }

  const workflowTopicId = existing?.topicId ?? selectedTopic.value?.id
  if (workflowTopicId === undefined) {
    ElMessage.warning('请先选择并保存一个主方案')
    return
  }

  try {
    const task = await workflowStore.start({
      storyId: story.value.id,
      topicId: workflowTopicId,
    })
    latestWorkflow.value = workflowStore.latestStorySession(story.value.id)
    await router.push({
      name: 'workflow-progress',
      params: { storyId: story.value.id, taskId: task.taskId },
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '工作流启动失败，请稍后重试。'))
  }
}

function beginChoosing() {
  if (workflowLocked.value) {
    ElMessage.info('V1 工作流启动后会锁定原选题，当前故事不能更换主方案')
    return
  }
  pendingTopicId.value = selectedTopicId.value
  choosing.value = true
}

function chooseTopic(topic: TopicOption) {
  if (!choosing.value || workflowLocked.value) return
  pendingTopicId.value = topic.id
}

async function saveSelection() {
  if (!story.value || !pendingTopicId.value) return
  if (workflowLocked.value) {
    choosing.value = false
    ElMessage.info('V1 工作流已锁定原选题，不能保存新的主方案')
    return
  }

  saving.value = true
  try {
    const refreshed = await saveTopicSelection(story.value.id, pendingTopicId.value)
    const merged = mergeWithCache(refreshed, cachedSession.value)
    const refreshedTopics = refreshed.topics?.length ? refreshed.topics : topics.value
    const refreshedSelectedId = refreshed.selectedTopicId || pendingTopicId.value
    merged.topics = refreshedTopics
    merged.selectedTopicId = refreshedSelectedId

    saveTopicSession({
      storyId: merged.id,
      topics: refreshedTopics,
      selectedTopicId: refreshedSelectedId,
      generatedAt: merged.updatedTime || new Date().toISOString(),
      input: {
        title: merged.title,
        genre: merged.genre,
        audience: merged.audience || '',
        keywords: merged.keywords || '',
      },
    })
    story.value = merged
    cachedSession.value = getTopicSession(merged.id)
    storyStore.updateStory(merged.id, merged)
    choosing.value = false
    ElMessage.success('主方案已更新并同步到服务端')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '主方案保存失败，请稍后重试。'))
  } finally {
    saving.value = false
  }
}

onMounted(loadStory)
</script>

<template>
  <div class="detail-page">
    <div v-if="loading" class="detail-loading">
      <div class="loading-orbit"><MagicStick /></div>
      <strong>正在取回故事方案</strong>
      <span>连接你的灵感档案…</span>
    </div>

    <EmptyState
      v-else-if="loadError"
      title="暂时无法打开这个故事"
      :description="loadError"
      action-label="重新加载"
      @action="loadStory"
    />

    <template v-else-if="story">
      <header class="detail-hero">
        <button type="button" class="back-button" @click="router.push({ name: 'dashboard' })">
          <el-icon><ArrowLeft /></el-icon>
          返回我的作品
        </button>
        <div class="hero-grid">
          <div class="hero-copy">
            <div class="hero-meta">
              <span>{{ story.genre }}</span>
              <i />
              <span>{{ story.audience || '目标受众未设置' }}</span>
            </div>
            <h2>{{ story.title }}</h2>
            <p>{{ story.keywords || '这个故事的创作方向等待继续补充。' }}</p>
          </div>
          <div class="hero-facts">
            <div>
              <strong>{{ topics.length }}</strong>
              <span>候选选题</span>
            </div>
            <div>
              <strong>{{ selectedTopic?.score || '—' }}</strong>
              <span>主方案评分</span>
            </div>
            <div>
              <strong>{{ formatDate(story.createdTime) }}</strong>
              <span>创建日期</span>
            </div>
          </div>
        </div>
      </header>

      <section v-if="selectedTopic" class="selected-overview">
        <div class="selected-mark">
          <el-icon><CircleCheck /></el-icon>
          <span>SELECTED DIRECTION</span>
        </div>
        <div>
          <span>当前主方案</span>
          <h3>{{ selectedTopic.title }}</h3>
          <p>{{ selectedTopic.hook }}</p>
        </div>
        <el-button v-if="!workflowLocked" :icon="EditPen" @click="beginChoosing">
          更换主方案
        </el-button>
        <span
          v-else
          class="topic-lock"
          :class="{ warning: !workflowTopicMatchesSelection }"
        >
          {{
            workflowTopicMatchesSelection
              ? 'V1 工作流已锁定该选题'
              : `V1 工作流已锁定原选题：${lockedTopic?.title || `#${matchingWorkflow?.topicId}`}`
          }}
        </span>
      </section>

      <section v-if="selectedTopic || matchingWorkflow" class="workflow-entry">
        <div class="workflow-copy">
          <span class="workflow-kicker">WEEK 02 · AGENT WORKFLOW</span>
          <h3>把选题推进成可审核的完整方案</h3>
          <p>
            AI 将依次生成人物卡、恰好 20 个剧情节点和五维商业评分；低于 80
            分时最多自动修改两轮。
          </p>
          <div v-if="matchingWorkflow" class="workflow-state">
            <i :class="matchingWorkflow.status.toLowerCase()" />
            已有任务 #{{ matchingWorkflow.taskId }} ·
            {{ workflowStatusLabel(matchingWorkflow.status) }}
          </div>
        </div>
        <div class="workflow-path" aria-label="AI 工作流步骤">
          <span>人物</span><i /><span>大纲</span><i /><span>评分</span><i /><span>审核</span>
        </div>
        <el-button
          v-if="canOpenChapterStudio"
          class="chapter-studio-button"
          type="success"
          size="large"
          @click="router.push({ name: 'chapter-catalog', params: { storyId } })"
        >
          进入章节创作
          <el-icon><ArrowRight /></el-icon>
        </el-button>
        <el-button
          type="primary"
          size="large"
          :loading="workflowStore.starting"
          @click="startOrResumeWorkflow"
        >
          {{ workflowActionLabel }}
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </section>

      <section v-if="topics.length" class="all-topics">
        <header class="section-header">
          <div>
            <span class="section-kicker">SAVED TOPICS</span>
            <h2>{{ choosing ? '选择新的主方案' : '全部故事方案' }}</h2>
            <p>
              {{
                choosing
                  ? '比较钩子、梗概与四维评分，然后保存你的选择。'
                  : '本次 AI 生成的所有结构化结果均已保留。'
              }}
            </p>
          </div>
          <div class="section-actions">
            <template v-if="choosing">
              <el-button @click="choosing = false">取消</el-button>
              <el-button
                type="primary"
                :icon="Check"
                :disabled="!pendingTopicId"
                :loading="saving"
                @click="saveSelection"
              >
                保存主方案
              </el-button>
            </template>
            <el-button v-else :icon="Refresh" @click="loadStory">同步最新结果</el-button>
          </div>
        </header>

        <div class="topic-list">
          <TopicCard
            v-for="(topic, index) in topics"
            :key="topic.id"
            :topic="topic"
            :index="index"
            :selectable="choosing"
            :selected="
              choosing ? pendingTopicId === topic.id : selectedTopicId === topic.id
            "
            @select="chooseTopic"
          />
        </div>
      </section>

      <EmptyState
        v-else
        compact
        title="这个故事还没有 AI 方案"
        description="开始一次新的 AI 策划，生成 10 个可比较的结构化故事方向。"
        action-label="开始 AI 策划"
        @action="router.push({ name: 'story-create' })"
      />
    </template>
  </div>
</template>

<style scoped>
.detail-page {
  display: grid;
  gap: 26px;
}

.detail-loading {
  display: grid;
  min-height: 480px;
  place-items: center;
  align-content: center;
  color: var(--sf-ink-muted);
}

.loading-orbit {
  display: grid;
  width: 67px;
  height: 67px;
  margin-bottom: 18px;
  place-items: center;
  border: 1px solid #dcd5ef;
  border-radius: 50%;
  color: var(--sf-primary);
  background: #fff;
  box-shadow: 0 13px 30px rgba(74, 55, 170, 0.1);
  animation: breathe 1.6s ease-in-out infinite;
}

.loading-orbit svg {
  width: 24px;
}

.detail-loading strong {
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 17px;
}

.detail-loading span {
  margin-top: 6px;
  font-size: 9px;
}

.detail-hero {
  position: relative;
  overflow: hidden;
  padding: 22px 30px 30px;
  border-radius: 23px;
  color: #fff;
  background:
    radial-gradient(circle at 85% 20%, rgba(148, 124, 255, 0.26), transparent 27%),
    linear-gradient(125deg, #211c45, #33285e);
  box-shadow: 0 17px 43px rgba(39, 29, 83, 0.13);
}

.detail-hero::after {
  position: absolute;
  right: -85px;
  bottom: -160px;
  width: 340px;
  height: 340px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 50%;
  box-shadow: 0 0 0 42px rgba(255, 255, 255, 0.022);
  content: '';
}

.back-button {
  display: inline-flex;
  position: relative;
  z-index: 1;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  border: 0;
  color: #aaa4c0;
  background: transparent;
  cursor: pointer;
  font-size: 9px;
}

.back-button:hover {
  color: #fff;
}

.hero-grid {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 50px;
  align-items: end;
  margin-top: 25px;
}

.hero-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #c6bfee;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 1.2px;
}

.hero-meta i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #efaf75;
}

.hero-copy h2 {
  margin: 8px 0 9px;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(27px, 3vw, 38px);
  font-weight: 550;
}

.hero-copy p {
  margin: 0;
  color: #aaa4bf;
  font-size: 11px;
}

.hero-facts {
  display: flex;
  gap: 9px;
}

.hero-facts > div {
  display: grid;
  min-width: 82px;
  min-height: 67px;
  place-content: center;
  padding: 9px 13px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
}

.hero-facts strong {
  overflow: hidden;
  max-width: 110px;
  color: #fff;
  font-family: Georgia, 'STSong', serif;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-facts span {
  margin-top: 3px;
  color: #9089a8;
  font-size: 8px;
}

.selected-overview {
  display: grid;
  grid-template-columns: 50px minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  padding: 17px 20px;
  border: 1px solid #ddd5f3;
  border-radius: 16px;
  background: linear-gradient(105deg, #f7f4ff, #fff);
}

.selected-mark {
  display: grid;
  gap: 4px;
  justify-items: center;
  color: var(--sf-primary);
}

.selected-mark .el-icon {
  font-size: 21px;
}

.selected-mark span {
  font-size: 5px;
  font-weight: 800;
  letter-spacing: 0.5px;
}

.selected-overview > div:nth-child(2) > span {
  color: var(--sf-ink-muted);
  font-size: 8px;
  font-weight: 750;
  letter-spacing: 1px;
}

.selected-overview h3 {
  margin: 4px 0 3px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 17px;
}

.selected-overview p {
  margin: 0;
  color: var(--sf-accent);
  font-size: 9px;
}

.selected-overview .el-button {
  border-radius: 9px;
}

.topic-lock {
  max-width: 230px;
  padding: 7px 10px;
  border: 1px solid #d9d2ed;
  border-radius: 9px;
  color: #6659ae;
  background: #f3f0ff;
  font-size: 8px;
  font-weight: 750;
  line-height: 1.4;
  text-align: center;
}

.topic-lock.warning {
  border-color: #efcfb9;
  color: #a65e3d;
  background: #fff6ef;
}

.workflow-entry {
  position: relative;
  display: grid;
  overflow: hidden;
  grid-template-columns: minmax(0, 1.2fr) minmax(250px, 0.8fr) auto;
  gap: 24px;
  align-items: center;
  padding: 23px 24px;
  border: 1px solid #d8d0ef;
  border-radius: 18px;
  background:
    radial-gradient(circle at 88% 20%, rgba(111, 89, 221, 0.08), transparent 25%),
    linear-gradient(112deg, #f7f4ff, #fff);
}

.workflow-entry::after {
  position: absolute;
  right: -45px;
  bottom: -90px;
  width: 160px;
  height: 160px;
  border: 1px solid rgba(92, 73, 213, 0.07);
  border-radius: 50%;
  box-shadow: 0 0 0 28px rgba(92, 73, 213, 0.025);
  content: '';
  pointer-events: none;
}

.workflow-copy {
  position: relative;
  z-index: 1;
}

.workflow-kicker {
  color: var(--sf-primary);
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 1.8px;
}

.workflow-copy h3 {
  margin: 6px 0 5px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.workflow-copy > p {
  max-width: 580px;
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 9px;
  line-height: 1.65;
}

.workflow-state {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 9px;
  color: #655d74;
  font-size: 8px;
  font-weight: 700;
}

.workflow-state i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7865df;
}

.workflow-state i.review_required {
  background: #d87958;
}

.workflow-state i.success {
  background: #309774;
}

.workflow-state i.failed {
  background: #c45450;
}

.workflow-path {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
}

.workflow-path span {
  display: grid;
  width: 37px;
  height: 37px;
  flex: 0 0 37px;
  place-items: center;
  border: 1px solid #d8d2e5;
  border-radius: 11px;
  color: #6e6778;
  background: #fff;
  font-size: 8px;
  font-weight: 700;
}

.workflow-path i {
  width: 18px;
  height: 1px;
  background: linear-gradient(90deg, #c8c0df, #e5e0ec);
}

.workflow-entry > .el-button {
  position: relative;
  z-index: 1;
  min-width: 165px;
  border-radius: 11px;
  box-shadow: 0 10px 23px rgba(79, 59, 185, 0.17);
}

.all-topics {
  display: grid;
  gap: 18px;
}

.section-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}

.section-kicker {
  color: var(--sf-accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 2px;
}

.section-header h2 {
  margin: 5px 0 4px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 23px;
}

.section-header p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 10px;
}

.section-actions {
  display: flex;
  gap: 8px;
}

.section-actions .el-button {
  border-radius: 9px;
}

.topic-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 15px;
}

@keyframes breathe {
  50% {
    transform: scale(1.08);
    box-shadow: 0 17px 38px rgba(74, 55, 170, 0.16);
  }
}

@media (max-width: 1150px) {
  .workflow-entry {
    grid-template-columns: 1fr auto;
  }

  .workflow-path {
    display: none;
  }

  .topic-list {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .hero-grid {
    grid-template-columns: 1fr;
    gap: 25px;
  }

  .hero-facts {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
  }

  .hero-facts > div {
    min-width: 0;
  }

  .selected-overview {
    grid-template-columns: 42px 1fr;
  }

  .selected-overview > .el-button,
  .selected-overview > .topic-lock {
    grid-column: 1 / -1;
  }

  .selected-overview > .topic-lock {
    max-width: none;
  }

  .workflow-entry {
    grid-template-columns: 1fr;
  }

  .workflow-entry > .el-button {
    width: 100%;
  }

  .section-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .section-actions,
  .section-actions .el-button {
    width: 100%;
  }

  .section-actions .el-button {
    flex: 1;
  }
}
</style>
