<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleCheck,
  CollectionTag,
  MagicStick,
  Refresh,
  UserFilled,
} from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import TopicCard from '@/components/TopicCard.vue'
import { generateTopics, saveTopicSelection } from '@/api/story'
import { useStoryStore } from '@/stores/story'
import type { CreateStoryInput, EntityId, TopicOption } from '@/types'
import { getErrorMessage } from '@/utils/error'
import { saveTopicSession } from '@/utils/storage'

interface StoryForm extends CreateStoryInput {
  audience: string
  keywords: string
}

const genreOptions = [
  '都市情感',
  '家庭伦理',
  '职场逆袭',
  '悬疑推理',
  '古装传奇',
  '青春校园',
  '奇幻脑洞',
  '轻喜剧',
]
const audienceOptions = ['女性', '男性', '年轻女性', '年轻男性', '大众']
const contentModeOptions = [
  { value: 'SHORT_STORY' as const, label: '短故事', description: '3–10 章，节奏紧凑，适合快速验证' },
  { value: 'NOVEL' as const, label: '小说', description: '20–200 章，支持分章持续创作' },
]
const viewpointOptions = [
  { value: 'THIRD_LIMITED', label: '第三人称限知' },
  { value: 'FIRST_PERSON', label: '第一人称' },
  { value: 'THIRD_OMNISCIENT', label: '第三人称全知' },
]
const keywordSuggestions = ['复仇', '身份反转', '先婚后爱', '逆袭', '救赎', '爽感', '悬疑', '治愈']
const loadingMessages = [
  'Topic Agent 正在拆解题材与受众…',
  '正在设计高冲突开场与身份反转…',
  'Score Agent 正在评估商业潜力…',
  '正在整理 10 个结构化故事方案…',
]

const router = useRouter()
const storyStore = useStoryStore()
const formRef = ref<FormInstance>()
const stage = ref<'configure' | 'results'>('configure')
const generating = ref(false)
const saving = ref(false)
const loadingMessageIndex = ref(0)
const storyId = ref<EntityId | null>(null)
const taskId = ref<EntityId>()
const topics = ref<TopicOption[]>([])
const selectedTopicId = ref('')
let loadingTimer: ReturnType<typeof setInterval> | undefined

const form = reactive<StoryForm>({
  title: '',
  genre: '',
  audience: '',
  keywords: '',
  contentMode: 'SHORT_STORY',
  targetChapterCount: 10,
  targetTotalWords: 30000,
  chapterTargetWords: 1800,
  viewpoint: 'THIRD_LIMITED',
})

watch(
  () => form.contentMode,
  (mode) => {
    if (mode === 'NOVEL') {
      if (!form.targetChapterCount || form.targetChapterCount < 20) form.targetChapterCount = 30
      if (!form.targetTotalWords || form.targetTotalWords < 100_000) form.targetTotalWords = 300_000
      if (!form.chapterTargetWords || form.chapterTargetWords < 800) form.chapterTargetWords = 2500
    } else {
      if (!form.targetChapterCount || form.targetChapterCount > 10) form.targetChapterCount = 10
      if (!form.targetTotalWords || form.targetTotalWords > 80_000) form.targetTotalWords = 30_000
      if (!form.chapterTargetWords || form.chapterTargetWords > 5000) form.chapterTargetWords = 1800
    }
  },
)

