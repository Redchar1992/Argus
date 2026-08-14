import { createRouter, createWebHashHistory } from 'vue-router';

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/tools' },
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
