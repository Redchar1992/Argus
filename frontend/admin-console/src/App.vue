<script setup lang="ts">
import { useRoute } from 'vue-router';
const route = useRoute();
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header class="topbar">
      <span class="brand">👁️ Argus — Admin Console</span>
      <span class="sub">policies · tools · cases · audit</span>
    </el-header>
    <el-container>
      <el-aside width="200px">
        <el-menu :default-active="route.path" router>
          <el-menu-item index="/tools">Tools</el-menu-item>
          <el-menu-item index="/policies">Policies</el-menu-item>
          <el-menu-item index="/cases">Cases</el-menu-item>
          <el-menu-item index="/audit">Audit Log</el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.topbar {
  background: #1d2330;
  color: #fff;
  display: flex;
  align-items: center;
  gap: 16px;
}
.brand {
  font-size: 18px;
  font-weight: 600;
}
.sub {
  color: #8a93a6;
  margin-left: auto;
}
</style>
