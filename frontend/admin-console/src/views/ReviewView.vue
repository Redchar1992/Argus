<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import type { CaseView, ReviewAction } from '../types';

const cases = ref<CaseView[]>([]);
const loading = ref(false);
const drawerOpen = ref(false);
const selected = ref<CaseView | null>(null);
const reviewNote = ref('');
const submitting = ref<ReviewAction | null>(null);

const queue = computed(() => cases.value.filter((item) =>
  item.reviewStatus === 'PENDING_REVIEW' || item.reviewStatus === 'NEEDS_INFO',
));

async function load() {
  loading.value = true;
  try {
    cases.value = await api.getCases();
  } catch (e) {
    ElMessage.error('Could not load review queue: ' + (e as Error).message);
  } finally {
    loading.value = false;
  }
}

function openCase(row: CaseView) {
  selected.value = row;
  reviewNote.value = row.reviewNote ?? '';
  drawerOpen.value = true;
}

async function submitReview(action: ReviewAction) {
  if (!selected.value) return;
  if ((action === 'BLOCK' || action === 'REQUEST_INFO') && !reviewNote.value.trim()) {
    ElMessage.warning('Add an evidence note before this action.');
    return;
  }

  const id = selected.value.id;
  submitting.value = action;
  try {
    const updated = await api.reviewCase(id, action, reviewNote.value.trim() || undefined);
    const index = cases.value.findIndex((item) => item.id === id);
    if (index >= 0) cases.value[index] = updated;
    selected.value = updated;
    reviewNote.value = updated.reviewNote ?? '';
    ElMessage.success(action === 'REQUEST_INFO' ? 'Evidence request recorded.' : `Case marked ${action}.`);
  } catch (e) {
    ElMessage.error('Could not update case: ' + (e as Error).message);
  } finally {
    submitting.value = null;
  }
}

function decisionType(decision: string): string {
  if (decision === 'BLOCK') return 'danger';
  if (decision === 'REVIEW') return 'warning';
  return 'success';
}

function stateType(state: string): string {
  if (state === 'NEEDS_INFO') return 'info';
  if (state === 'RESOLVED') return 'success';
  if (state === 'AUTO_APPROVED') return 'success';
  return 'warning';
}

function factorsText(raw: string | null): string {
  if (!raw) return 'No structured risk factors were persisted.';
  try {
    const parsed = JSON.parse(raw);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}

onMounted(load);
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="hdr">
        <div>
          <div class="title">Human Review Queue</div>
          <div class="subtitle">Intervene before a REVIEW case can reach a downstream decision.</div>
        </div>
        <div class="header-actions">
          <el-tag type="warning" effect="dark">{{ queue.length }} actionable</el-tag>
          <el-button size="small" @click="load">Refresh</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="warning"
      :closable="false"
      title="The agent decision remains REVIEW. A human action is recorded separately and produces an audit event."
      style="margin-bottom: 12px"
    />

    <el-table :data="queue" stripe @row-click="openCase">
      <el-table-column prop="id" label="Case" width="170" />
      <el-table-column prop="subjectAddress" label="Subject" min-width="260" />
      <el-table-column label="Agent decision" width="140">
        <template #default="{ row }">
          <el-tag :type="decisionType(row.decision)" size="small">{{ row.decision }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="riskScore" label="Score" width="90" />
      <el-table-column label="Review state" width="150">
        <template #default="{ row }">
          <el-tag :type="stateType(row.reviewStatus)" size="small">{{ row.reviewStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Action" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openCase(row)">Inspect</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-drawer v-model="drawerOpen" title="Review case" size="520px">
    <template v-if="selected">
      <div class="drawer-kicker">{{ selected.id }}</div>
      <div class="subject">{{ selected.subjectAddress }}</div>

      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="Agent decision">
          <el-tag :type="decisionType(selected.decision)" size="small">{{ selected.decision }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="Risk">{{ selected.riskScore }} · {{ selected.riskBand }}</el-descriptions-item>
        <el-descriptions-item label="Review state">
          <el-tag :type="stateType(selected.reviewStatus)" size="small">{{ selected.reviewStatus }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="Created by">{{ selected.createdBy || 'unknown' }}</el-descriptions-item>
        <el-descriptions-item v-if="selected.reviewDecision" label="Human decision">
          {{ selected.reviewDecision }} by {{ selected.reviewedBy || 'unknown' }}
        </el-descriptions-item>
      </el-descriptions>

      <h4>Agent summary</h4>
      <p class="summary">{{ selected.summary }}</p>

      <h4>Persisted evidence</h4>
      <pre class="evidence">{{ factorsText(selected.riskFactorsJson) }}</pre>

      <template v-if="selected.reviewStatus !== 'RESOLVED'">
        <h4>Reviewer note</h4>
        <el-input
          v-model="reviewNote"
          type="textarea"
          :rows="5"
          maxlength="4000"
          show-word-limit
          placeholder="Record the evidence or rationale for the intervention"
        />
        <div class="review-actions">
          <el-button
            :loading="submitting === 'REQUEST_INFO'"
            :disabled="!!submitting"
            @click="submitReview('REQUEST_INFO')"
          >
            Request evidence
          </el-button>
          <el-button
            type="danger"
            :loading="submitting === 'BLOCK'"
            :disabled="!!submitting"
            @click="submitReview('BLOCK')"
          >
            Confirm BLOCK
          </el-button>
          <el-button
            type="success"
            :loading="submitting === 'CLEAR'"
            :disabled="!!submitting"
            @click="submitReview('CLEAR')"
          >
            Mark CLEAR
          </el-button>
        </div>
      </template>
      <el-alert v-else type="success" :closable="false" title="This review gate is resolved and can no longer be changed." />
    </template>
  </el-drawer>
</template>

<style scoped>
.hdr {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.title {
  font-weight: 700;
  font-size: 16px;
}
.subtitle {
  color: var(--argus-muted);
  font-size: 12px;
  margin-top: 3px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.drawer-kicker {
  color: var(--argus-muted);
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.subject {
  color: var(--argus-accent-2);
  overflow-wrap: anywhere;
  margin: 5px 0 18px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
h4 {
  color: var(--argus-muted);
  font-size: 12px;
  letter-spacing: .05em;
  margin: 20px 0 8px;
  text-transform: uppercase;
}
.summary {
  margin: 0;
  line-height: 1.55;
}
.evidence {
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--argus-panel-2);
  border: 1px solid var(--argus-line);
  border-radius: 8px;
  color: #c9d4e6;
  font-size: 12px;
  padding: 10px;
}
.review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
@media (max-width: 620px) {
  .hdr { align-items: flex-start; flex-direction: column; }
  .header-actions { width: 100%; justify-content: space-between; }
}
</style>