function validateKeywords(
  _rule: unknown,
  value: string,
  callback: (error?: Error) => void,
) {
  const keywords = value
    .split(/[,，、;；\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)

  if (keywords.length > 10) {
    callback(new Error('关键词最多 10 个'))
    return
  }
  if (keywords.some((keyword) => keyword.length > 30)) {
    callback(new Error('每个关键词最多 30 个字符'))
    return
  }
  callback()
}

const rules: FormRules<StoryForm> = {
  title: [
    { required: true, message: '请为故事项目起一个名字', trigger: 'blur' },
    { min: 2, max: 80, message: '项目名称需为 2–80 个字符', trigger: 'blur' },
  ],
  genre: [
    { required: true, message: '请选择故事题材', trigger: 'change' },
    { min: 2, max: 50, message: '题材需为 2–50 个字符', trigger: 'change' },
  ],
  audience: [{ required: true, message: '请选择目标受众', trigger: 'change' }],
  contentMode: [{ required: true, message: '请选择内容模式', trigger: 'change' }],
  keywords: [
    { required: true, message: '请输入情绪或剧情方向', trigger: 'blur' },
    { max: 120, message: '创作方向请控制在 120 个字符内', trigger: 'blur' },
    { validator: validateKeywords, trigger: 'blur' },
  ],
}

const selectedTopic = computed(() =>
  topics.value.find((topic) => topic.id === selectedTopicId.value),
)

function appendKeyword(keyword: string) {
  const existing = form.keywords
    .split(/[，,、\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  if (existing.includes(keyword)) return
  form.keywords = [...existing, keyword].join('、')
  formRef.value?.clearValidate('keywords')
}

function startLoadingMessages() {
  loadingMessageIndex.value = 0
  loadingTimer = setInterval(() => {
    loadingMessageIndex.value =
      (loadingMessageIndex.value + 1) % loadingMessages.length
  }, 2300)
}

function stopLoadingMessages() {
  if (loadingTimer) clearInterval(loadingTimer)
  loadingTimer = undefined
}

async function createProjectIfNeeded() {
  if (storyId.value !== null && String(storyId.value)) return storyId.value

  const story = await storyStore.createStory({
    title: form.title.trim(),
    genre: form.genre,
    audience: form.audience,
    keywords: form.keywords.trim(),
    contentMode: form.contentMode,
    targetChapterCount: form.targetChapterCount,
    targetTotalWords: form.targetTotalWords,
    chapterTargetWords: form.chapterTargetWords,
    viewpoint: form.viewpoint,
  })
  if (story.id === '' || story.id === null || story.id === undefined) {
    throw new Error('故事已提交，但服务未返回故事 ID。')
  }
  storyId.value = story.id
  return story.id
}

async function handleGenerate() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  generating.value = true
  startLoadingMessages()
  try {
    const id = await createProjectIfNeeded()
    const result = await generateTopics({
      storyId: id,
      genre: form.genre,
      audience: form.audience,
      keywords: form.keywords.trim(),
      contentMode: form.contentMode,
    })
    if (!result.topics.length) {
      throw new Error('AI 已返回结果，但未找到可展示的故事方案。')
    }

    topics.value = result.topics
    taskId.value = result.taskId
    selectedTopicId.value = ''
    saveTopicSession({
      storyId: id,
      taskId: result.taskId,
      topics: result.topics,
      generatedAt: new Date().toISOString(),
      input: {
        title: form.title.trim(),
        genre: form.genre,
        audience: form.audience,
        keywords: form.keywords.trim(),
        contentMode: form.contentMode,
      },
    })
    storyStore.updateStory(id, {
      status: 'generated',
      topics: result.topics,
      audience: form.audience,
      keywords: form.keywords.trim(),
      contentMode: form.contentMode,
      targetChapterCount: form.targetChapterCount,
      targetTotalWords: form.targetTotalWords,
      chapterTargetWords: form.chapterTargetWords,
      viewpoint: form.viewpoint,
    })
    stage.value = 'results'
    ElMessage.success(`已生成 ${result.topics.length} 个故事方案`)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, 'AI 生成失败，请稍后重试。'))
  } finally {
    stopLoadingMessages()
    generating.value = false
  }
}

function chooseTopic(topic: TopicOption) {
  selectedTopicId.value = topic.id
}

async function saveSelection() {
  if (storyId.value === null || !selectedTopic.value) {
    ElMessage.warning('请先选择一个最有潜力的故事方案')
    return
  }

  saving.value = true
  try {
    const refreshed = await saveTopicSelection(storyId.value, selectedTopic.value.id)
    const serverTopics = refreshed.topics?.length ? refreshed.topics : topics.value
    const serverSelectedId = refreshed.selectedTopicId || selectedTopic.value.id

    topics.value = serverTopics
    selectedTopicId.value = serverSelectedId
    saveTopicSession({
      storyId: storyId.value,
      taskId: taskId.value,
      topics: serverTopics,
      selectedTopicId: serverSelectedId,
      generatedAt: refreshed.updatedTime || new Date().toISOString(),
      input: {
        title: refreshed.title || form.title.trim(),
        genre: refreshed.genre || form.genre,
        audience: refreshed.audience || form.audience,
        keywords: refreshed.keywords || form.keywords.trim(),
        contentMode: refreshed.contentMode || form.contentMode,
      },
    })
    storyStore.updateStory(storyId.value, {
      ...refreshed,
      topics: serverTopics,
      selectedTopicId: serverSelectedId,
      status: 'generated',
    })
    ElMessage.success('故事方案已保存，可以随时回来查看')
    await router.push({
      name: 'story-detail',
      params: { id: storyId.value },
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '方案保存失败，请稍后重试。'))
  } finally {
    saving.value = false
  }
}

