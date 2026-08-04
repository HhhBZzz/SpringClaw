<script setup lang="ts">
/**
 * ConnectionLine —— 两节点间的 SVG 连线。
 * 坐标 0..100 → 设计空间 viewBox(0..1000 × 0..620) 换算:x*10 / y*6.2。
 * state: idle / active(光梭流动中,霓虹+流dash) / done(已遍历,范式色)。
 */
import { computed } from 'vue';
import type { CanvasEdge } from '../types';

const props = defineProps<{
  edge: CanvasEdge;
  fromX: number; fromY: number;
  toX: number; toY: number;
  state?: 'idle' | 'active' | 'done';
}>();

const DESIGN_W = 1000;
const DESIGN_H = 620;
const sx = (v: number) => (v / 100) * DESIGN_W;
const sy = (v: number) => (v / 100) * DESIGN_H;

const pathD = computed(() => {
  const ax = sx(props.fromX), ay = sy(props.fromY);
  const bx = sx(props.toX), by = sy(props.toY);
  const cpx = props.edge.cp ? sx(props.edge.cp.x) : (ax + bx) / 2;
  const cpy = props.edge.cp ? sy(props.edge.cp.y) : (ay + by) / 2;
  return `M ${ax.toFixed(1)} ${ay.toFixed(1)} Q ${cpx.toFixed(1)} ${cpy.toFixed(1)} ${bx.toFixed(1)} ${by.toFixed(1)}`;
});
</script>

<template>
  <path
    :id="`bp-edge-${edge.id}`"
    class="bp-edge"
    :class="[`is-kind-${edge.kind}`, state === 'active' && 'is-active', state === 'done' && 'is-done']"
    :d="pathD"
  />
</template>
