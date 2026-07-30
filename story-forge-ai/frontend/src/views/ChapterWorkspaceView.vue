<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Brush,
  Check,
  CircleCheck,
  Clock,
  Document,
  EditPen,
  MagicStick,
  Select,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from 'vue-router'

import { getStory } from '@/api/story'
import ChapterPlanPanel from '@/components/ChapterPlanPanel.vue'
import ChapterVersionDrawer from '@/components/ChapterVersionDrawer.vue'
import EmptyState from '@/components/EmptyState.vue'
import MonacoChapterEditor from '@/components/MonacoChapterEditor.vue'
import RewriteProposalPanel from '@/components/RewriteProposalPanel.vue'
import { useChapterStore } from '@/stores/chapter'
import type {
  ChapterListItem,
  RewriteAction,
  StoryProject,
} from '@/types'
import { chapterStatusLabel } from '@/utils/chapter'
import { getErrorMessage } from '@/utils/error'

const route = useRoute()
const router = useRouter()
const chapterStore = useChapterStore()
const story = ref<StoryProject>()
const loadError = ref('')
const editorRef = ref<InstanceType<typeof MonacoChapterEditor>>()
const versionDrawerOpen = ref(false)
const customInstruction = ref('')
const reviewNotes = ref('')

const storyId = computed(() => String(route.params.storyId))
const chapterNo = computed(() => Number(route.params.chapterNo))
const chapter = computed(() => chapterStore.currentChapter)
const catalog = computed(() => chapterStore.catalogs[storyId.value] ?? [])
const directory = computed<ChapterListItem[]>(() => {
  const items = [...catalog.value]
  if (!items.some((item) => item.chapterNo === chapterNo.value)) {
    items.push({
      storyId: storyId.value,
      chapterNo: chapterNo.value,
      title: `第 ${chapterNo.value} 章`,
      status: chapter.value?.status ?? 'EMPTY',
      wordCount: chapterStore.wordCount,
      planApproved: chapter.value?.planApproved ?? false,
      currentVersionId: chapter.value?.currentVersionId,
    })
  }
  return items.sort((left, right) => left.chapterNo - right.chapterNo)
})
const showPlan = computed(
  () =>
    !chapter.value?.currentVersionId &&
    !chapterStore.isStreaming,
)
const showEditor = computed(
  () =>
    Boolean(chapter.value?.currentVersionId) ||
    chapterStore.isStreaming ||
    Boolean(chapterStore.editorContent),
)
const saveLabel = computed(() => {
  if (chapterStore.saveState === 'saving') return '正在保存…'
  if (chapterStore.saveState === 'dirty') return '有未保存修改'
  if (chapterStore.saveState === 'error') return '保存失败'
  if (chapterStore.saveState === 'saved') return '已自动保存'
  return '等待编辑'
})
const selectionLabel = computed(() => {
  const selected = chapterStore.selection
  if (!selected) return '请在正文中选择一段文字'
  return `已选择 ${selected.end - selected.start} 个字符`
})
const canRewrite = computed(
  () =>
    Boolean(chapterStore.selection?.text.trim()) &&
    Boolean(chapter.value?.currentVersionId) &&
    chapter.value?.status !== 'APPROVED' &&
    !chapterStore.rewriting &&
    !chapterStore.isStreaming &&
    !chapterStore.streamPurpose,
)
const review = computed(() => chapter.value?.review)
const streamStatusLabel = computed(() => {
  if (chapterStore.streamState === 'reconnecting') return '连接中断，正在携带游标重连…'
  if (chapterStore.streamState === 'connecting') return '正在连接章节事件流…'
  if (chapterStore.streamState === 'connected') {
    if (chapterStore.streamPurpose === 'plan') return 'AI 正在规划章节场景…'
    if (chapterStore.streamPurpose === 'rewrite') return 'AI 正在生成局部改写建议…'
    if (chapterStore.streamPurpose === 'finalize') return '正在生成摘要并更新故事记忆…'
    return 'AI 正在流式生成正文…'
  }
  return '章节任务已结束'
})

