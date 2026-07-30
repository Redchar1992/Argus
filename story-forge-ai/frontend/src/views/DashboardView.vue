<script setup lang="ts">
import {
  ArrowRight,
  Collection,
  Document,
  MagicStick,
  Plus,
  Refresh,
  TrendCharts,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { useStoryStore } from '@/stores/story'
import type { StoryProject } from '@/types'
import { getErrorMessage } from '@/utils/error'
import { formatDate, statusLabel } from '@/utils/format'
import { getTopicSession } from '@/utils/storage'

const router = useRouter()
const authStore = useAuthStore()
const storyStore = useStoryStore()

const generatedCount = computed(
  () =>
    storyStore.stories.filter((story) => {
      const session = getTopicSession(story.id)
      return Boolean(session?.topics.length || story.topics?.length)
    }).length,
)

const averageScore = computed(() => {
  const scores = storyStore.stories.flatMap((story) => {
    const topics = getTopicSession(story.id)?.topics ?? story.topics ?? []
    return topics.map((topic) => topic.score).filter(Boolean)
  })
  if (!scores.length) return '—'
  return Math.round(scores.reduce((total, score) => total + score, 0) / scores.length)
})

function selectedTitle(story: StoryProject) {
  const cached = getTopicSession(story.id)
  const topics = cached?.topics ?? story.topics ?? []
  const selectedId = cached?.selectedTopicId ?? story.selectedTopicId
  return topics.find((topic) => topic.id === selectedId)?.title
}

function storyStatus(story: StoryProject) {
  if (getTopicSession(story.id)?.topics.length || story.topics?.length) return 'generated'
  return story.status
}

async function loadStories(force = false) {
  try {
    await storyStore.fetchStories(force)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '作品列表加载失败，请稍后重试。'))
  }
}

function openStory(story: StoryProject) {
  router.push({ name: 'story-detail', params: { id: story.id } })
}

onMounted(() => loadStories())
</script>

<template>
  <div class="dashboard">
    <section class="welcome">
      <div>
        <span class="section-kicker">CREATIVE OVERVIEW</span>
        <h2>你好，{{ authStore.displayName }}。今天想写什么故事？</h2>
        <p>从一个清晰的创作方向开始，AI 策划会帮你找到最有潜力的开场。</p>
      </div>
      <el-button
        type="primary"
        size="large"
        :icon="MagicStick"
        @click="router.push({ name: 'story-create' })"
      >
        开始 AI 策划
      </el-button>
    </section>

    <section class="stats" aria-label="创作数据">
      <article>
        <span class="stat-icon violet"><Collection /></span>
        <div>
          <strong>{{ storyStore.stories.length }}</strong>
          <span>故事项目</span>
        </div>
      </article>
      <article>
        <span class="stat-icon coral"><MagicStick /></span>
        <div>
          <strong>{{ generatedCount }}</strong>
          <span>已生成方案</span>
        </div>
      </article>
      <article>
        <span class="stat-icon green"><TrendCharts /></span>
        <div>
          <strong>{{ averageScore }}</strong>
          <span>平均潜力分</span>
        </div>
      </article>
      <aside>
        <span>本周重点</span>
        <p>先验证选题价值，再扩展人物与大纲。</p>
      </aside>
    </section>

    <section class="works-section">
      <header class="section-header">
        <div>
          <span class="section-kicker">YOUR STORIES</span>
          <h2>最近作品</h2>
        </div>
        <el-button
          v-if="storyStore.stories.length"
          text
          :icon="Refresh"
          :loading="storyStore.loading"
          @click="loadStories(true)"
        >
          刷新
        </el-button>
      </header>

      <div v-if="storyStore.loading && !storyStore.loaded" class="story-grid">
        <div v-for="index in 3" :key="index" class="story-skeleton">
          <el-skeleton animated>
            <template #template>
              <el-skeleton-item variant="circle" class="skeleton-icon" />
              <el-skeleton-item variant="h3" style="width: 65%" />
              <el-skeleton-item variant="text" style="width: 42%" />
              <el-skeleton-item variant="text" style="width: 90%; margin-top: 24px" />
              <el-skeleton-item variant="button" style="width: 100%; margin-top: 28px" />
            </template>
          </el-skeleton>
        </div>
      </div>

      <EmptyState
        v-else-if="!storyStore.stories.length"
        @action="router.push({ name: 'story-create' })"
      />

      <div v-else class="story-grid">
        <article
          v-for="story in storyStore.stories"
          :key="story.id"
          class="story-card"
          tabindex="0"
          @click="openStory(story)"
          @keyup.enter="openStory(story)"
        >
          <div class="story-topline">
            <span class="story-icon"><Document /></span>
            <span class="status" :class="statusLabel(storyStatus(story)) === '已生成' ? 'done' : ''">
              <i />
              {{ statusLabel(storyStatus(story)) }}
            </span>
          </div>

          <div class="story-copy">
            <span class="genre">{{ story.genre }}</span>
            <h3>{{ story.title }}</h3>
            <p v-if="selectedTitle(story)" class="selected-topic">
              主方案 · {{ selectedTitle(story) }}
            </p>
            <p v-else class="selected-topic muted">等待生成第一个故事选题</p>
          </div>

          <div class="story-meta">
            <span>{{ formatDate(story.createdTime) }}</span>
            <span>{{ getTopicSession(story.id)?.topics.length ?? story.topics?.length ?? 0 }} 个方案</span>
          </div>

          <footer>
            <span>查看故事方案</span>
            <el-icon><ArrowRight /></el-icon>
          </footer>
        </article>

        <button
          class="new-story-card"
          type="button"
          @click="router.push({ name: 'story-create' })"
        >
          <span><Plus /></span>
          <strong>创建新故事</strong>
          <small>输入方向，让灵感开始生长</small>
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  display: grid;
  gap: 36px;
}

