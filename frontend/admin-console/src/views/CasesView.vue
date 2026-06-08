<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import type { CaseView } from '../types';

const cases = ref<CaseView[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    cases.value = await api.getCases();
  } catch (e) {
    ElMessage.error('Could not load cases: ' + (e as Error).message);
  } finally {
    loading.value = false;
  }
}

function decisionType(d: string): string {
  if (d === 'BLOCK') return 'danger';
  if (d === 'REVIEW') return 'warning';
  return 'success';
}

onMounted(load);
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="hdr">
        <span>Completed Cases</span>
        <el-button size="small" @click="load">Refresh</el-button>
      </div>
    </template>
    <el-alert
      type="info"
      :closable="false"
      title="Durable case headers persisted by the orchestrator on each completed investigation (SQL side)."
      style="margin-bottom: 12px"
    />
    <el-table :data="cases" stripe>
      <el-table-column prop="id" label="Case" width="170" />
      <el-table-column prop="subjectAddress" label="Subject" />
      <el-table-column label="Decision" width="120">
        <template #default="{ row }">
          <el-tag :type="decisionType(row.decision)" size="small">{{ row.decision }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="riskScore" label="Score" width="90" />
      <el-table-column prop="riskBand" label="Band" width="110" />
      <el-table-column prop="createdBy" label="By" width="120" />
    </el-table>
  </el-card>
</template>

<style scoped>
.hdr {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
