<script setup lang="ts">
import {
  Clock,
  Connection,
  RefreshLeft,
  Tickets,
} from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'

import type {
  ChapterVersion,
  ChapterVersionComparison,
  EntityId,
} from '@/types'
import { buildSideBySideDiff } from '@/utils/chapter'
import { formatDate } from '@/utils/format'

const props = defineProps<{
  modelValue: boolean
  versions: ChapterVersion[]
  currentVersionId?: EntityId
  comparison?: ChapterVersionComparison
  loading: boolean
  comparing: boolean
  restoring: boolean
  restoreAllowed: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  load: []
  compare: [fromVersionId: EntityId, toVersionId: EntityId]
  restore: [versionId: EntityId]
}>()

const fromVersionId = ref<EntityId>()
const toVersionId = ref<EntityId>()

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    emit('load')
  },
)

watch(
  () => [props.versions, props.currentVersionId] as const,
  ([versions, currentVersionId]) => {
    if (!versions.length) {
      fromVersionId.value = undefined
      toVersionId.value = undefined
      return
    }

    const includesVersion = (versionId: EntityId | undefined) =>
      versionId !== undefined &&
      versions.some((version) => String(version.id) === String(versionId))

    if (!includesVersion(toVersionId.value)) {
      toVersionId.value = includesVersion(currentVersionId)
        ? currentVersionId
        : versions[0].id
    }
    if (
      !includesVersion(fromVersionId.value) ||
      String(fromVersionId.value) === String(toVersionId.value)
    ) {
      fromVersionId.value = versions.find(
        (version) => String(version.id) !== String(toVersionId.value),
      )?.id
    }
  },
  { deep: true, immediate: true },
)

const fromVersion = computed(() =>
  props.versions.find((version) => String(version.id) === String(fromVersionId.value)),
)
const toVersion = computed(() =>
  props.versions.find((version) => String(version.id) === String(toVersionId.value)),
)
const localDiff = computed(() =>
  buildSideBySideDiff(fromVersion.value?.content ?? '', toVersion.value?.content ?? ''),
)

function runCompare() {
  if (fromVersionId.value === undefined || toVersionId.value === undefined) return
  emit('compare', fromVersionId.value, toVersionId.value)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    size="min(760px, 94vw)"
    class="version-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="drawer-title">
        <span class="drawer-icon"><Clock /></span>
        <div>
          <span>IMMUTABLE HISTORY</span>
          <h2>章节版本历史</h2>
          <p>每次 AI 修改、人工编辑和恢复都会创建独立版本。</p>
          <p v-if="!restoreAllowed" class="restore-lock-note">本章已批准并写入长期记忆，历史版本仅供查看和对比，不能恢复。</p>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="version-body">
      <section class="compare-controls">
        <div>
          <label>FROM</label>
          <el-select v-model="fromVersionId" placeholder="选择原版本">
            <el-option
              v-for="version in versions"
              :key="version.id"
              :label="`V${version.versionNo} · ${version.sourceType}`"
              :value="version.id"
            />
          </el-select>
        </div>
        <Connection />
        <div>
          <label>TO</label>
          <el-select v-model="toVersionId" placeholder="选择目标版本">
            <el-option
              v-for="version in versions"
              :key="version.id"
              :label="`V${version.versionNo} · ${version.sourceType}`"
              :value="version.id"
            />
          </el-select>
        </div>
        <el-button
          type="primary"
          :disabled="fromVersionId === undefined || toVersionId === undefined"
          :loading="comparing"
          @click="runCompare"
        >
          对比版本
        </el-button>
      </section>

      <section v-if="fromVersion && toVersion" class="version-diff">
        <article>
          <header><span>V{{ fromVersion.versionNo }}</span><strong>原版本</strong></header>
          <p>
            <template v-for="(segment, index) in localDiff.original" :key="index">
              <mark v-if="segment.changed">{{ segment.value }}</mark>
              <template v-else>{{ segment.value }}</template>
            </template>
          </p>
        </article>
        <article>
          <header><span>V{{ toVersion.versionNo }}</span><strong>目标版本</strong></header>
          <p>
            <template v-for="(segment, index) in localDiff.replacement" :key="index">
              <mark v-if="segment.changed">{{ segment.value }}</mark>
              <template v-else>{{ segment.value }}</template>
            </template>
          </p>
        </article>
        <div v-if="comparison" class="server-diff-note">
          <Tickets />
          服务端已确认版本对比结果；正文高亮基于当前已加载的不可变版本。
        </div>
      </section>

      <section class="version-list">
        <article
          v-for="version in versions"
          :key="version.id"
          :class="{ current: String(version.id) === String(currentVersionId) }"
        >
          <div class="version-number">V{{ version.versionNo }}</div>
          <div class="version-copy">
            <header>
              <strong>{{ version.sourceType }}</strong>
              <span v-if="String(version.id) === String(currentVersionId)">当前</span>
            </header>
            <p>{{ version.changeSummary || '该版本未填写修改摘要。' }}</p>
            <small>{{ formatDate(version.createdTime) }} · {{ version.content.length }} 字符</small>
          </div>
          <el-button
            :icon="RefreshLeft"
            v-if="restoreAllowed"
            :disabled="String(version.id) === String(currentVersionId)"
            :loading="restoring"
            @click="emit('restore', version.id)"
          >
            恢复
          </el-button>
        </article>

        <div v-if="!versions.length && !loading" class="version-empty">
          还没有可比较的章节版本。
        </div>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-title {
  display: flex;
  align-items: center;
  gap: 12px;
}

.drawer-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: linear-gradient(145deg, #6d58df, #4937b4);
}

.drawer-title > div > span,
.compare-controls label {
  color: var(--sf-accent);
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 1.3px;
}

.drawer-title h2 {
  margin: 3px 0 1px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 19px;
}

.drawer-title p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 8px;
}

