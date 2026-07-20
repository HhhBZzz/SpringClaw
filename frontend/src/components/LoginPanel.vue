<script setup lang="ts">
import { computed, ref } from 'vue';
import { useAuthStore } from '../stores/auth';

const auth = useAuthStore();
const emit = defineEmits<{ authenticated: [] }>();
const username = ref('');
const password = ref('');
const displayError = computed(() => formatAuthError(auth.error, auth.errorKind, auth.errorStatus));

async function submit(mode: 'login' | 'register') {
  const trimmedUsername = username.value.trim();
  if (!trimmedUsername || !password.value || auth.loading) return;
  username.value = trimmedUsername;
  try {
    await auth.signIn(trimmedUsername, password.value, mode);
    password.value = '';
    emit('authenticated');
  } catch {
    // auth store already keeps a safe user-facing error.
  }
}

function formatAuthError(message: string, kind: string, status: number) {
  if (!message) return '';
  if (kind === 'network') return '暂时连不上后端，请确认 18080 服务已启动。';
  if (kind === 'non_json' && status === 403) return '接口被跨域或网关策略拒绝，请通过 Vue/Vite 5173 访问并确认后端 CORS 配置。';
  if (kind === 'empty') return '接口没有返回内容，请确认后端服务状态。';
  if (message.length > 120) return `${message.slice(0, 120)}...`;
  return message;
}
</script>

<template>
  <form class="login-card" aria-label="登录" @submit.prevent="submit('login')">
    <div class="panel-title-row">
      <div>
        <p class="eyebrow">Identity</p>
        <h2>登录后开始执行</h2>
      </div>
      <span class="status-dot" :class="auth.profile ? 'is-online' : 'is-warn'"></span>
    </div>

    <label class="field-label" for="login-username">用户名 / 飞书 userId</label>
    <input id="login-username" v-model="username" autocomplete="username" placeholder="your_username" />

    <label class="field-label" for="login-password">密码</label>
    <input id="login-password" v-model="password" autocomplete="current-password" type="password" placeholder="输入密码" />

    <div class="button-row">
      <button class="btn-primary" type="submit" :disabled="auth.loading || !username.trim() || !password">
        <span v-if="auth.loading" class="button-spinner" aria-hidden="true"></span>
        登录
      </button>
      <button class="btn-subtle" type="button" :disabled="auth.loading || !username.trim() || !password" @click="submit('register')">注册</button>
    </div>

    <div role="status" aria-live="polite">
      <p v-if="displayError" class="status danger">{{ displayError }}</p>
      <p v-else-if="auth.profile" class="status ok">{{ auth.profile.username }} / {{ auth.profile.roleCode }}</p>
      <p v-else class="status warn">未登录，Agent 请求会被拦截</p>
    </div>
  </form>
</template>
