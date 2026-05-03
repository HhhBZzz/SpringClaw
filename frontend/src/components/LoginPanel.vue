<script setup lang="ts">
import { ref } from 'vue';
import { useAuthStore } from '../stores/auth';

const auth = useAuthStore();
const username = ref('');
const password = ref('');

async function submit(mode: 'login' | 'register') {
  const trimmedUsername = username.value.trim();
  if (!trimmedUsername || !password.value || auth.loading) return;
  await auth.signIn(trimmedUsername, password.value, mode);
  password.value = '';
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
    <input id="login-username" v-model="username" autocomplete="username" placeholder="hanbingzheng" />

    <label class="field-label" for="login-password">密码</label>
    <input id="login-password" v-model="password" autocomplete="current-password" type="password" placeholder="输入密码" />

    <div class="button-row">
      <button class="btn-primary" type="submit" :disabled="auth.loading || !username.trim() || !password">
        <span v-if="auth.loading" class="button-spinner" aria-hidden="true"></span>
        登录
      </button>
      <button class="btn-subtle" type="button" :disabled="auth.loading || !username.trim() || !password" @click="submit('register')">注册</button>
    </div>

    <p v-if="auth.error" class="status danger">{{ auth.error }}</p>
    <p v-else-if="auth.profile" class="status ok">{{ auth.profile.username }} / {{ auth.profile.roleCode }}</p>
    <p v-else class="status warn">未登录，Agent 请求会被拦截</p>
  </form>
</template>
