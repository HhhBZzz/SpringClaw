import { defineStore } from 'pinia';
import { login, me, readToken, register, writeToken } from '../services/api';
import type { AuthProfileResponse } from '../types';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: readToken(),
    profile: null as AuthProfileResponse | null,
    loading: false,
    error: ''
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token && state.profile),
    username: (state) => state.profile?.username || '',
    roleCode: (state) => state.profile?.roleCode || '-'
  },
  actions: {
    async loadMe() {
      if (!this.token) return;
      this.loading = true;
      this.error = '';
      try {
        this.profile = await me();
      } catch (error) {
        this.logout();
        this.error = error instanceof Error ? error.message : '登录态失效';
      } finally {
        this.loading = false;
      }
    },
    async signIn(username: string, password: string, mode: 'login' | 'register') {
      this.loading = true;
      this.error = '';
      try {
        const result = mode === 'login' ? await login(username, password) : await register(username, password);
        this.token = result.token;
        writeToken(result.token);
        this.profile = { username: result.username, roleCode: result.roleCode, expireAt: result.expireAt };
      } catch (error) {
        this.error = error instanceof Error ? error.message : '认证失败';
        throw error;
      } finally {
        this.loading = false;
      }
    },
    logout() {
      this.token = '';
      this.profile = null;
      writeToken('');
    }
  }
});
