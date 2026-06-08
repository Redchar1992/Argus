<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import type { Policy } from '../types';

const policies = ref<Policy[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    policies.value = await api.getPolicies();
  } catch (e) {
    ElMessage.error('Could not load policies: ' + (e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function save(policy: Policy) {
  try {
    const updated = await api.updatePolicy(policy.key, policy.value, 'admin');
    policy.value = updated.value;
    ElMessage.success(`Policy '${policy.key}' saved`);
  } catch (e) {
    ElMessage.error('Save failed: ' + (e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="hdr">
        <span>Screening Policies</span>
        <el-button size="small" @click="load">Refresh</el-button>
      </div>
    </template>
    <el-alert
      type="info"
      :closable="false"
      title="These thresholds drive the agent's decisions (block/review cutoffs, max steps, trace depth). Edits are recorded in the audit log."
      style="margin-bottom: 12px"
    />
    <el-table :data="policies" stripe>
      <el-table-column prop="key" label="Policy" width="200" />
      <el-table-column prop="description" label="Description" />
      <el-table-column label="Value" width="240">
        <template #default="{ row }">
          <div class="value-cell">
            <el-input-number v-model="row.value" :min="0" :max="100" size="small" />
            <el-button type="primary" size="small" @click="save(row)">Save</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.hdr {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.value-cell {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
