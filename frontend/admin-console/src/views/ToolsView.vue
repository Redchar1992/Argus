<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import type { ToolStatus } from '../types';

const tools = ref<ToolStatus[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    tools.value = await api.getTools();
  } catch (e) {
    ElMessage.error('Could not load tool catalog: ' + (e as Error).message);
  } finally {
    loading.value = false;
  }
}

async function toggle(tool: ToolStatus) {
  try {
    const updated = await api.setTool(tool.toolId, tool.enabled);
    tool.enabled = updated.enabled;
    ElMessage.success(`${tool.toolId} ${updated.enabled ? 'enabled' : 'disabled'}`);
  } catch (e) {
    tool.enabled = !tool.enabled; // revert
    ElMessage.error('Update failed: ' + (e as Error).message);
  }
}

onMounted(load);
</script>

<template>
  <el-card v-loading="loading">
    <template #header>
      <div class="hdr">
        <span>Screening Tools</span>
        <el-button size="small" @click="load">Refresh</el-button>
      </div>
    </template>
    <el-alert
      type="info"
      :closable="false"
      title="Disabling a tool removes it from what the agent is allowed to call. The orchestrator reads this catalog at run time."
      style="margin-bottom: 12px"
    />
    <el-table :data="tools" stripe>
      <el-table-column prop="toolId" label="Tool" width="200" />
      <el-table-column prop="description" label="Description" />
      <el-table-column label="Enabled" width="120">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" @change="toggle(row)" />
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
</style>
