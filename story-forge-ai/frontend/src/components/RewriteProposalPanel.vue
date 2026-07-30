<script setup lang="ts">
import {
  CircleCheck,
  Close,
  RefreshRight,
  Warning,
} from '@element-plus/icons-vue'
import { computed } from 'vue'

import type { RewriteProposal } from '@/types'
import { buildSideBySideDiff } from '@/utils/chapter'

const props = defineProps<{
  proposal: RewriteProposal
  loading?: boolean
}>()

const emit = defineEmits<{
  accept: []
  reject: []
  regenerate: []
}>()

const diff = computed(() =>
  buildSideBySideDiff(props.proposal.originalText, props.proposal.replacementText),
)
const actionable = computed(
  () => props.proposal.status === 'READY' && !props.proposal.stale,
)
</script>

<template>
  <section class="proposal-panel" :class="{ stale: proposal.stale }">
    <header>
      <div>
        <span>AI REVISION PROPOSAL</span>
        <h3>局部改写建议</h3>
      </div>
      <span v-if="proposal.stale" class="proposal-status stale-status">
        <Warning /> 已过期
      </span>
      <span v-else-if="proposal.status === 'ACCEPTED'" class="proposal-status accepted">
        <CircleCheck /> 已接受
      </span>
      <span v-else-if="proposal.status === 'REJECTED'" class="proposal-status rejected">
        已拒绝
      </span>
      <span v-else class="proposal-status">等待决定</span>
    </header>

    <div v-if="proposal.stale" class="stale-warning">
      <Warning />
      <div>
        <strong>正文已发生变化，禁止自动替换</strong>
        <p>{{ proposal.staleReason || '请重新选择当前版本中的文字，再生成新的建议。' }}</p>
      </div>
    </div>

    <div class="proposal-reason">
      <span>修改理由</span>
      <p>{{ proposal.reason || 'AI 根据所选动作重新组织了这段正文。' }}</p>
    </div>

    <div class="diff-grid">
      <article class="diff-card original">
        <header><span>ORIGINAL</span><strong>原文</strong></header>
        <p>
          <template v-for="(segment, index) in diff.original" :key="index">
            <mark v-if="segment.changed">{{ segment.value }}</mark>
            <template v-else>{{ segment.value }}</template>
          </template>
        </p>
      </article>
      <article class="diff-card replacement">
        <header><span>SUGGESTION</span><strong>建议文本</strong></header>
        <p>
          <template v-for="(segment, index) in diff.replacement" :key="index">
            <mark v-if="segment.changed">{{ segment.value }}</mark>
            <template v-else>{{ segment.value }}</template>
          </template>
        </p>
      </article>
    </div>

    <footer>
      <el-button
        :icon="Close"
        :disabled="proposal.status !== 'READY'"
        :loading="loading"
        @click="emit('reject')"
      >
        拒绝
      </el-button>
      <el-button
        :icon="RefreshRight"
        :disabled="!actionable"
        :loading="loading"
        @click="emit('regenerate')"
      >
        再生成
      </el-button>
      <el-button
        type="primary"
        :icon="CircleCheck"
        :disabled="!actionable"
        :loading="loading"
        @click="emit('accept')"
      >
        接受建议
      </el-button>
    </footer>
  </section>
</template>

<style scoped>
.proposal-panel {
  overflow: hidden;
  border: 1px solid #d9d2ee;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 14px 34px rgba(50, 38, 111, 0.08);
}

.proposal-panel > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 15px;
  border-bottom: 1px solid #ebe7f2;
  background: linear-gradient(105deg, #f7f4ff, #fff);
}

.proposal-panel > header span,
.proposal-reason > span,
.diff-card header span {
  color: var(--sf-accent);
  font-size: 6px;
  font-weight: 800;
  letter-spacing: 1.2px;
}

.proposal-panel h3 {
  margin: 3px 0 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 15px;
}

.proposal-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 7px;
  border-radius: 7px;
  color: #6658b0 !important;
  background: #eeeaff;
  letter-spacing: 0 !important;
}

.proposal-status svg {
  width: 10px;
}

.proposal-status.accepted {
  color: #287b5d !important;
  background: #e9f7f1;
}

.proposal-status.rejected,
.proposal-status.stale-status {
  color: #a95a48 !important;
  background: #fff0ea;
}

.stale-warning {
  display: flex;
  gap: 8px;
  margin: 12px 12px 0;
  padding: 9px 10px;
  border: 1px solid #efc8bd;
  border-radius: 9px;
  color: #ad5144;
  background: #fff4f1;
}

.stale-warning > svg {
  width: 16px;
  flex: 0 0 16px;
}

.stale-warning strong {
  font-size: 8px;
}

.stale-warning p {
  margin: 3px 0 0;
  font-size: 7px;
  line-height: 1.45;
}

.proposal-reason {
  padding: 10px 13px;
  border-bottom: 1px solid #efedf2;
  background: #fffaf7;
}

.proposal-reason p {
  margin: 4px 0 0;
  color: #6d6266;
  font-size: 8px;
  line-height: 1.45;
}

.diff-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  background: #e8e5ec;
}

.diff-card {
  min-width: 0;
  margin: 0;
  background: #fff;
}

.diff-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-bottom: 1px solid #f0edf2;
}

.diff-card header strong {
  color: #6b6574;
  font-size: 8px;
}

.diff-card > p {
  min-height: 115px;
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 11px 12px;
  color: #4e4958;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 10px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.diff-card mark {
  padding: 1px 0;
  color: inherit;
  background: #ffe1d4;
}

.replacement mark {
  background: #dff3e9;
}

.proposal-panel > footer {
  display: flex;
  justify-content: flex-end;
  gap: 7px;
  padding: 10px 12px;
  border-top: 1px solid #eae7ed;
  background: #faf9fb;
}

@media (max-width: 720px) {
  .diff-grid {
    grid-template-columns: 1fr;
  }
}
</style>