const rewriteActions: Array<{ action: RewriteAction; label: string; hint: string }> = [
  { action: 'ENHANCE_CONFLICT', label: '增强冲突', hint: '增加可见阻力与行动' },
  { action: 'ADD_VISUAL_DETAIL', label: '增加画面感', hint: '补充动作、环境与感官' },
  { action: 'ADJUST_TONE', label: '调整语气', hint: '让表达更贴合当前情绪' },
  { action: 'REDUCE_AI_TONE', label: '减少 AI 感', hint: '压低模板句和总结式表达' },
  { action: 'COMPRESS', label: '压缩内容', hint: '保留信息并提高节奏' },
  { action: 'EXPAND_DETAIL', label: '扩写细节', hint: '增加有效动作与反应' },
  { action: 'FIX_CHARACTER_LOGIC', label: '修复人物逻辑', hint: '对齐动机、事实与关系' },
]

async function load() {
  loadError.value = ''
  try {
    const [storyResult] = await Promise.all([
      getStory(storyId.value),
      chapterStore.loadCatalog(storyId.value),
      chapterStore.loadChapter(storyId.value, chapterNo.value),
    ])
    story.value = storyResult
  } catch (error) {
    loadError.value = getErrorMessage(error, '章节工作台加载失败。')
  }
}

async function run(action: () => Promise<unknown>, success?: string) {
  try {
    await action()
    if (success) ElMessage.success(success)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  }
}

async function captureSelection(range: { start: number; end: number }) {
  try {
    await chapterStore.captureSelection(range.start, range.end)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '无法读取当前选区。'))
  }
}

async function requestRewrite(action: RewriteAction) {
  await run(async () => {
    await chapterStore.requestRewrite(action)
    if (!chapterStore.isStreaming) ElMessage.success('AI 改写建议已生成，请预览后决定')
  })
}

async function requestCustomRewrite() {
  if (!customInstruction.value.trim()) {
    ElMessage.warning('请先填写具体的自定义改写指令')
    return
  }
  await run(async () => {
    await chapterStore.requestRewrite('CUSTOM', customInstruction.value)
    if (!chapterStore.isStreaming) ElMessage.success('自定义改写建议已生成')
  })
}

async function acceptProposal() {
  await run(async () => {
    await chapterStore.acceptProposal()
    ElMessage.success('已接受建议，并保存为新的不可变版本')
    await nextTick()
    editorRef.value?.focus()
  })
}

async function approveChapter() {
  await run(async () => {
    const result = await chapterStore.approveCurrentChapter()
    if ('taskId' in result) ElMessage.success('章节已提交批准，正在更新故事记忆')
    else ElMessage.success('章节已批准，故事记忆已更新')
  })
}

async function requestAiChanges() {
  if (!reviewNotes.value.trim()) {
    ElMessage.warning('请先填写具体修改要求')
    return
  }
  await run(async () => {
    await chapterStore.requestAiChanges(reviewNotes.value)
    reviewNotes.value = ''
    ElMessage.success('修改要求已提交，AI 正在重新生成正文')
  })
}

async function saveBeforeNavigation() {
  if (chapterStore.isDirty) await chapterStore.saveNow()
  if (chapterStore.isDirty) throw new Error('当前正文尚未保存，暂时不能离开本章。')
}

async function goToChapter(number: number) {
  await run(async () => {
    await saveBeforeNavigation()
    await router.push({
      name: 'chapter-workspace',
      params: { storyId: storyId.value, chapterNo: number },
    })
  })
}

async function goToCatalog() {
  await run(async () => {
    await saveBeforeNavigation()
    await router.push({ name: 'chapter-catalog', params: { storyId: storyId.value } })
  })
}

async function guardNavigation() {
  try {
    await saveBeforeNavigation()
    return true
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
    return false
  }
}

