<script setup lang="ts">
import {
  Check,
  CircleCheck,
  MagicStick,
  Position,
  VideoPlay,
} from '@element-plus/icons-vue'
import { ref } from 'vue'

import type { ChapterPlan } from '@/types'

defineProps<{
  plan?: ChapterPlan
  approved: boolean
  planning: boolean
  approving: boolean
  generating: boolean
  canGenerate: boolean
}>()

const emit = defineEmits<{
  create: [targetLength: number]
  approve: []
  generate: []
}>()

const targetLength = ref(1800)
</script>

<template>
  <section class="plan-panel">
    <header class="plan-heading">
      <div class="plan-icon"><Position /></div>
      <div>
        <span>CHAPTER BLUEPRINT</span>
        <h2>章节场景计划</h2>
        <p>先确认 3–6 个场景，再让 AI 按顺序生成正文。</p>
      </div>
      <span v-if="approved" class="approved-chip"><Check /> 已确认</span>
    </header>

    <div v-if="!plan" class="plan-empty">
      <div class="empty-orbit"><MagicStick /></div>
      <div>
        <strong>这一章还没有场景计划</strong>
        <p>AI 会读取已批准大纲、人物卡、最近章节摘要与未完成伏笔。</p>
      </div>
      <label>
        <span>目标字数</span>
        <el-input-number
          v-model="targetLength"
          :min="800"
          :max="5000"
          :step="200"
          controls-position="right"
        />
      </label>
      <el-button
        type="primary"
        size="large"
        :icon="MagicStick"
        :loading="planning"
        @click="emit('create', targetLength)"
      >
        生成章节计划
      </el-button>
    </div>

    <template v-else>
      <div class="plan-summary">
        <div>
          <span>章节标题</span>
          <strong>{{ plan.chapterTitle }}</strong>
        </div>
        <div>
          <span>本章目标</span>
          <strong>{{ plan.chapterGoal || '完成指定大纲任务' }}</strong>
        </div>
        <div>
          <span>目标长度</span>
          <strong>{{ plan.targetLength }} 字</strong>
        </div>
      </div>

      <div class="hook-row">
        <div>
          <span>OPENING HOOK</span>
          <p>{{ plan.openingHook || '开头钩子待补充' }}</p>
        </div>
        <i />
        <div>
          <span>ENDING HOOK</span>
          <p>{{ plan.endingHook || '结尾悬念待补充' }}</p>
        </div>
      </div>

      <div class="scene-list">
        <article v-for="scene in plan.scenes" :key="scene.sceneNo" class="scene-card">
          <div class="scene-index">
            <strong>{{ String(scene.sceneNo).padStart(2, '0') }}</strong>
            <span>{{ scene.sceneFunction }}</span>
          </div>
          <div class="scene-main">
            <header>
              <strong>{{ scene.location || '地点待定' }}</strong>
              <span>{{ scene.time || '时间待定' }}</span>
              <small>{{ scene.characters.join(' · ') || '角色待定' }}</small>
            </header>
            <p>{{ scene.visibleConflict || scene.protagonistGoal }}</p>
            <dl>
              <div><dt>目标与阻力</dt><dd>{{ scene.protagonistGoal }} / {{ scene.opposingForce }}</dd></div>
              <div><dt>信息变化</dt><dd>{{ scene.informationRevealed || '—' }}</dd></div>
              <div><dt>离场钩子</dt><dd>{{ scene.exitHook || '—' }}</dd></div>
            </dl>
          </div>
        </article>
      </div>

      <footer class="plan-actions">
        <p v-if="approved">
          <CircleCheck /> 场景计划已锁定。正文生成将严格按照场景顺序推进。
        </p>
        <p v-else>请确认开头事件、场景冲突和结尾悬念是否符合预期。</p>
        <el-button
          v-if="!approved"
          type="primary"
          size="large"
          :icon="CircleCheck"
          :loading="approving"
          @click="emit('approve')"
        >
          确认场景计划
        </el-button>
        <el-button
          v-else-if="canGenerate"
          type="primary"
          size="large"
          :icon="VideoPlay"
          :loading="generating"
          @click="emit('generate')"
        >
          开始生成正文
        </el-button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.plan-panel {
  overflow: hidden;
  border: 1px solid var(--sf-line);
  border-radius: 19px;
  background: #fff;
  box-shadow: 0 12px 34px rgba(46, 37, 88, 0.055);
}

