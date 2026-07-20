<script setup lang="ts">
import LoginPanel from '../LoginPanel.vue';

defineProps<{ hasDraft: boolean }>();

const emit = defineEmits<{ authenticated: [] }>();
</script>

<template>
  <section class="task-login-gate" aria-label="登录后执行任务">
    <p class="eyebrow">开始前</p>
    <h3>登录后即可执行任务</h3>
    <p>{{ hasDraft ? '你已输入的任务会保留，登录后由你决定何时发送。' : '登录后可以提交任务、查看执行状态和确认风险操作。' }}</p>
    <LoginPanel @authenticated="emit('authenticated')" />
  </section>
</template>
