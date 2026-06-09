<script setup lang="ts">
import { useRoute } from 'vue-router';
const route = useRoute();
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header class="topbar">
      <span class="brand">
        <svg class="eye-mark" viewBox="0 0 100 100" aria-hidden="true">
          <path d="M10 50 Q50 18 90 50 Q50 82 10 50 Z" fill="none" stroke="url(#g)" stroke-width="6" />
          <circle cx="50" cy="50" r="16" fill="url(#g)" />
          <circle cx="50" cy="50" r="6" fill="#0c0a14" />
          <defs>
            <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#8b5cf6" />
              <stop offset="1" stop-color="#c4b5fd" />
            </linearGradient>
          </defs>
        </svg>
        <span class="logo">Argus</span>
        <span class="title-sep">Admin Console</span>
      </span>
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
  background: var(--argus-panel, #18141f);
  border-bottom: 1px solid var(--argus-line, #2a2336);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 16px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}
.eye-mark {
  width: 26px;
  height: 26px;
}
.logo {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.5px;
  background: linear-gradient(90deg, #8b5cf6, #c4b5fd);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.title-sep {
  color: var(--argus-muted, #8a93a3);
  font-size: 14px;
  font-weight: 500;
  border-left: 1px solid var(--argus-line, #2a2336);
  padding-left: 12px;
}
.sub {
  color: var(--argus-muted, #8a93a3);
  margin-left: auto;
  font-size: 13px;
}
</style>
