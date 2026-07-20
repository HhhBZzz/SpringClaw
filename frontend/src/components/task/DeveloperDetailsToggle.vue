<script setup lang="ts">
defineProps<{
  open: boolean;
  summary: string;
}>();

const emit = defineEmits<{ toggle: [] }>();
</script>

<template>
  <button class="developer-details-toggle" type="button" :aria-expanded="open" @click="emit('toggle')">
    <span>{{ open ? '收起开发者详情' : '查看开发者详情' }}</span>
    <small>{{ summary }}</small>
  </button>
</template>