function backToConfigure() {
  stage.value = 'configure'
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

onBeforeUnmount(stopLoadingMessages)
</script>

<template>
  <div class="create-page">
    <Transition name="fade" mode="out-in">
      <section v-if="stage === 'configure'" key="configure" class="configure-layout">
        <div class="form-column">
          <header class="form-intro">
            <span class="section-kicker">CREATE WITH DIRECTION</span>
            <h2>给 AI 一个清晰的创作方向</h2>
            <p>先定义题材、受众和情绪价值。其他复杂工作，交给策划 Agent。</p>
          </header>

          <el-form
            ref="formRef"
            class="story-form"
            :model="form"
            :rules="rules"
            label-position="top"
            @submit.prevent="handleGenerate"
          >
            <section class="form-section">
              <div class="section-number">01</div>
              <div class="section-content">
                <div class="field-heading">
                  <div>
                    <h3>故事项目</h3>
                    <p>这是你之后在「我的作品」中看到的项目名称。</p>
                  </div>
                  <el-icon><CollectionTag /></el-icon>
                </div>
                <el-form-item label="项目名称" prop="title">
                  <el-input
                    v-model="form.title"
                    maxlength="80"
                    placeholder="例如：她离婚后继承了百亿集团"
                    size="large"
                  />
                </el-form-item>
                <el-form-item label="题材类型" prop="genre">
                  <el-select
                    v-model="form.genre"
                    filterable
                    allow-create
                    default-first-option
                    placeholder="选择或输入题材"
                    size="large"
                  >
                    <el-option
                      v-for="genre in genreOptions"
                      :key="genre"
                      :label="genre"
                      :value="genre"
                    />
                  </el-select>
                </el-form-item>
              </div>
            </section>

            <section class="form-section">
              <div class="section-number">02</div>
              <div class="section-content">
                <div class="field-heading">
                  <div>
                    <h3>内容模式</h3>
                    <p>先选择短故事或小说，AI 会使用不同的结构和写作节奏。</p>
                  </div>
                  <el-icon><MagicStick /></el-icon>
                </div>
                <el-form-item prop="contentMode">
                  <div class="audience-options mode-options">
                    <button
                      v-for="option in contentModeOptions"
                      :key="option.value"
                      type="button"
                      :class="{ active: form.contentMode === option.value }"
                      @click="form.contentMode = option.value"
                    >
                      <strong>{{ option.label }}</strong>
                      <small>{{ option.description }}</small>
                      <el-icon v-if="form.contentMode === option.value"><Check /></el-icon>
                    </button>
                  </div>
                </el-form-item>
                <div class="profile-grid">
                  <el-form-item label="目标章节数">
                    <el-input-number
                      v-model="form.targetChapterCount"
                      :min="form.contentMode === 'NOVEL' ? 20 : 1"
                      :max="form.contentMode === 'NOVEL' ? 200 : 10"
                      controls-position="right"
                      size="large"
                    />
                  </el-form-item>
                  <el-form-item label="单章目标字数">
                    <el-input-number
                      v-model="form.chapterTargetWords"
                      :min="800"
                      :max="8000"
                      :step="200"
                      controls-position="right"
                      size="large"
                    />
                  </el-form-item>
                  <el-form-item label="叙事视角">
                    <el-select v-model="form.viewpoint" size="large">
                      <el-option
                        v-for="option in viewpointOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                      />
                    </el-select>
                  </el-form-item>
                </div>
              </div>
            </section>

            <section class="form-section">
              <div class="section-number">03</div>
              <div class="section-content">
                <div class="field-heading">
                  <div>
                    <h3>目标受众</h3>
                    <p>不同受众期待不同的冲突节奏与情绪回报。</p>
                  </div>
                  <el-icon><UserFilled /></el-icon>
                </div>
                <el-form-item prop="audience">
                  <div class="audience-options">
                    <button
                      v-for="audience in audienceOptions"
                      :key="audience"
                      type="button"
                      :class="{ active: form.audience === audience }"
                      @click="form.audience = audience"
                    >
                      <span>{{ audience }}</span>
                      <el-icon v-if="form.audience === audience"><Check /></el-icon>
                    </button>
                  </div>
                </el-form-item>
              </div>
            </section>

            <section class="form-section">
              <div class="section-number">04</div>
              <div class="section-content">
                <div class="field-heading">
                  <div>
                    <h3>创作方向</h3>
                    <p>写下想要的情绪、冲突或关键词，用「、」分隔。</p>
                  </div>
                  <el-icon><MagicStick /></el-icon>
                </div>
                <el-form-item prop="keywords">
                  <el-input
                    v-model="form.keywords"
                    :rows="3"
                    maxlength="120"
                    placeholder="例如：复仇、身份反转、女性成长"
                    resize="none"
                    show-word-limit
                    type="textarea"
                  />
                </el-form-item>
                <div class="suggestions">
                  <span>灵感标签</span>
                  <button
                    v-for="keyword in keywordSuggestions"
                    :key="keyword"
                    type="button"
                    @click="appendKeyword(keyword)"
                  >
                    + {{ keyword }}
                  </button>
                </div>
              </div>
            </section>

            <div class="generate-action">
              <div>
                <strong>一次生成 10 个结构化方案</strong>
                <span>包含开场钩子、故事梗概与商业潜力评分</span>
              </div>
              <el-button
                type="primary"
                size="large"
                native-type="submit"
                :loading="generating"
                :icon="MagicStick"
              >
                {{ generating ? 'AI 正在策划' : storyId ? '重新生成方案' : '生成故事选题' }}
              </el-button>
            </div>
          </el-form>
        </div>

        <aside class="guide-column">
          <div class="guide-card">
            <span class="guide-kicker">HOW IT WORKS</span>
            <h3>本次 AI 策划会做什么？</h3>
            <div class="agent-step active">
              <span>1</span>
              <div>
                <strong>Topic Agent</strong>
                <p>结合题材与受众，设计冲突开场和身份反转。</p>
              </div>
            </div>
            <div class="agent-connector" />
            <div class="agent-step">
              <span>2</span>
              <div>
                <strong>Score Agent</strong>
                <p>从冲突、反转、情绪价值和短剧适配度进行评分。</p>
              </div>
            </div>
            <div class="agent-connector" />
            <div class="agent-step">
              <span>3</span>
              <div>
                <strong>结构化保存</strong>
                <p>每次生成都绑定到故事项目，随时回来查看。</p>
              </div>
            </div>
          </div>

          <div class="principle-card">
            <span>创作提示</span>
            <blockquote>方向越具体，AI 给出的方案越能贴近你想要的情绪价值。</blockquote>
          </div>
        </aside>
      </section>

      <section v-else key="results" class="results-layout">
        <header class="results-header">
          <button type="button" class="back-button" @click="backToConfigure">
            <el-icon><ArrowLeft /></el-icon>
            调整生成条件
          </button>
          <div class="result-title">
            <span class="section-kicker">AI TOPIC RESULTS</span>
            <h2>{{ form.title }}</h2>
            <p>
              已为「{{ form.genre }} · {{ form.audience }}」生成
              <strong>{{ topics.length }}</strong> 个故事方向。选择一个最值得继续的方案。
            </p>
          </div>
          <div class="result-actions">
            <el-button :icon="Refresh" @click="backToConfigure">重新生成</el-button>
            <el-button
              type="primary"
              :disabled="!selectedTopic"
              :loading="saving"
              :icon="CircleCheck"
              @click="saveSelection"
            >
              保存选择
            </el-button>
          </div>
        </header>

        <div class="results-summary">
          <div>
            <span>生成任务</span>
            <strong>#{{ taskId ?? 'READY' }}</strong>
          </div>
          <div>
            <span>创作关键词</span>
            <strong>{{ form.keywords }}</strong>
          </div>
          <p>
            <el-icon><MagicStick /></el-icon>
            分数用于快速比较，不替代你的创作判断。
          </p>
        </div>

        <div class="topic-list">
          <TopicCard
            v-for="(topic, index) in topics"
            :key="topic.id"
            :topic="topic"
            :index="index"
            selectable
            :selected="selectedTopicId === topic.id"
            @select="chooseTopic"
          />
        </div>

        <div class="sticky-save" :class="{ visible: selectedTopic }">
          <div>
            <el-icon><CircleCheck /></el-icon>
            <span>
              已选择
              <strong>{{ selectedTopic?.title }}</strong>
            </span>
          </div>
          <el-button type="primary" :loading="saving" @click="saveSelection">
            保存并查看故事
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </section>
    </Transition>

    <Teleport to="body">
      <Transition name="loading-fade">
        <div v-if="generating" class="generation-overlay">
          <div class="generation-card">
            <div class="ai-orbit">
              <span class="orbit-ring ring-one" />
              <span class="orbit-ring ring-two" />
              <el-icon><MagicStick /></el-icon>
            </div>
            <span class="generation-kicker">STORY AGENTS AT WORK</span>
            <h2>正在锻造你的故事选题</h2>
            <p>{{ loadingMessages[loadingMessageIndex] }}</p>
            <div class="progress-track"><span /></div>
            <small>通常需要 10–30 秒，请不要关闭页面</small>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.section-kicker,
.guide-kicker,
.generation-kicker {
  color: var(--sf-accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 2.2px;
}

.configure-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 28px;
  align-items: start;
}

.form-column {
  min-width: 0;
}

.form-intro {
  margin-bottom: 27px;
}

.form-intro h2,
.result-title h2 {
  margin: 7px 0 8px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(26px, 2.8vw, 35px);
  font-weight: 600;
}

.form-intro p,
.result-title p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 12px;
  line-height: 1.7;
}