.drawer-title .restore-lock-note {
  margin-top: 4px;
  color: #a5533d;
  font-weight: 650;
}

.version-body {
  display: grid;
  gap: 16px;
}

.compare-controls {
  display: grid;
  grid-template-columns: 1fr auto 1fr auto;
  gap: 10px;
  align-items: end;
  padding: 13px;
  border: 1px solid #e6e1f0;
  border-radius: 13px;
  background: #f8f6ff;
}

.compare-controls > div {
  display: grid;
  gap: 5px;
}

.compare-controls > svg {
  width: 17px;
  margin-bottom: 8px;
  color: #8a7ed1;
}

.version-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  overflow: hidden;
  border: 1px solid #e6e3e9;
  border-radius: 13px;
  background: #e8e5eb;
  gap: 1px;
}

.version-diff article {
  min-width: 0;
  background: #fff;
}

.version-diff article header {
  display: flex;
  justify-content: space-between;
  padding: 8px 10px;
  border-bottom: 1px solid #eeebf0;
  color: #6b6378;
  font-size: 8px;
}

.version-diff article header span {
  color: var(--sf-primary);
  font-weight: 800;
}

.version-diff article p {
  max-height: 300px;
  overflow: auto;
  margin: 0;
  padding: 12px;
  color: #4f4959;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 10px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.version-diff mark {
  color: inherit;
  background: #ffe0d4;
}

.version-diff article:nth-child(2) mark {
  background: #dcf2e7;
}

.server-diff-note {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  color: #6559a7;
  background: #f3f0ff;
  font-size: 7px;
}

.server-diff-note svg {
  width: 12px;
}

.version-list {
  display: grid;
  gap: 8px;
}

.version-list article {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 11px 12px;
  border: 1px solid #e8e5eb;
  border-radius: 12px;
  background: #fff;
}

.version-list article.current {
  border-color: #cfc6ef;
  background: #faf8ff;
}

.version-number {
  display: grid;
  height: 40px;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: #373052;
  font-family: Georgia, serif;
  font-size: 12px;
}

.version-copy header {
  display: flex;
  align-items: center;
  gap: 7px;
}

.version-copy header strong {
  color: var(--sf-ink-strong);
  font-size: 9px;
}

.version-copy header span {
  padding: 2px 5px;
  border-radius: 5px;
  color: #287b5d;
  background: #e9f7f1;
  font-size: 6px;
  font-weight: 750;
}

.version-copy p {
  margin: 4px 0;
  color: #706a78;
  font-size: 8px;
}

.version-copy small {
  color: var(--sf-ink-muted);
  font-size: 7px;
}

.version-empty {
  padding: 35px;
  color: var(--sf-ink-muted);
  text-align: center;
  font-size: 9px;
}

@media (max-width: 680px) {
  .compare-controls,
  .version-diff {
    grid-template-columns: 1fr;
  }

  .compare-controls > svg {
    display: none;
  }

  .version-list article {
    grid-template-columns: 42px 1fr;
  }

  .version-list article > .el-button {
    grid-column: 1 / -1;
  }
}
</style>
