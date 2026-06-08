<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import type { AuditEntry } from '../types';

const entries = ref<AuditEntry[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    entries.value = await api.getAudit();
  } catch (e) {
    ElMessage.error('Could not load audit log: ' + (e as Error).message);
  } finally {
    loading.value = false;
  }
}

function tagType(action: string): string {
  if (action.includes('POLICY')) return 'warning';
  if (action.includes('CASE')) return 'success';
  return 'info';
}

onMounted(load);
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="hdr">
        <span>Audit Log</span>
        <el-button size="small" @click="load">Refresh</el-button>
      </div>
    </template>
    <el-table :data="entries" stripe>
      <el-table-column prop="createdAt" label="When" width="220" />
      <el-table-column prop="actor" label="Actor" width="120" />
      <el-table-column label="Action" width="180">
        <template #default="{ row }">
          <el-tag :type="tagType(row.action)" size="small">{{ row.action }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="target" label="Target" width="180" />
      <el-table-column prop="detail" label="Detail" />
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