.story-form {
  overflow: hidden;
  border: 1px solid var(--sf-line);
  border-radius: 22px;
  background: #fff;
  box-shadow: 0 10px 35px rgba(38, 29, 75, 0.045);
}

.form-section {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: 0;
  padding: 26px 30px 27px 20px;
  border-bottom: 1px solid #eeeaf1;
}

.section-number {
  padding-top: 2px;
  color: #b0abba;
  font-family: Georgia, serif;
  font-size: 11px;
}

.field-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 23px;
}

.field-heading h3 {
  margin: 0 0 5px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.field-heading p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 10px;
}

.field-heading > .el-icon {
  color: #b0a6dd;
  font-size: 20px;
}

:deep(.el-form-item) {
  margin-bottom: 18px;
}

:deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

:deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 7px;
  color: #5a5464;
  font-size: 10px;
  font-weight: 750;
  line-height: 1.2;
}

:deep(.el-input__wrapper),
:deep(.el-select__wrapper) {
  min-height: 46px;
  border-radius: 10px;
}

:deep(.el-select) {
  width: 100%;
}

:deep(.el-textarea__inner) {
  padding: 13px 14px 27px;
  border: 0;
  border-radius: 10px;
  box-shadow: 0 0 0 1px var(--sf-line) inset;
  line-height: 1.7;
}

:deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--sf-primary) inset;
}

.audience-options {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.audience-options button {
  position: relative;
  min-height: 45px;
  border: 1px solid var(--sf-line);
  border-radius: 10px;
  color: #777180;
  background: #fff;
  cursor: pointer;
  font-size: 10px;
  font-weight: 650;
}

.audience-options button:hover {
  border-color: #c9c1e5;
}

.audience-options button.active {
  border-color: var(--sf-primary);
  color: var(--sf-primary);
  background: #f6f3ff;
  box-shadow: 0 0 0 2px rgba(92, 73, 213, 0.06);
}

.mode-options {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.mode-options button {
  display: grid;
  gap: 4px;
  padding: 12px 28px 12px 12px;
  text-align: left;
}

.mode-options button strong {
  color: var(--sf-ink-strong);
  font-size: 12px;
}

.mode-options button small {
  color: var(--sf-ink-muted);
  font-size: 9px;
  line-height: 1.45;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.profile-grid :deep(.el-input-number),
.profile-grid :deep(.el-select) {
  width: 100%;
}

.audience-options .el-icon {
  position: absolute;
  top: 4px;
  right: 4px;
  font-size: 9px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
}

.suggestions > span {
  margin-right: 3px;
  color: var(--sf-ink-muted);
  font-size: 9px;
}

.suggestions button {
  padding: 5px 8px;
  border: 1px solid #e6e2e9;
  border-radius: 7px;
  color: #7a7481;
  background: #faf9fb;
  cursor: pointer;
  font-size: 9px;
}

.suggestions button:hover {
  border-color: #cfc7e9;
  color: var(--sf-primary);
  background: #f6f3ff;
}

.generate-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 23px 30px 23px 68px;
  background: #faf9fb;
}

.generate-action > div {
  display: grid;
  gap: 4px;
}

.generate-action strong {
  color: var(--sf-ink-strong);
  font-size: 11px;
}

.generate-action span {
  color: var(--sf-ink-muted);
  font-size: 9px;
}

.generate-action .el-button {
  min-width: 170px;
  height: 45px;
  border-radius: 11px;
  box-shadow: 0 10px 22px rgba(80, 60, 190, 0.17);
}

.guide-column {
  position: sticky;
  top: 116px;
  display: grid;
  gap: 16px;
}

.guide-card,
.principle-card {
  border: 1px solid var(--sf-line);
  border-radius: 20px;
  background: #fff;
}

.guide-card {
  padding: 25px 23px;
}

.guide-card h3 {
  margin: 8px 0 25px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 17px;
}

.agent-step {
  display: flex;
  gap: 12px;
}

.agent-step > span {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border: 1px solid #dfdbe7;
  border-radius: 9px;
  color: #918b9b;
  font-family: Georgia, serif;
  font-size: 10px;
}

.agent-step.active > span {
  border-color: var(--sf-primary);
  color: #fff;
  background: var(--sf-primary);
  box-shadow: 0 6px 14px rgba(80, 61, 190, 0.2);
}

.agent-step strong {
  color: var(--sf-ink-strong);
  font-size: 11px;
}

.agent-step p {
  margin: 5px 0 0;
  color: var(--sf-ink-muted);
  font-size: 9px;
  line-height: 1.6;
}

.agent-connector {
  width: 1px;
  height: 24px;
  margin: 5px 0 5px 14px;
  background: #e6e2eb;
}

.principle-card {
  padding: 19px 21px;
  border-color: #eadfd2;
  background: #fffaf4;
}

.principle-card span {
  color: #bb6651;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 1.2px;
}

.principle-card blockquote {
  margin: 8px 0 0;
  color: #6c625e;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 12px;
  line-height: 1.7;
}

.results-layout {
  position: relative;
}

.results-header {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr) auto;
  align-items: end;
  gap: 22px;
  margin-bottom: 24px;
}

.back-button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  align-self: start;
  padding: 7px 0;
  border: 0;
  color: var(--sf-ink-muted);
  background: transparent;
  cursor: pointer;
  font-size: 10px;
}