watch([storyId, chapterNo], load, { immediate: true })
onBeforeRouteUpdate(guardNavigation)
onBeforeRouteLeave(guardNavigation)

onBeforeUnmount(() => {
  if (chapterStore.isDirty) void chapterStore.saveNow().catch(() => undefined)
  chapterStore.stopStream()
})
</script>

<template>
  <div class="workspace-page">
    <div v-if="chapterStore.loadingChapter && !chapter" class="workspace-loading">
      <div><MagicStick /></div>
      <strong>正在打开章节工作台</strong>
      <span>同步正文版本、章节计划与 AI 任务游标…</span>
    </div>

    <EmptyState
      v-else-if="loadError"
      title="暂时无法打开章节"
      :description="loadError"
      action-label="重新加载"
      @action="load"
    />

    <template v-else-if="chapter">
      <header class="workspace-header">
        <button type="button" class="back-button" @click="goToCatalog">
          <ArrowLeft /> 返回章节目录
        </button>
        <div class="workspace-title">
          <div>
            <span>CHAPTER {{ String(chapter.chapterNo).padStart(2, '0') }}</span>
            <h2>{{ chapter.title || `第 ${chapter.chapterNo} 章` }}</h2>
            <p>{{ story?.title }} · {{ chapterStatusLabel(chapter.status) }}</p>
          </div>
          <div class="header-actions">
            <span class="save-state" :class="chapterStore.saveState">
              <i /> {{ saveLabel }}
            </span>
            <el-button
              :icon="Clock"
              :disabled="!chapter.chapterId"
              @click="versionDrawerOpen = true"
            >
              版本历史
            </el-button>
            <el-button
              v-if="chapter.status === 'APPROVED'"
              type="primary"
              :icon="ArrowRight"
              @click="goToChapter(chapter.chapterNo + 1)"
            >
              继续下一章
            </el-button>
          </div>
        </div>
      </header>

      <ChapterPlanPanel
        v-if="showPlan"
        :plan="chapterStore.currentPlan"
        :approved="chapter.planApproved"
        :planning="chapterStore.planning || chapterStore.streamPurpose === 'plan'"
        :approving="chapterStore.approvingPlan"
        :generating="chapterStore.generating"
        :can-generate="chapter.planApproved"
        @create="(target) => run(() => chapterStore.createPlan(target), '章节计划任务已提交')"
        @approve="run(() => chapterStore.approvePlan(), '场景计划已确认')"
        @generate="run(() => chapterStore.generate(), '正文生成任务已提交')"
      />

      <div v-if="chapterStore.isStreaming || chapterStore.streamError" class="stream-banner" :class="chapterStore.streamState">
        <div class="stream-pulse"><MagicStick /></div>
        <div>
          <strong>{{ streamStatusLabel }}</strong>
          <span>
            {{ chapterStore.streamCurrentNode || '等待下一条事件' }}
            <template v-if="chapterStore.streamError"> · {{ chapterStore.streamError }}</template>
          </span>
        </div>
        <el-progress
          :percentage="chapterStore.streamProgress"
          :show-text="false"
          :stroke-width="5"
        />
      </div>

      <div v-if="showEditor" class="studio-layout">
        <aside class="chapter-directory">
          <header>
            <span>DIRECTORY</span>
            <h3>章节目录</h3>
          </header>
          <nav>
            <button
              v-for="item in directory"
              :key="item.chapterNo"
              type="button"
              :class="{
                active: item.chapterNo === chapter.chapterNo,
                approved: item.status === 'APPROVED',
              }"
              @click="goToChapter(item.chapterNo)"
            >
              <span>{{ String(item.chapterNo).padStart(2, '0') }}</span>
              <span>
                <strong>{{ item.title || `第 ${item.chapterNo} 章` }}</strong>
                <small>{{ chapterStatusLabel(item.status) }}</small>
              </span>
              <CircleCheck v-if="item.status === 'APPROVED'" />
            </button>
          </nav>
          <button class="next-chapter" type="button" @click="goToChapter(Math.max(...directory.map((item) => item.chapterNo)) + 1)">
            <Document /> 规划下一章
          </button>
        </aside>

        <main class="editor-column">
          <div v-if="chapterStore.pendingGeneratedContent" class="edit-conflict">
            <Warning />
            <div>
              <strong>AI 新版本已生成，但你在生成期间编辑过正文</strong>
              <p>为防止覆盖，系统没有自动替换。可采用 AI 草稿，或继续保留当前编辑。</p>
            </div>
            <el-button @click="chapterStore.dismissGeneratedDraft">保留当前编辑</el-button>
            <el-button type="primary" @click="chapterStore.useGeneratedDraft">采用 AI 草稿</el-button>
          </div>

          <section class="editor-card">
            <header class="editor-toolbar">
              <div>
                <span>MANUSCRIPT EDITOR</span>
                <strong>正文编辑区</strong>
              </div>
              <div class="editor-facts">
                <span>{{ chapterStore.wordCount.toLocaleString() }} 字</span>
                <span>V{{ chapter.currentVersionNo || '—' }}</span>
                <span>{{ chapter.plan?.targetLength || chapterStore.currentPlan?.targetLength || 1800 }} 字目标</span>
              </div>
              <el-button
                :icon="Check"
                :disabled="!chapterStore.isDirty"
                :loading="chapterStore.saveState === 'saving'"
                @click="run(() => chapterStore.saveNow(), '正文已保存为新版本')"
              >
                保存
              </el-button>
            </header>

            <MonacoChapterEditor
              ref="editorRef"
              :model-value="chapterStore.editorContent"
              :readonly="chapter.status === 'APPROVED'"
              placeholder="章节正文将在这里流式出现。你可以随时编辑，AI流不会覆盖你的修改。"
              @update:model-value="chapterStore.updateEditorContent"
              @selection-change="captureSelection"
            />

            <footer class="editor-footer">
              <span><i :class="chapterStore.saveState" /> {{ saveLabel }}</span>
              <span v-if="chapterStore.streamDetached">AI 流已与编辑区分离，当前人工修改受到保护</span>
              <span v-else>自动保存间隔约 1.2 秒</span>
            </footer>
          </section>

          <details v-if="chapterStore.streamDetached && chapterStore.streamBuffer" class="stream-preview">
            <summary>查看未覆盖到编辑器的 AI 流式草稿</summary>
            <pre>{{ chapterStore.streamBuffer }}</pre>
          </details>

          <RewriteProposalPanel
            v-if="chapterStore.proposal"
            :proposal="chapterStore.proposal"
            :loading="chapterStore.rewriting"
            @accept="acceptProposal"
            @reject="run(() => chapterStore.rejectProposal(), '已拒绝本次建议')"
            @regenerate="run(() => chapterStore.regenerateProposal(), '正在重新生成建议')"
          />
        </main>

        <aside class="assistant-column">
          <section class="assistant-card score-card">
            <header>
              <div class="assistant-icon"><Brush /></div>
              <div><span>QUALITY REVIEW</span><h3>章节质量</h3></div>
              <strong>{{ review?.total ?? '—' }}</strong>
            </header>
            <div v-if="review" class="review-dimensions">
              <div v-for="dimension in review.dimensions" :key="dimension.key">
                <span>{{ dimension.label }}</span>
                <i><b :style="{ width: `${(dimension.score / dimension.maxScore) * 100}%` }" /></i>
                <strong>{{ dimension.score }}/{{ dimension.maxScore }}</strong>
              </div>
            </div>
            <p v-else>正文完成审核后，这里会显示六维评分与连续性问题。</p>
            <div v-if="chapter.mechanicalErrors.length" class="mechanical-errors">
              <strong><Warning /> 机械校验</strong>
              <span v-for="error in chapter.mechanicalErrors" :key="error">{{ error }}</span>
            </div>
          </section>

          <section class="assistant-card rewrite-card">
            <header>
              <div class="assistant-icon"><MagicStick /></div>
              <div><span>AI WRITING ASSISTANT</span><h3>局部改写</h3></div>
            </header>
            <div class="selection-preview" :class="{ ready: chapterStore.selection }">
              <Select />
              <div>
                <strong>{{ selectionLabel }}</strong>
                <p>{{ chapterStore.selection?.text.slice(0, 90) || '选区将通过哈希与版本号保护。' }}</p>
              </div>
            </div>
            <div class="rewrite-actions">
              <button
                v-for="item in rewriteActions"
                :key="item.action"
                type="button"
                :disabled="!canRewrite"
                @click="requestRewrite(item.action)"
              >
                <strong>{{ item.label }}</strong>
                <span>{{ item.hint }}</span>
                <ArrowRight />
              </button>
            </div>
            <div class="custom-rewrite">
              <el-input
                v-model="customInstruction"
                type="textarea"
                :rows="3"
                maxlength="300"
                placeholder="例如：保留事实，把这段改成更克制的第一人称表达。"
              />
              <el-button
                :icon="EditPen"
                :disabled="!canRewrite || !customInstruction.trim()"
                :loading="chapterStore.rewriting"
                @click="requestCustomRewrite"
              >
                执行自定义指令
              </el-button>
            </div>
          </section>

          <section class="approval-card" :class="{ approved: chapter.status === 'APPROVED' }">
            <template v-if="chapter.status === 'APPROVED'">
              <CircleCheck />
              <div><strong>本章已批准</strong><p>摘要与故事记忆已更新，可继续下一章。</p></div>
              <el-button type="primary" :icon="ArrowRight" @click="goToChapter(chapter.chapterNo + 1)">
                下一章
              </el-button>
            </template>
            <template v-else-if="chapter.status === 'REVIEW_REQUIRED'">
              <div><strong>准备好定稿了吗？</strong><p>批准前会保存人工编辑；批准后将更新长期记忆。</p></div>
              <el-input
                v-model="reviewNotes"
                type="textarea"
                :rows="3"
                maxlength="2000"
                show-word-limit
                placeholder="告诉 AI 需要修改什么，例如：第二场补足女主保全证据的具体动作。"
              />
              <div class="review-actions">
                <el-button
                  size="large"
                  :icon="EditPen"
                  :disabled="!chapter.currentVersionId || chapterStore.isStreaming || !reviewNotes.trim()"
                  :loading="chapterStore.requestingChanges"
                  @click="requestAiChanges"
                >
                  要求 AI 修改
                </el-button>
                <el-button
                  type="primary"
                  size="large"
                  :icon="CircleCheck"
                  :disabled="!chapter.currentVersionId || chapterStore.isStreaming"
                  :loading="chapterStore.approvingChapter"
                  @click="approveChapter"
                >
                  批准本章
                </el-button>
              </div>
            </template>
            <template v-else-if="chapterStore.canRetryGeneration">
              <Warning />
              <div>
                <strong>正文生成未完成</strong>
                <p>已保留当前草稿，可直接重试生成；AI 返回时仍会保护你的人工修改。</p>
              </div>
              <el-button
                type="primary"
                :icon="MagicStick"
                :loading="chapterStore.generating"
                :disabled="chapterStore.isStreaming"
                @click="run(() => chapterStore.generate(), '正文生成任务已重新提交')"
              >
                重试生成
              </el-button>
            </template>
            <template v-else>
              <div><strong>章节处理中</strong><p>等待 AI 生成和质量审核完成后，可批准或填写意见要求修改。</p></div>
            </template>
          </section>
        </aside>
      </div>

      <ChapterVersionDrawer
        v-model="versionDrawerOpen"
        :versions="chapterStore.versions"
        :current-version-id="chapter.currentVersionId"
        :comparison="chapterStore.versionComparison"
        :loading="chapterStore.loadingVersions"
        :comparing="chapterStore.comparingVersions"
        :restoring="chapterStore.restoringVersion"
        :restore-allowed="chapter.status !== 'APPROVED'"
        @load="run(() => chapterStore.loadVersions())"
        @compare="(from, to) => run(() => chapterStore.compareVersions(from, to))"
        @restore="(versionId) => run(() => chapterStore.restoreVersion(versionId), '已恢复历史版本并创建新版本')"
      />
    </template>
  </div>
