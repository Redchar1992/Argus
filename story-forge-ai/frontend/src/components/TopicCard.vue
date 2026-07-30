<script setup lang="ts">
import { Check, MagicStick, TrendCharts } from '@element-plus/icons-vue'
import { computed } from 'vue'

import type { TopicOption } from '@/types'
import { scoreTone } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    topic: TopicOption
    index: number
    selected?: boolean
    selectable?: boolean
  }>(),
  {
    selected: false,
    selectable: false,
  },
)

defineEmits<{
  select: [topic: TopicOption]
}>()

const tone = computed(() => scoreTone(props.topic.score))
const serial = computed(() => String(props.index + 1).padStart(2, '0'))
</script>

<template>
  <article
    class="topic-card"
    :class="{ selected, selectable }"
    @click="selectable && $emit('select', topic)"
  >
    <div class="card-rail">
      <span class="serial">{{ serial }}</span>
      <span class="rail-line" />
      <el-icon><MagicStick /></el-icon>
    </div>

    <div class="card-main">
      <div class="card-heading">
        <div class="title-block">
          <div class="hook-line">
            <span>核心钩子</span>
            <strong>{{ topic.hook }}</strong>
          </div>
          <h3>{{ topic.title }}</h3>
        </div>
        <div class="score" :class="tone">
          <strong>{{ topic.score || '—' }}</strong>
          <span>商业潜力</span>
        </div>
      </div>

      <p class="summary">{{ topic.summary }}</p>

      <div v-if="topic.scoreDetails.length" class="score-grid">
        <div
          v-for="detail in topic.scoreDetails"
          :key="detail.dimension"
          class="score-detail"
          :title="detail.reason"
        >
          <span>{{ detail.label }}</span>
          <strong>{{ detail.score }}</strong>
          <i>
            <b :style="{ width: `${detail.score}%` }" />
          </i>
        </div>
      </div>

      <div v-if="topic.reasons.length" class="reason-list">
        <div v-for="reason in topic.reasons.slice(0, 4)" :key="reason" class="reason">
          <el-icon><TrendCharts /></el-icon>
          <span>{{ reason }}</span>
        </div>
      </div>

      <footer class="card-footer">
        <div class="tags">
          <span v-for="tag in topic.tags.slice(0, 4)" :key="tag">{{ tag }}</span>
        </div>
        <button
          v-if="selectable"
          class="select-button"
          :class="{ active: selected }"
          type="button"
          @click.stop="$emit('select', topic)"
        >
          <el-icon v-if="selected"><Check /></el-icon>
          {{ selected ? '已选为主方案' : '选择此方案' }}
        </button>
        <span v-else-if="selected" class="selected-label">
          <el-icon><Check /></el-icon>
          已选方案
        </span>
      </footer>
    </div>
  </article>
</template>

<style scoped>
.topic-card {
  position: relative;
  display: grid;
  overflow: hidden;
  grid-template-columns: 52px minmax(0, 1fr);
  border: 1px solid var(--sf-line);
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 6px 20px rgba(45, 34, 86, 0.035);
  transition:
    border-color 180ms ease,
    transform 180ms ease,
    box-shadow 180ms ease;
}

.topic-card.selectable {
  cursor: pointer;
}

.topic-card:hover {
  border-color: #d6d0e5;
  transform: translateY(-2px);
  box-shadow: 0 16px 35px rgba(45, 34, 86, 0.075);
}

.topic-card.selected {
  border-color: var(--sf-primary);
  box-shadow:
    0 0 0 2px rgba(92, 73, 213, 0.09),
    0 18px 38px rgba(64, 46, 159, 0.12);
}

.card-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px 0;
  color: #aba6ba;
  background: #faf9fc;
}

.selected .card-rail {
  color: var(--sf-primary);
  background: #f5f2ff;
}

.serial {
  font-family: Georgia, serif;
  font-size: 12px;
  font-weight: 700;
}

.rail-line {
  width: 1px;
  min-height: 32px;
  flex: 1;
  margin: 10px 0;
  background: #e3e0e9;
}

.card-rail .el-icon {
  font-size: 16px;
}

.card-main {
  min-width: 0;
  padding: 22px 24px 18px;
}

.card-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.title-block {
  min-width: 0;
}

.hook-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.hook-line span {
  padding: 3px 7px;
  border-radius: 5px;
  color: #af5545;
  background: var(--sf-accent-soft);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 1px;
}

.hook-line strong {
  overflow: hidden;
  color: var(--sf-accent);
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

h3 {
  margin: 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.45;
}

.score {
  display: grid;
  width: 62px;
  height: 62px;
  flex: 0 0 62px;
  place-content: center;
  border: 1px solid #ebe8ef;
  border-radius: 50%;
  text-align: center;
  background: #faf9fb;
}

.score::before {
  position: absolute;
  content: '';
}

.score strong {
  color: var(--sf-ink-strong);
  font-family: Georgia, serif;
  font-size: 20px;
  line-height: 1;
}

.score span {
  margin-top: 3px;
  color: var(--sf-ink-muted);
  font-size: 7px;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.score.excellent {
  border-color: #f2c9a4;
  background: #fff8ee;
}

.score.excellent strong {
  color: #cc674e;
}

.score.good {
  border-color: #cbc5ed;
  background: #f7f5ff;
}

.score.good strong {
  color: var(--sf-primary);
}

.summary {
  margin: 15px 0 17px;
  color: #666174;
  font-size: 13px;
  line-height: 1.82;
}

.score-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 16px;
}

.score-detail {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 5px 8px;
  padding: 9px 10px;
  border-radius: 9px;
  background: #faf9fc;
}

.score-detail span {
  overflow: hidden;
  color: var(--sf-ink-muted);
  font-size: 9px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.score-detail strong {
  color: var(--sf-ink-strong);
  font-family: Georgia, serif;
  font-size: 11px;
}

.score-detail i {
  position: relative;
  overflow: hidden;
  height: 3px;
  grid-column: 1 / -1;
  border-radius: 999px;
  background: #e8e5ef;
}

.score-detail b {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--sf-primary), #9b88ee);
}

.reason-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 18px;
}

.reason {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  gap: 7px;
  color: #777284;
  font-size: 10px;
  line-height: 1.5;
}

.reason .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
  color: var(--sf-success);
}

.card-footer {
  display: flex;
  min-height: 32px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 17px;
  padding-top: 14px;
  border-top: 1px solid #f0edf2;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.tags span {
  padding: 4px 8px;
  border-radius: 6px;
  color: #797384;
  background: #f3f1f5;
  font-size: 9px;
}

.select-button,
.selected-label {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 700;
}

.select-button {
  padding: 7px 11px;
  border: 1px solid #ddd8e8;
  border-radius: 8px;
  color: #615b70;
  background: #fff;
  cursor: pointer;
}

.select-button:hover,
.select-button.active {
  border-color: var(--sf-primary);
  color: var(--sf-primary);
  background: #f5f2ff;
}

.selected-label {
  color: var(--sf-primary);
}

@media (max-width: 680px) {
  .topic-card {
    grid-template-columns: 40px minmax(0, 1fr);
  }

  .card-main {
    padding: 18px 16px 15px;
  }

  .score-grid,
  .reason-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .hook-line strong {
    max-width: 190px;
  }

  .card-footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .select-button {
    width: 100%;
    justify-content: center;
  }
}
</style>
