import { createRouter, createWebHashHistory } from 'vue-router';
import ToolsView from '../views/ToolsView.vue';
import PoliciesView from '../views/PoliciesView.vue';
import AuditView from '../views/AuditView.vue';
import CasesView from '../views/CasesView.vue';

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/tools' },
    { path: '/tools', component: ToolsView, meta: { title: 'Tools' } },
    { path: '/policies', component: PoliciesView, meta: { title: 'Policies' } },
    { path: '/cases', component: CasesView, meta: { title: 'Cases' } },
    { path: '/audit', component: AuditView, meta: { title: 'Audit Log' } },
  ],
});