.back-button:hover {
  color: var(--sf-primary);
}

.result-title p strong {
  color: var(--sf-primary);
  font-family: Georgia, serif;
}

.result-actions {
  display: flex;
  gap: 8px;
}

.result-actions .el-button {
  border-radius: 10px;
}

.results-summary {
  display: grid;
  grid-template-columns: 130px minmax(180px, 1fr) auto;
  gap: 10px;
  align-items: center;
  margin-bottom: 18px;
  padding: 14px 17px;
  border: 1px solid var(--sf-line);
  border-radius: 13px;
  background: #fff;
}

.results-summary > div {
  display: grid;
  gap: 3px;
}

.results-summary span {
  color: var(--sf-ink-muted);
  font-size: 8px;
  font-weight: 700;
}

.results-summary strong {
  overflow: hidden;
  color: var(--sf-ink-strong);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.results-summary p {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #8c8494;
  font-size: 9px;
}

.results-summary p .el-icon {
  color: var(--sf-primary);
}

.topic-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 15px;
  padding-bottom: 92px;
}

.sticky-save {
  position: fixed;
  z-index: 14;
  right: clamp(24px, 4vw, 58px);
  bottom: 24px;
  left: calc(254px + clamp(24px, 4vw, 58px));
  display: flex;
  min-height: 67px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 10px 12px 10px 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 17px;
  color: #fff;
  background: rgba(30, 25, 61, 0.96);
  box-shadow: 0 17px 45px rgba(25, 19, 55, 0.25);
  opacity: 0;
  pointer-events: none;
  transform: translateY(15px);
  transition: 200ms ease;
  backdrop-filter: blur(18px);
}

