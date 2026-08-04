<script setup lang="ts">
/**
 * HarnessView —— /harness 路由入口:harness 装配流水线(完整产品外壳截面)。
 * 解耦 demo,复用现有 trace 的 7 层 live 信号 + 3 层 demo,不改后端。
 */
import HarnessConveyor from '../features/blueprint-canvas/components/HarnessConveyor.vue';
</script>

<template>
  <HarnessConveyor />
</template>
