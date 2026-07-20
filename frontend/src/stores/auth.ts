import { defineStore } from 'pinia';
import { ApiError, login, logoutRequest, me, readToken, register, writeToken, type ApiErrorKind } from '../services/api';
import type { AuthProfileResponse } from '../types';

type AuthErrorKind = ApiErrorKind | 'unknown';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: readToken(),
    profile: null as AuthProfileResponse | null,
    loading: false,
    error: '',
    errorKind: '' as AuthErrorKind | '',
    errorStatus: 0,
    errorCode: undefined as number | undefined
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.profile),
    username: (state) => state.profile?.username || '',
    roleCode: (state) => state.profile?.roleCode || '-'
  },
  actions: {
    async loadMe() {
      const hadMemoryToken = Boolean(readToken());
      this.loading = true;
      this.clearError();
      try {
        this.profile = await me();
      } catch (error) {
        if (error instanceof ApiError && error.status === 401 && !hadMemoryToken) {
          this.profile = null;
          this.token = '';
          this.clearError();
        } else if (error instanceof ApiError
            && hadMemoryToken
            && (error.status === 401 || error.status === 403)) {
          this.logout();
          this.setError(error, '登录态已失效，请重新登录。');
        } else {
          this.setError(error, error instanceof Error ? error.message : '加载用户信息失败，可稍后重试。');
        }
      } finally {
        this.loading = false;
      }
    },
    async signIn(username: string, password: string, mode: 'login' | 'register') {
      this.loading = true;
      this.clearError();
      try {
        const result = mode === 'login' ? await login(username, password) : await register(username, password);
        this.token = result.token;
        writeToken(result.token);
        this.profile = { username: result.username, roleCode: result.roleCode, expireAt: result.expireAt };
      } catch (error) {
        this.setError(error, error instanceof Error ? error.message : '认证失败');
        throw error;
      } finally {
        this.loading = false;
      }
    },
    logout() {
      this.token = '';
      this.profile = null;
      writeToken('');
      void logoutRequest().catch(() => undefined);
    },
    clearError() {
      this.error = '';
      this.errorKind = '';
      this.errorStatus = 0;
      this.errorCode = undefined;
    },
    setError(error: unknown, fallback: string) {
      if (error instanceof ApiError) {
        this.error = fallback || error.message;
        this.errorKind = error.kind;
        this.errorStatus = error.status;
        this.errorCode = error.code;
        return;
      }
      if (error instanceof TypeError) {
        this.error = fallback || '网络请求失败';
        this.errorKind = 'network';
        this.errorStatus = 0;
        this.errorCode = undefined;
        return;
      }
      this.error = fallback;
      this.errorKind = 'unknown';
      this.errorStatus = 0;
      this.errorCode = undefined;
    }
  }
});