.sticky-save.visible {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(0);
}

.sticky-save > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.sticky-save > div > .el-icon {
  color: #90e0bc;
}

.sticky-save span {
  overflow: hidden;
  color: #aaa4bd;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sticky-save span strong {
  margin-left: 6px;
  color: #fff;
}

.sticky-save .el-button {
  flex: 0 0 auto;
  border-radius: 11px;
}

.generation-overlay {
  position: fixed;
  z-index: 9999;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(17, 14, 35, 0.76);
  backdrop-filter: blur(10px);
}

.generation-card {
  width: min(100%, 430px);
  padding: 40px 34px 34px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 25px;
  color: #fff;
  text-align: center;
  background:
    radial-gradient(circle at 50% 8%, rgba(124, 102, 246, 0.22), transparent 32%),
    #201b3f;
  box-shadow: 0 35px 90px rgba(0, 0, 0, 0.35);
}

.ai-orbit {
  position: relative;
  display: grid;
  width: 92px;
  height: 92px;
  margin: 0 auto 28px;
  place-items: center;
  border-radius: 50%;
  color: #ffcc8d;
  background: rgba(255, 255, 255, 0.06);
}

.ai-orbit > .el-icon {
  font-size: 31px;
  animation: pulse 1.8s ease-in-out infinite;
}

.orbit-ring {
  position: absolute;
  border: 1px solid rgba(145, 124, 255, 0.45);
  border-radius: 50%;
}

.ring-one {
  inset: -8px;
  border-right-color: transparent;
  animation: spin 2.8s linear infinite;
}

.ring-two {
  inset: 8px;
  border-bottom-color: transparent;
  animation: spin 1.8s linear infinite reverse;
}

.generation-kicker {
  color: #b8aef1;
}

.generation-card h2 {
  margin: 11px 0 9px;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 25px;
  font-weight: 550;
}

.generation-card p {
  min-height: 22px;
  margin: 0;
  color: #aaa3c2;
  font-size: 11px;
}

.progress-track {
  overflow: hidden;
  height: 4px;
  margin: 27px 0 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}

.progress-track span {
  display: block;
  width: 38%;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #725ee7, #f0a86d);
  animation: progress 2.2s ease-in-out infinite;
}

.generation-card small {
  color: #77718f;
  font-size: 8px;
}

.fade-enter-active,
.fade-leave-active,
.loading-fade-enter-active,
.loading-fade-leave-active {
  transition: opacity 180ms ease;
}

.fade-enter-from,
.fade-leave-to,
.loading-fade-enter-from,
.loading-fade-leave-to {
  opacity: 0;
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

@keyframes progress {
  0% {
    transform: translateX(-110%);
  }
  50% {
    width: 58%;
  }
  100% {
    transform: translateX(290%);
  }
}

@media (max-width: 1150px) {
  .configure-layout {
    grid-template-columns: 1fr;
  }

  .guide-column {
    position: static;
    grid-template-columns: 1fr 0.75fr;
  }

  .topic-list {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 860px) {
  .sticky-save {
    left: 24px;
  }
}

@media (max-width: 720px) {
  .form-section {
    grid-template-columns: 32px minmax(0, 1fr);
    padding: 22px 17px 23px 13px;
  }

  .audience-options {
    grid-template-columns: repeat(2, 1fr);
  }

  .generate-action {
    align-items: stretch;
    flex-direction: column;
    padding: 20px;
  }

  .generate-action .el-button {
    width: 100%;
  }

  .guide-column {
    grid-template-columns: 1fr;
  }

  .results-header {
    grid-template-columns: 1fr;
    align-items: start;
  }

  .result-actions {
    width: 100%;
  }

  .result-actions .el-button {
    flex: 1;
  }

  .results-summary {
    grid-template-columns: 1fr;
  }

  .sticky-save {
    right: 12px;
    bottom: 12px;
    left: 12px;
    padding-left: 14px;
  }

  .sticky-save span {
    max-width: 130px;
  }
}
</style>
