<script setup lang="ts">
import { computed } from 'vue';
import type { TaskPhase } from '../../features/task-workspace/taskLifecycle';

const props = defineProps<{
  phase: TaskPhase;
  title: string;
  detail: string;
  elapsedLabel: string;
  result: string;
  canRetry: boolean;
  canRefreshStatus: boolean;
}>();

const emit = defineEmits<{
  retry: [];
  refreshStatus: [];
  openDetails: [];
}>();

const phaseLabel = computed(() => ({
  idle: '等待任务',
  preparing: '准备中',
  running: '处理中',
  awaiting_approval: '等待确认',
  executing_approved_tool: '安全执行中',
  completed: '已完成',
  failed: '未完成',
  cancelled: '已取消',
  status_unknown: '状态待确认'
})[props.phase]);
</script>

<template>
  <section class="task-status-card" :class="`is-${phase}`" aria-live="polite" aria-atomic="true">
    <div class="task-status-card__copy">
      <span class="task-status-card__phase">{{ phaseLabel }}</span>
      <h3>{{ title }}</h3>
      <p>{{ result || detail }}</p>
      <small v-if="elapsedLabel">已用时 {{ elapsedLabel }}</small>
    </div>
    <div class="task-status-card__actions">
      <button v-if="canRetry" class="btn-subtle" type="button" @click="emit('retry')">重新发起</button>
      <button v-if="canRefreshStatus" class="btn-subtle" type="button" @click="emit('refreshStatus')">重新查询状态</button>
      <button class="btn-subtle" type="button" @click="emit('openDetails')">开发者详情</button>
    </div>
  </section>
</template>
