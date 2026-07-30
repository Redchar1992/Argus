import { createRouter, createWebHistory } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { getStoredAuth } from '@/utils/storage'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/AuthView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: AppShell,
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '我的作品' },
        },
        {
          path: 'stories/new',
          name: 'story-create',
          component: () => import('@/views/StoryCreateView.vue'),
          meta: { title: '新建故事' },
        },
        {
          path: 'stories/:storyId/workflow/:taskId',
          name: 'workflow-progress',
          component: () => import('@/views/WorkflowProgressView.vue'),
          meta: { title: 'AI 工作流进度' },
        },
        {
          path: 'stories/:storyId/workflow/:taskId/review',
          name: 'workflow-review',
          component: () => import('@/views/WorkflowReviewView.vue'),
          meta: { title: '大纲审核' },
        },
        {
          path: 'stories/:id',
          name: 'story-detail',
          component: () => import('@/views/StoryDetailView.vue'),
          meta: { title: '故事方案' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.beforeEach((to) => {
  const isAuthenticated = Boolean(getStoredAuth()?.token)

  if (to.meta.requiresAuth && !isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && isAuthenticated) return { name: 'dashboard' }
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title || '故事工坊')} · Story Forge AI`
})

export default router
