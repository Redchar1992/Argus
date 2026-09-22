import { createRouter, createWebHashHistory } from 'vue-router';

const STATIC_DEMO = import.meta.env.VITE_STATIC_DEMO === 'true';

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: STATIC_DEMO ? '/review' : '/tools' },
    {
      path: '/tools',
      component: () => import('../views/ToolsView.vue'),
      meta: { title: 'Tools' },
    },
    {
      path: '/policies',
      component: () => import('../views/PoliciesView.vue'),
      meta: { title: 'Policies' },
    },
    {
      path: '/review',
      component: () => import('../views/ReviewView.vue'),
      meta: { title: 'Review Queue' },
    },
    {
      path: '/cases',
      component: () => import('../views/CasesView.vue'),
      meta: { title: 'Cases' },
    },
    {
      path: '/audit',
      component: () => import('../views/AuditView.vue'),
      meta: { title: 'Audit Log' },
    },
  ],
});