.welcome {
  position: relative;
  display: flex;
  overflow: hidden;
  min-height: 170px;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 34px 38px;
  border-radius: 24px;
  color: #fff;
  background:
    radial-gradient(circle at 80% 0%, rgba(149, 130, 255, 0.3), transparent 28%),
    linear-gradient(116deg, #211c45, #33285f);
  box-shadow: 0 18px 45px rgba(37, 28, 79, 0.13);
}

.welcome::after {
  position: absolute;
  right: -35px;
  bottom: -80px;
  width: 260px;
  height: 260px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 50%;
  box-shadow: 0 0 0 35px rgba(255, 255, 255, 0.025);
  content: '';
}

.section-kicker {
  color: var(--sf-accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 2.2px;
}

.welcome .section-kicker {
  color: #c5bdf2;
}

.welcome h2 {
  margin: 10px 0 8px;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(24px, 2.4vw, 32px);
  font-weight: 550;
}

.welcome p {
  margin: 0;
  color: #aaa4c3;
  font-size: 12px;
}

.welcome .el-button {
  position: relative;
  z-index: 2;
  flex: 0 0 auto;
  border-color: #fff;
  border-radius: 12px;
  color: #312a5a;
  background: #fff;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.12);
}

.welcome .el-button:hover {
  border-color: #fff;
  color: var(--sf-primary);
  background: #fff;
}

.stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(130px, 0.8fr)) minmax(230px, 1.2fr);
  gap: 14px;
}

.stats article,
.stats aside {
  display: flex;
  min-height: 90px;
  align-items: center;
  gap: 13px;
  padding: 17px 18px;
  border: 1px solid var(--sf-line);
  border-radius: 16px;
  background: #fff;
}

.stat-icon {
  display: grid;
  width: 39px;
  height: 39px;
  flex: 0 0 39px;
  place-items: center;
  border-radius: 11px;
}

.stat-icon svg,
.new-story-card svg,
.story-icon svg {
  width: 18px;
}

.stat-icon.violet {
  color: var(--sf-primary);
  background: #f0edff;
}

.stat-icon.coral {
  color: #cf6b54;
  background: #fff0e9;
}

.stat-icon.green {
  color: #2d8f6d;
  background: #eaf7f2;
}

.stats article > div {
  display: grid;
}

.stats article strong {
  color: var(--sf-ink-strong);
  font-family: Georgia, serif;
  font-size: 22px;
}

.stats article div span {
  color: var(--sf-ink-muted);
  font-size: 9px;
  font-weight: 650;
}

