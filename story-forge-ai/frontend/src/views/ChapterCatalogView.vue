<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  CircleCheck,
  Document,
  EditPen,
  MagicStick,
  Plus,
} from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getStory } from '@/api/story'
import EmptyState from '@/components/EmptyState.vue'
import { useChapterStore } from '@/stores/chapter'
import type { StoryProject } from '@/types'
import { chapterStatusLabel } from '@/utils/chapter'
import { getErrorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const chapterStore = useChapterStore()
const story = ref<StoryProject>()
const loadError = ref('')

const storyId = computed(() => String(route.params.storyId))
const chapters = computed(() => chapterStore.catalogs[storyId.value] ?? [])
const approvedCount = computed(
  () => chapters.value.filter((chapter) => chapter.status === 'APPROVED').length,
)
const totalWords = computed(() =>
  chapters.value.reduce((sum, chapter) => sum + chapter.wordCount, 0),
)
const nextChapterNo = computed(
  () => Math.max(0, ...chapters.value.map((chapter) => chapter.chapterNo)) + 1,
)

async function load() {
  loadError.value = ''
  try {
    const [storyResult] = await Promise.all([
      getStory(storyId.value),
      chapterStore.loadCatalog(storyId.value),
    ])
    story.value = storyResult
  } catch (error) {
    loadError.value = getErrorMessage(error, '章节目录加载失败。')
  }
}

function openChapter(chapterNo: number) {
  router.push({
    name: 'chapter-workspace',
    params: { storyId: storyId.value, chapterNo },
  })
}

onMounted(load)
</script>

<template>
  <div class="catalog-page">
    <div v-if="chapterStore.loadingCatalog && !story" class="catalog-loading">
      <div><MagicStick /></div>
      <strong>正在整理章节目录</strong>
      <span>读取已批准大纲、章节版本与创作进度…</span>
    </div>

    <EmptyState
      v-else-if="loadError"
      title="暂时无法打开章节目录"
      :description="loadError"
      action-label="重新加载"
      @action="load"
    />

    <template v-else-if="story">
      <header class="catalog-hero">
        <button type="button" class="back-button" @click="router.push({ name: 'story-detail', params: { id: storyId } })">
          <ArrowLeft /> 返回故事方案
        </button>
        <div class="hero-main">
          <div>
            <span class="hero-kicker">WEEK 03 · CHAPTER STUDIO</span>
            <h2>{{ story.title }}</h2>
            <p>一次创作一章，每章确认后再更新故事记忆并推进下一章。</p>
          </div>
          <el-button type="primary" size="large" :icon="Plus" @click="openChapter(nextChapterNo)">
            规划第 {{ nextChapterNo }} 章
          </el-button>
        </div>
        <div class="hero-stats">
          <div><strong>{{ chapters.length }}</strong><span>章节总数</span></div>
          <div><strong>{{ approvedCount }}</strong><span>已批准</span></div>
          <div><strong>{{ totalWords.toLocaleString() }}</strong><span>累计字数</span></div>
          <div><strong>{{ story.genre }}</strong><span>故事类型</span></div>
        </div>
      </header>

      <section class="catalog-section">
        <header class="section-heading">
          <div>
            <span>CHAPTER DIRECTORY</span>
            <h3>创作进度</h3>
            <p>正文、人工编辑与 AI 修改均保留不可变版本，可随时回看或恢复。</p>
          </div>
          <el-button :loading="chapterStore.loadingCatalog" @click="load">同步最新状态</el-button>
        </header>

        <div v-if="chapters.length" class="chapter-grid">
          <article
            v-for="chapter in chapters"
            :key="chapter.chapterNo"
            class="chapter-card"
            :class="chapter.status.toLowerCase()"
            @click="openChapter(chapter.chapterNo)"
          >
            <div class="chapter-number">
              <span>CHAPTER</span>
              <strong>{{ String(chapter.chapterNo).padStart(2, '0') }}</strong>
            </div>
            <div class="chapter-copy">
              <div class="chapter-status">
                <i /> {{ chapterStatusLabel(chapter.status) }}
              </div>
              <h4>{{ chapter.title || `第 ${chapter.chapterNo} 章` }}</h4>
              <p>
                {{ chapter.wordCount ? `${chapter.wordCount.toLocaleString()} 字` : '正文尚未生成' }}
                <span v-if="chapter.updatedTime">· {{ formatDate(chapter.updatedTime) }}</span>
              </p>
              <div class="chapter-track">
                <span :class="{ done: chapter.planApproved }">计划</span>
                <i />
                <span :class="{ done: chapter.currentVersionId }">正文</span>
                <i />
                <span :class="{ done: chapter.status === 'APPROVED' }">批准</span>
              </div>
            </div>
            <div class="chapter-action">
              <el-icon v-if="chapter.status === 'APPROVED'"><CircleCheck /></el-icon>
              <el-icon v-else-if="chapter.currentVersionId"><EditPen /></el-icon>
              <el-icon v-else><Document /></el-icon>
              <ArrowRight />
            </div>
          </article>
        </div>

        <div v-else class="empty-catalog">
          <div class="empty-icon"><Document /></div>
          <h3>从第一章开始把大纲写成正文</h3>
          <p>先生成 3–6 个场景的章节计划，确认后即可流式生成初稿。</p>
          <el-button type="primary" size="large" :icon="MagicStick" @click="openChapter(1)">
            规划第 1 章
          </el-button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.catalog-page {
  display: grid;
  gap: 22px;
}

.catalog-loading {
  display: grid;
  min-height: 520px;
  place-content: center;
  justify-items: center;
  color: var(--sf-ink-muted);
}

.catalog-loading > div {
  display: grid;
  width: 58px;
  height: 58px;
  margin-bottom: 14px;
  place-items: center;
  border-radius: 18px;
  color: #fff;
  background: linear-gradient(145deg, #705cdf, #4734ad);
  box-shadow: 0 14px 28px rgba(70, 51, 167, 0.2);
  animation: breathe 2s ease-in-out infinite;
}

.catalog-loading strong {
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.catalog-loading span {
  margin-top: 6px;
  font-size: 9px;
}

.catalog-hero {
  overflow: hidden;
  padding: 22px 26px 0;
  border-radius: 22px;
  color: #fff;
  background:
    radial-gradient(circle at 85% 5%, rgba(138, 116, 255, 0.25), transparent 31%),
    linear-gradient(140deg, #29224d, #17152d 70%);
  box-shadow: 0 18px 48px rgba(28, 22, 59, 0.17);
}

.back-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  color: #aaa3c7;
  background: transparent;
  cursor: pointer;
  font-size: 9px;
}

.back-button svg {
  width: 12px;
}

.hero-main {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 25px;
  margin: 24px 0 26px;
}

.hero-kicker,
.section-heading > div > span {
  color: #f0af79;
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 2px;
}

.hero-main h2 {
  margin: 7px 0 5px;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(26px, 3vw, 38px);
  font-weight: 550;
}

.hero-main p {
  margin: 0;
  color: #aaa4be;
  font-size: 10px;
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin-inline: -26px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
}

.hero-stats > div {
  display: grid;
  min-height: 68px;
  place-content: center;
  border-right: 1px solid rgba(255, 255, 255, 0.07);
  text-align: center;
}

.hero-stats > div:last-child {
  border-right: 0;
}

.hero-stats strong {
  overflow: hidden;
  max-width: 150px;
  font-family: Georgia, 'STSong', serif;
  font-size: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hero-stats span {
  margin-top: 3px;
  color: #928ba8;
  font-size: 7px;
}

.catalog-section {
  padding: 20px 22px 24px;
  border: 1px solid var(--sf-line);
  border-radius: 20px;
  background: #fff;
  box-shadow: var(--sf-shadow);
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 17px;
}

.section-heading h3 {
  margin: 4px 0 2px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 21px;
}

.section-heading p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 8px;
}

.chapter-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.chapter-card {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  overflow: hidden;
  min-height: 150px;
  border: 1px solid #e7e3eb;
  border-radius: 15px;
  background: #fff;
  cursor: pointer;
  transition: 180ms ease;
}

.chapter-card:hover {
  transform: translateY(-2px);
  border-color: #cfc7e9;
  box-shadow: 0 12px 27px rgba(51, 42, 93, 0.09);
}

.chapter-number {
  display: grid;
  place-content: center;
  color: #fff;
  background: linear-gradient(160deg, #40376c, #25213f);
  text-align: center;
}

.chapter-number span {
  color: #a9a1c4;
  font-size: 5px;
  font-weight: 800;
  letter-spacing: 1px;
}

.chapter-number strong {
  margin-top: 5px;
  font-family: Georgia, serif;
  font-size: 23px;
}

.chapter-copy {
  min-width: 0;
  padding: 16px 15px;
}

.chapter-status {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #6f629f;
  font-size: 7px;
  font-weight: 750;
}

.chapter-status i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #7e6ad8;
}

.approved .chapter-status {
  color: #287b5d;
}

.approved .chapter-status i {
  background: #309774;
}

.failed .chapter-status {
  color: #ae554a;
}

.failed .chapter-status i {
  background: #c65c50;
}

.chapter-copy h4 {
  overflow: hidden;
  margin: 8px 0 4px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chapter-copy > p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 7px;
}

.chapter-track {
  display: flex;
  align-items: center;
  margin-top: 15px;
}

.chapter-track span {
  padding: 3px 6px;
  border-radius: 6px;
  color: #aaa5b2;
  background: #f2f0f4;
  font-size: 6px;
  font-weight: 750;
}

.chapter-track span.done {
  color: #6559a5;
  background: #eeeaff;
}

.chapter-track i {
  width: 16px;
  height: 1px;
  background: #dfdce3;
}

.chapter-action {
  display: grid;
  width: 48px;
  place-content: center;
  gap: 19px;
  border-left: 1px solid #efedf1;
  color: #9c96a8;
  background: #faf9fb;
}

.chapter-action svg {
  width: 14px;
}

.empty-catalog {
  display: grid;
  min-height: 360px;
  place-content: center;
  justify-items: center;
  text-align: center;
}

.empty-icon {
  display: grid;
  width: 64px;
  height: 64px;
  place-items: center;
  border-radius: 20px;
  color: var(--sf-primary);
  background: #efecff;
}

.empty-catalog h3 {
  margin: 15px 0 5px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 20px;
}

.empty-catalog p {
  margin: 0 0 17px;
  color: var(--sf-ink-muted);
  font-size: 9px;
}

@keyframes breathe {
  50% { transform: scale(1.06); }
}

@media (max-width: 900px) {
  .chapter-grid { grid-template-columns: 1fr; }
}

@media (max-width: 680px) {
  .hero-main,
  .section-heading { align-items: stretch; flex-direction: column; }
  .hero-stats { grid-template-columns: repeat(2, 1fr); }
  .chapter-card { grid-template-columns: 58px minmax(0, 1fr); }
  .chapter-action { display: none; }
}
</style>
