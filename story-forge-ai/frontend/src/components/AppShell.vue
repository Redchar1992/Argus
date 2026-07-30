<script setup lang="ts">
import {
  ArrowRight,
  Collection,
  EditPen,
  Plus,
  SwitchButton,
} from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import BrandMark from '@/components/BrandMark.vue'
import { useAuthStore } from '@/stores/auth'
import { useStoryStore } from '@/stores/story'

const authStore = useAuthStore()
const storyStore = useStoryStore()
const route = useRoute()
const router = useRouter()

const pageTitle = computed(() => {
  if (route.name === 'story-create') return '新建故事'
  if (route.name === 'story-detail') return '故事方案'
  return '我的作品'
})

function logout() {
  authStore.logout()
  storyStore.reset()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <BrandMark inverse />
      </div>

      <div class="sidebar-intro">
        <span>你的 AI 策划搭档</span>
        <strong>让灵感落成故事，<br />让故事值得开拍。</strong>
      </div>

      <nav class="side-nav" aria-label="主导航">
        <RouterLink class="nav-item" :to="{ name: 'dashboard' }">
          <el-icon><Collection /></el-icon>
          <span>我的作品</span>
        </RouterLink>
        <RouterLink class="nav-item" :to="{ name: 'story-create' }">
          <el-icon><EditPen /></el-icon>
          <span>AI 故事策划</span>
        </RouterLink>
      </nav>

      <div class="sidebar-tip">
        <div class="tip-icon">01</div>
        <div>
          <strong>本周创作目标</strong>
          <p>从一个方向，生成并保存第一组故事方案。</p>
        </div>
      </div>
    </aside>

    <div class="shell-main">
      <header class="topbar">
        <div class="mobile-brand">
          <BrandMark />
        </div>
        <div class="page-heading">
          <span class="eyebrow">STORY WORKSPACE</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <el-button
            v-if="route.name !== 'story-create'"
            class="create-button"
            type="primary"
            :icon="Plus"
            @click="router.push({ name: 'story-create' })"
          >
            新建故事
          </el-button>
          <el-dropdown trigger="click">
            <button class="profile-button" type="button">
              <span class="avatar">{{ authStore.displayName.slice(0, 1).toUpperCase() }}</span>
              <span class="profile-copy">
                <strong>{{ authStore.displayName }}</strong>
                <small>创作者</small>
              </span>
              <el-icon><ArrowRight /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="SwitchButton" @click="logout">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--sf-canvas);
}

.sidebar {
  position: fixed;
  z-index: 20;
  inset: 0 auto 0 0;
  display: flex;
  width: 254px;
  flex-direction: column;
  padding: 28px 20px 24px;
  color: #fff;
  background:
    radial-gradient(circle at 25% 4%, rgba(126, 109, 255, 0.38), transparent 28%),
    linear-gradient(164deg, #201b43 0%, #17142d 52%, #111021 100%);
}

.sidebar::after {
  position: absolute;
  right: 20px;
  bottom: 150px;
  width: 110px;
  height: 110px;
  border: 1px solid rgba(255, 255, 255, 0.04);
  border-radius: 50%;
  box-shadow:
    0 0 0 25px rgba(255, 255, 255, 0.018),
    0 0 0 55px rgba(255, 255, 255, 0.01);
  content: '';
  pointer-events: none;
}

.sidebar-brand {
  padding: 0 8px;
}

.sidebar-intro {
  display: grid;
  gap: 8px;
  margin: 55px 8px 35px;
}

.sidebar-intro span {
  color: #a59dca;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 2px;
}

.sidebar-intro strong {
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 20px;
  font-weight: 500;
  line-height: 1.6;
  letter-spacing: 0.5px;
}

.side-nav {
  display: grid;
  gap: 8px;
}

.nav-item {
  display: flex;
  min-height: 50px;
  align-items: center;
  gap: 13px;
  padding: 0 15px;
  border: 1px solid transparent;
  border-radius: 14px;
  color: #aaa5c2;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  transition: 180ms ease;
}

.nav-item:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.06);
}

.nav-item.router-link-exact-active {
  border-color: rgba(255, 255, 255, 0.08);
  color: #fff;
  background: linear-gradient(100deg, rgba(112, 92, 242, 0.35), rgba(87, 70, 196, 0.13));
  box-shadow: inset 3px 0 #8d7eff;
}

.nav-item .el-icon {
  font-size: 19px;
}

.sidebar-tip {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 12px;
  margin-top: auto;
  padding: 15px 14px;
  border: 1px solid rgba(255, 255, 255, 0.07);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.045);
}

.tip-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  border-radius: 10px;
  color: #211d42;
  background: #ffd69c;
  font-family: Georgia, serif;
  font-size: 13px;
  font-weight: 700;
}

.sidebar-tip strong {
  font-size: 12px;
}

.sidebar-tip p {
  margin: 5px 0 0;
  color: #9892b3;
  font-size: 11px;
  line-height: 1.55;
}

.shell-main {
  min-height: 100vh;
  margin-left: 254px;
}

.topbar {
  position: sticky;
  z-index: 15;
  top: 0;
  display: flex;
  min-height: 84px;
  align-items: center;
  justify-content: space-between;
  padding: 13px clamp(24px, 4vw, 58px);
  border-bottom: 1px solid rgba(37, 30, 79, 0.07);
  background: rgba(250, 249, 247, 0.9);
  backdrop-filter: blur(18px);
}

.page-heading {
  display: grid;
  gap: 3px;
}

.eyebrow {
  color: var(--sf-accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 2.2px;
}

.page-heading h1 {
  margin: 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 24px;
  font-weight: 600;
}

.topbar-actions,
.profile-button {
  display: flex;
  align-items: center;
}

.topbar-actions {
  gap: 12px;
}

.create-button {
  height: 42px;
  padding-inline: 18px;
  border-radius: 12px;
  box-shadow: 0 8px 18px rgba(91, 70, 210, 0.16);
}

.profile-button {
  gap: 10px;
  padding: 5px 7px 5px 6px;
  border: 0;
  border-radius: 12px;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.profile-button:hover {
  background: rgba(92, 72, 207, 0.06);
}

.avatar {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: linear-gradient(145deg, #f3a86a, #d36b63);
  font-family: Georgia, serif;
  font-weight: 700;
}

.profile-copy {
  display: grid;
  min-width: 68px;
  gap: 1px;
  text-align: left;
}

.profile-copy strong {
  max-width: 100px;
  overflow: hidden;
  color: var(--sf-ink-strong);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-copy small {
  color: var(--sf-ink-muted);
  font-size: 10px;
}

.profile-button > .el-icon {
  color: #aaa5b8;
  font-size: 11px;
  transform: rotate(90deg);
}

.content {
  width: 100%;
  max-width: 1500px;
  margin: 0 auto;
  padding: 38px clamp(24px, 4vw, 58px) 64px;
}

.mobile-brand {
  display: none;
}

@media (max-width: 860px) {
  .sidebar {
    display: none;
  }

  .shell-main {
    margin-left: 0;
  }

  .mobile-brand {
    display: block;
  }

  .page-heading {
    display: none;
  }

  .topbar {
    min-height: 72px;
  }

  .profile-copy,
  .profile-button > .el-icon {
    display: none;
  }
}

@media (max-width: 560px) {
  .topbar,
  .content {
    padding-inline: 16px;
  }

  .create-button {
    width: 42px;
    padding: 0;
    font-size: 0;
  }
}
</style>
