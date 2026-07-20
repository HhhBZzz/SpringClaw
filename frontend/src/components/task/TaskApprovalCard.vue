<script setup lang="ts">
defineProps<{
  title: string;
  summary: string;
  riskLevel: string;
  targetPaths: string[];
  submitting: boolean;
  approveLabel: string;
}>();

const emit = defineEmits<{
  approve: [];
  reject: [];
}>();
</script>

<template>
  <section class="task-approval-card" aria-live="assertive" aria-atomic="true">
    <div>
      <span class="risk-badge">{{ riskLevel }}</span>
      <h3>{{ title }}</h3>
      <p>{{ summary }}</p>
      <p v-if="targetPaths.length" class="task-approval-card__impact">影响范围：{{ targetPaths.join('、') }}</p>
      <small>确认前不会执行。</small>
    </div>
    <div class="task-approval-card__actions">
      <button class="btn-subtle light" type="button" :disabled="submitting" @click="emit('reject')">拒绝</button>
      <button class="btn-primary" type="button" :disabled="submitting" @click="emit('approve')">{{ approveLabel }}</button>
    </div>
  </section>
</template>