</template>

<style scoped>
.workspace-page {
  display: grid;
  gap: 16px;
}

.workspace-loading {
  display: grid;
  min-height: 540px;
  place-content: center;
  justify-items: center;
  color: var(--sf-ink-muted);
}

.workspace-loading > div {
  display: grid;
  width: 62px;
  height: 62px;
  margin-bottom: 15px;
  place-items: center;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(145deg, #705bdf, #4533aa);
  box-shadow: 0 15px 30px rgba(72, 53, 170, 0.22);
  animation: breathe 2s ease-in-out infinite;
}

.workspace-loading strong {
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 19px;
}

.workspace-loading span {
  margin-top: 6px;
  font-size: 9px;
}

.workspace-header {
  padding: 17px 21px 0;
  border: 1px solid var(--sf-line);
  border-radius: 19px;
  background: #fff;
}

.back-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: 0;
  color: var(--sf-ink-muted);
  background: transparent;
  cursor: pointer;
  font-size: 8px;
}

.back-button svg {
  width: 11px;
}

.workspace-title {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-top: 15px;
  padding-bottom: 18px;
}

.workspace-title > div:first-child > span,
.chapter-directory > header span,
.editor-toolbar > div:first-child span,
.assistant-card > header div:nth-child(2) span {
  color: var(--sf-accent);
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 1.5px;
}

