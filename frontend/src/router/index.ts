import { createRouter, createWebHashHistory } from 'vue-router';
import AgentView from '../views/AgentView.vue';
import HomeView from '../views/HomeView.vue';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/agent', name: 'agent', component: AgentView },
    { path: '/console', redirect: '/agent' }
  ],
  scrollBehavior(to) {
    if (to.hash) return { el: to.hash, behavior: 'smooth' };
    return { top: 0 };
  }
});

export default router;