.stats aside {
  display: block;
  border-color: #ebe2d7;
  background: #fffaf4;
}

.stats aside span {
  color: #ae624d;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 1px;
}

.stats aside p {
  margin: 7px 0 0;
  color: #766a66;
  font-size: 10px;
  line-height: 1.5;
}

.works-section {
  display: grid;
  gap: 20px;
}

.section-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.section-header h2 {
  margin: 5px 0 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 23px;
}

.story-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(240px, 1fr));
  gap: 17px;
}

.story-card,
.new-story-card,
.story-skeleton {
  min-height: 285px;
  border: 1px solid var(--sf-line);
  border-radius: 19px;
  background: #fff;
}

.story-card {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  padding: 20px 21px 0;
  cursor: pointer;
  outline: none;
  transition:
    transform 180ms ease,
    box-shadow 180ms ease,
    border-color 180ms ease;
}

.story-card:hover,
.story-card:focus-visible {
  border-color: #d2cbe6;
  transform: translateY(-3px);
  box-shadow: var(--sf-shadow);
}

.story-topline {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.story-icon {
  display: grid;
  width: 39px;
  height: 39px;
  place-items: center;
  border-radius: 11px;
  color: var(--sf-primary);
  background: #f1eeff;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 8px;
  border-radius: 999px;
  color: #8a7f73;
  background: #f7f2ec;
  font-size: 8px;
  font-weight: 750;
}

.status i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #c69e72;
}

.status.done {
  color: #267c5d;
  background: #eaf6f1;
}

.status.done i {
  background: #3aa37c;
}

.story-copy {
  margin: 24px 0 18px;
}

.genre {
  color: var(--sf-accent);
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 1.4px;
}

.story-copy h3 {
  margin: 7px 0 10px;
  overflow: hidden;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 19px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-topic {
  min-height: 35px;
  margin: 0;
  color: #696475;
  font-size: 10px;
  line-height: 1.6;
}

.selected-topic.muted {
  color: #aaa6b2;
}

.story-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding: 14px 0;
  border-top: 1px solid #f0edf2;
  color: var(--sf-ink-muted);
  font-size: 9px;
}

.story-card footer {
  display: flex;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  margin-inline: -21px;
  padding-inline: 21px;
  color: #766e86;
  background: #faf9fb;
  font-size: 10px;
  font-weight: 700;
}

.story-card footer .el-icon {
  color: var(--sf-primary);
}

.new-story-card {
  display: grid;
  place-items: center;
  align-content: center;
  padding: 25px;
  border-style: dashed;
  color: inherit;
  background: rgba(255, 255, 255, 0.48);
  cursor: pointer;
}

.new-story-card:hover {
  border-color: var(--sf-primary);
  background: #f8f6ff;
}

.new-story-card > span {
  display: grid;
  width: 52px;
  height: 52px;
  margin-bottom: 15px;
  place-items: center;
  border-radius: 16px;
  color: var(--sf-primary);
  background: #ece8ff;
}

.new-story-card strong {
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 17px;
}

.new-story-card small {
  margin-top: 7px;
  color: var(--sf-ink-muted);
  font-size: 9px;
}

.story-skeleton {
  padding: 22px;
}

:deep(.skeleton-icon) {
  display: block;
  width: 40px;
  height: 40px;
  margin-bottom: 25px;
  border-radius: 11px;
}

@media (max-width: 1100px) {
  .stats {
    grid-template-columns: repeat(3, 1fr);
  }

  .stats aside {
    display: none;
  }

  .story-grid {
    grid-template-columns: repeat(2, minmax(230px, 1fr));
  }
}

@media (max-width: 680px) {
  .dashboard {
    gap: 26px;
  }

  .welcome {
    min-height: 230px;
    align-items: flex-start;
    flex-direction: column;
    padding: 27px 24px;
  }

  .welcome .el-button {
    width: 100%;
  }

  .stats {
    grid-template-columns: repeat(3, 1fr);
    gap: 7px;
  }

  .stats article {
    min-height: 85px;
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
    padding: 12px;
  }

  .stat-icon {
    width: 31px;
    height: 31px;
    flex-basis: 31px;
  }

  .story-grid {
    grid-template-columns: 1fr;
  }
}
</style>