.workspace-title h2 {
  margin: 4px 0 2px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 25px;
  font-weight: 600;
}

.workspace-title p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 8px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.save-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--sf-ink-muted);
  font-size: 7px;
}

.save-state i,
.editor-footer i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #aaa5b2;
}

.save-state.saved i,
.editor-footer i.saved { background: #309774; }
.save-state.dirty i,
.editor-footer i.dirty { background: #d98a48; }
.save-state.saving i,
.editor-footer i.saving { background: #6e5bd2; animation: pulse 1s infinite; }
.save-state.error i,
.editor-footer i.error { background: #c75650; }

.stream-banner {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) minmax(150px, 0.3fr);
  gap: 12px;
  align-items: center;
  padding: 11px 14px;
  border: 1px solid #d7d0ed;
  border-radius: 13px;
  background: linear-gradient(105deg, #f5f2ff, #fff);
}

.stream-banner.reconnecting {
  border-color: #efcfb7;
  background: #fff8f1;
}

.stream-pulse {
  display: grid;
  width: 31px;
  height: 31px;
  place-items: center;
  border-radius: 9px;
  color: #fff;
  background: var(--sf-primary);
  animation: pulse 1.4s ease-in-out infinite;
}

.stream-banner strong {
  display: block;
  color: #4d426d;
  font-size: 9px;
}

.stream-banner span {
  color: var(--sf-ink-muted);
  font-size: 7px;
}

.studio-layout {
  display: grid;
  grid-template-columns: 184px minmax(450px, 1fr) 280px;
  gap: 13px;
  align-items: start;
}

.chapter-directory,
.assistant-card,
.approval-card,
.editor-card {
  border: 1px solid var(--sf-line);
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(49, 40, 89, 0.045);
}

.chapter-directory {
  position: sticky;
  top: 100px;
  overflow: hidden;
}

.chapter-directory > header {
  padding: 14px;
  border-bottom: 1px solid #efedf1;
  background: #faf9fb;
}

.chapter-directory h3,
.assistant-card h3 {
  margin: 3px 0 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 15px;
}

.chapter-directory nav {
  display: grid;
  max-height: 510px;
  overflow: auto;
  padding: 7px;
}

.chapter-directory nav button {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 8px;
  border: 0;
  border-radius: 9px;
  color: #777180;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.chapter-directory nav button:hover { background: #f7f5fa; }
.chapter-directory nav button.active { color: #5142b2; background: #efecff; }

.chapter-directory nav button > span:first-child {
  display: grid;
  width: 27px;
  height: 27px;
  place-items: center;
  border-radius: 7px;
  color: #fff;
  background: #3b3457;
  font-family: Georgia, serif;
  font-size: 8px;
}

.chapter-directory nav button > span:nth-child(2) {
  min-width: 0;
}

.chapter-directory nav strong,
.chapter-directory nav small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-directory nav strong { font-size: 8px; }
.chapter-directory nav small { margin-top: 2px; color: #aaa5b2; font-size: 6px; }
.chapter-directory nav svg { width: 12px; color: var(--sf-success); }

.next-chapter {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 10px;
  border: 0;
  border-top: 1px solid #efedf1;
  color: #6457ad;
  background: #faf9fb;
  cursor: pointer;
  font-size: 7px;
  font-weight: 750;
}

.next-chapter svg { width: 11px; }

.editor-column,
.assistant-column {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.edit-conflict {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  gap: 9px;
  align-items: center;
  padding: 10px 11px;
  border: 1px solid #efc8b9;
  border-radius: 11px;
  color: #a75a42;
  background: #fff5ef;
}

.edit-conflict > svg { width: 17px; }
.edit-conflict strong { font-size: 8px; }
.edit-conflict p { margin: 2px 0 0; font-size: 7px; }

.editor-card {
  overflow: hidden;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 13px;
  border-bottom: 1px solid #ece9ef;
  background: #faf9fb;
}

.editor-toolbar > div:first-child strong {
  display: block;
  margin-top: 2px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 14px;
}

.editor-facts {
  display: flex;
  gap: 5px;
  margin-left: auto;
}

.editor-facts span {
  padding: 4px 6px;
  border-radius: 6px;
  color: #756c83;
  background: #efedf2;
  font-size: 6px;
  font-weight: 700;
}

.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  padding: 8px 13px;
  border-top: 1px solid #ece9ef;
  color: var(--sf-ink-muted);
  background: #faf9fb;
  font-size: 6px;
}

.editor-footer span { display: flex; align-items: center; gap: 5px; }

.stream-preview {
  border: 1px solid #d9d2ef;
  border-radius: 11px;
  background: #f8f6ff;
}

.stream-preview summary {
  padding: 10px 12px;
  color: #6558a7;
  cursor: pointer;
  font-size: 8px;
  font-weight: 750;
}

.stream-preview pre {
  max-height: 360px;
  overflow: auto;
  margin: 0;
  padding: 14px;
  border-top: 1px solid #e4dff1;
  color: #554d64;
  background: #fff;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 10px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.assistant-column {
  position: sticky;
  top: 100px;
}

.assistant-card { overflow: hidden; }

.assistant-card > header {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px;
  border-bottom: 1px solid #efedf1;
  background: #faf9fb;
}

.assistant-icon {
  display: grid;
  width: 31px;
  height: 31px;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(145deg, #705cdf, #4936b2);
}

.assistant-icon svg { width: 14px; }
.assistant-card > header > strong { margin-left: auto; color: var(--sf-primary); font-family: Georgia, serif; font-size: 20px; }

.score-card > p {
  margin: 0;
  padding: 13px;
  color: var(--sf-ink-muted);
  font-size: 7px;
  line-height: 1.55;
}

.review-dimensions { display: grid; gap: 8px; padding: 12px; }
.review-dimensions > div { display: grid; grid-template-columns: 1fr 75px auto; gap: 6px; align-items: center; }
.review-dimensions span { color: #706a78; font-size: 6px; }
.review-dimensions i { height: 4px; overflow: hidden; border-radius: 3px; background: #ece9f0; }
.review-dimensions b { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #6651cf, #df7c63); }
.review-dimensions strong { color: #514a5c; font-size: 6px; }

.mechanical-errors { display: grid; gap: 4px; margin: 0 10px 10px; padding: 8px; border-radius: 8px; color: #a65a47; background: #fff3ef; }
.mechanical-errors strong { display: flex; align-items: center; gap: 4px; font-size: 7px; }
.mechanical-errors svg { width: 10px; }
.mechanical-errors span { font-size: 6px; }

.selection-preview {
  display: flex;
  gap: 7px;
  margin: 10px;
  padding: 8px;
  border: 1px dashed #d7d1df;
  border-radius: 9px;
  color: #918b9b;
  background: #faf9fb;
}

.selection-preview.ready { border-style: solid; border-color: #cfc5ef; color: #6254ab; background: #f3f0ff; }
.selection-preview > svg { width: 14px; flex: 0 0 14px; }
.selection-preview strong { font-size: 7px; }
.selection-preview p { overflow: hidden; max-height: 35px; margin: 3px 0 0; font-size: 6px; line-height: 1.4; }

.rewrite-actions { display: grid; gap: 5px; padding: 0 10px 10px; }
.rewrite-actions button { display: grid; grid-template-columns: 1fr auto; padding: 8px 9px; border: 1px solid #ebe8ef; border-radius: 8px; color: #5b5364; background: #fff; cursor: pointer; text-align: left; }
.rewrite-actions button:hover:not(:disabled) { border-color: #c9bff0; background: #f8f6ff; }
.rewrite-actions button:disabled { cursor: not-allowed; opacity: 0.45; }
.rewrite-actions strong { font-size: 7px; }
.rewrite-actions span { grid-column: 1; margin-top: 2px; color: #aaa4b0; font-size: 6px; }
.rewrite-actions svg { grid-column: 2; grid-row: 1 / span 2; width: 10px; align-self: center; }

.custom-rewrite { display: grid; gap: 7px; padding: 10px; border-top: 1px solid #efedf1; background: #faf9fb; }

.approval-card { display: grid; gap: 9px; padding: 12px; }
.approval-card strong { color: var(--sf-ink-strong); font-family: 'STSong', 'Songti SC', serif; font-size: 14px; }
.approval-card p { margin: 3px 0 0; color: var(--sf-ink-muted); font-size: 7px; line-height: 1.45; }
.review-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.approval-card.approved { grid-template-columns: auto 1fr; align-items: center; border-color: #c9e3d8; color: #287b5d; background: #f1faf6; }
.approval-card.approved > svg { width: 22px; }
.approval-card.approved > .el-button { grid-column: 1 / -1; }

@keyframes pulse { 50% { opacity: 0.45; transform: scale(0.93); } }
@keyframes breathe { 50% { transform: scale(1.06); } }

@media (max-width: 1280px) {
  .studio-layout { grid-template-columns: 170px minmax(430px, 1fr); }
  .assistant-column { position: static; grid-column: 1 / -1; grid-template-columns: repeat(3, 1fr); }
}

@media (max-width: 900px) {
  .studio-layout { grid-template-columns: 1fr; }
  .chapter-directory, .assistant-column { position: static; }
  .chapter-directory { display: none; }
  .assistant-column { grid-column: auto; grid-template-columns: 1fr; }
  .workspace-title { align-items: stretch; flex-direction: column; }
  .header-actions { flex-wrap: wrap; }
  .stream-banner { grid-template-columns: auto 1fr; }
  .stream-banner > .el-progress { grid-column: 1 / -1; }
  .edit-conflict { grid-template-columns: auto 1fr; }
  .edit-conflict > .el-button { grid-column: 1 / -1; }
}

@media (max-width: 620px) {
  .editor-toolbar { flex-wrap: wrap; }
  .editor-facts { order: 3; width: 100%; margin-left: 0; }
  .editor-footer { align-items: flex-start; flex-direction: column; }
}
</style>
