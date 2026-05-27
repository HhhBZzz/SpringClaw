import { createRouter, createWebHashHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import AdminView from '../views/AdminView.vue';
import AgentView from '../views/AgentView.vue';
import HomeView from '../views/HomeView.vue';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/agent', name: 'agent', component: AgentView },
    { path: '/admin', name: 'admin', component: AdminView, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: '/console', redirect: '/agent' },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior(to) {
    if (to.hash) return { el: to.hash, behavior: 'smooth' };
    return { top: 0 };
  }
});

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) return true;
  const auth = useAuthStore();
  if (!auth.profile) {
    try {
      await auth.loadMe();
    } catch (error) {
      console.error('Failed to load user profile', error);
    }
  }
  if (!auth.profile) {
    return { path: '/agent' };
  }
  if (to.meta.requiresAdmin && auth.roleCode !== 'ADMIN') {
    return { path: '/agent' };
  }
  return true;
});

export default router;