.plan-heading {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 18px 20px;
  border-bottom: 1px solid #efedf2;
  background: linear-gradient(105deg, #fbfaff, #fff);
}

.plan-icon,
.empty-orbit {
  display: grid;
  width: 39px;
  height: 39px;
  flex: 0 0 39px;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: linear-gradient(145deg, #6d58df, #4a38b8);
  box-shadow: 0 8px 18px rgba(76, 55, 185, 0.19);
}

.plan-heading > div:nth-child(2) {
  min-width: 0;
}

.plan-heading span,
.hook-row span,
.plan-summary span {
  color: var(--sf-accent);
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 1.3px;
}

.plan-heading h2 {
  margin: 3px 0 2px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.plan-heading p,
.plan-empty p,
.plan-actions p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 8px;
}

.approved-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 6px 8px;
  border-radius: 8px;
  color: #287b5d !important;
  background: #eaf7f1;
  letter-spacing: 0 !important;
}

.approved-chip svg {
  width: 11px;
}

.plan-empty {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  gap: 15px;
  align-items: center;
  padding: 24px 20px;
}

.plan-empty strong {
  display: block;
  margin-bottom: 5px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 16px;
}

.plan-empty label {
  display: grid;
  gap: 4px;
  color: var(--sf-ink-muted);
  font-size: 7px;
  font-weight: 700;
}

.plan-summary {
  display: grid;
  grid-template-columns: 0.8fr 1.4fr 0.45fr;
  gap: 1px;
  background: #ece9f0;
}

.plan-summary > div {
  display: grid;
  gap: 5px;
  padding: 12px 16px;
  background: #faf9fb;
}

.plan-summary strong {
  color: #4e485b;
  font-size: 9px;
  line-height: 1.45;
}

.hook-row {
  display: grid;
  grid-template-columns: 1fr 28px 1fr;
  align-items: center;
  gap: 12px;
  padding: 14px 17px;
  border-bottom: 1px solid #f0edf3;
  background: #fffaf7;
}

.hook-row i {
  height: 1px;
  background: linear-gradient(90deg, #e0b6a7, #d8d1e7);
}

.hook-row p {
  margin: 4px 0 0;
  color: #655968;
  font-size: 8px;
  line-height: 1.5;
}

.scene-list {
  display: grid;
  gap: 10px;
  padding: 16px 17px;
}

.scene-card {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid #ebe8ef;
  border-radius: 12px;
  background: #fff;
}

.scene-index {
  display: grid;
  place-content: center;
  border-right: 1px solid #e9e5ef;
  color: #fff;
  background: linear-gradient(160deg, #40376d, #272342);
  text-align: center;
}

.scene-index strong {
  font-family: Georgia, serif;
  font-size: 16px;
}

.scene-index span {
  margin-top: 4px;
  color: #c8c1e3;
  font-size: 6px;
}

.scene-main {
  padding: 11px 13px;
}

.scene-main header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.scene-main header strong {
  color: var(--sf-ink-strong);
  font-size: 9px;
}

.scene-main header span {
  color: var(--sf-accent);
  font-size: 7px;
}

.scene-main header small {
  overflow: hidden;
  margin-left: auto;
  color: var(--sf-ink-muted);
  font-size: 7px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scene-main > p {
  margin: 7px 0 9px;
  color: #554f61;
  font-size: 9px;
  line-height: 1.5;
}

.scene-main dl {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 7px;
  margin: 0;
}

.scene-main dl > div {
  padding: 7px 8px;
  border-radius: 7px;
  background: #f8f7fa;
}

.scene-main dt {
  color: var(--sf-ink-muted);
  font-size: 6px;
  font-weight: 750;
}

.scene-main dd {
  margin: 3px 0 0;
  color: #66606e;
  font-size: 7px;
  line-height: 1.4;
}

.plan-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 15px;
  padding: 13px 17px;
  border-top: 1px solid #ece9ef;
  background: #faf9fb;
}

.plan-actions p {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-right: auto;
}

.plan-actions svg {
  width: 13px;
  color: var(--sf-success);
}

@media (max-width: 900px) {
  .plan-empty {
    grid-template-columns: auto 1fr;
  }

  .plan-empty label,
  .plan-empty > .el-button {
    grid-column: 1 / -1;
  }

  .plan-summary,
  .scene-main dl {
    grid-template-columns: 1fr;
  }
}
</style>
