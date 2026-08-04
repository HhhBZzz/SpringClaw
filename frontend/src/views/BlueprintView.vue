<script setup lang="ts">
/**
 * BlueprintView —— /blueprint 路由入口
 * 解耦的 demo 页:只渲染 AgentCanvas + 注入其样式。不依赖后端、不碰 AgentView。
 */
import AgentCanvas from '../features/blueprint-canvas/components/AgentCanvas.vue';
// AgentCanvas 自带 blueprint-canvas.css,无需在此重复导入
</script>

<template>
  <AgentCanvas />
</template>
