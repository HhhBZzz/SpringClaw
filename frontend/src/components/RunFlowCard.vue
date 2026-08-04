<script setup lang="ts">
/**
 * RunFlowCard —— 回复内联的动态执行流程卡
 *
 * 节点由该次 run 的真实 AgentTraceEvent[] 生成(非写死 demo):每个 trace 事件 → 一个节点,
 * 按 event.status 点亮(started→live / success→done / failed→failed),顺序连线。
 * 不同任务 → 不同 trace → 不同节点 / 不同细节。trace 流式追加时节点动态生长。
 * 放在 agent 回复处:看答案的同时看到"这次是怎么跑出来的"。
 */
import { computed, ref } from 'vue';
import type { AgentTraceEvent } from '../types';
import '../features/blueprint-canvas/blueprint-canvas.css';

const props = defineProps<{
  events: AgentTraceEvent[];
  accent?: string;
}>();

type State = 'idle' | 'live' | 'done' | 'failed';
function stateOf(s: string | undefined): State {
  if (s === 'success') return 'done';
  if (s === 'failed') return 'failed';
  if (s === 'skipped') return 'idle';
  return 'live'; // started / 默认
}

const steps = computed(() => props.events.map((e, i) => ({
  id: `rf-${i}`,
  idx: i,
  kind: e.type || 'step',
  label: e.stepName,
  state: stateOf(e.status),
  detail: e.detail,
  loc: [e.stepSchema, e.category, e.action].filter(Boolean).join(' · ') || undefined,
  duration: e.durationMs
})));

const expanded = ref<number | null>(null);
function toggle(i: number) { expanded.value = expanded.value === i ? null : i; }
</script>

<template>
  <div v-if="steps.length" class="run-flow" :style="{ '--rf-accent': accent || 'var(--bp-neon)' }">
    <div class="run-flow-head">
      <span class="run-flow-head-mark" aria-hidden="true" />
      <span>// execution flow · {{ steps.length }} 步 · 动态生成自真实 trace</span>
      <span class="run-flow-head-state">{{ steps.filter(s => s.state === 'done').length }}/{{ steps.length }}</span>
    </div>
    <div class="run-flow-rail">
      <div
        v-for="st in steps"
        :key="st.id"
        class="run-flow-step"
        :class="`is-${st.state}`"
        role="button"
        tabindex="0"
        @click="toggle(st.idx)"
        @keydown.enter.prevent="toggle(st.idx)"
      >
        <span class="run-flow-node" aria-hidden="true" />
        <div class="run-flow-step-card">
          <div class="run-flow-step-head">
            <span class="run-flow-badge">{{ st.kind }}</span>
            <span class="run-flow-label">{{ st.label }}</span>
            <span v-if="st.duration" class="run-flow-dur">{{ st.duration }}ms</span>
          </div>
          <span v-if="expanded === st.idx && st.detail" class="run-flow-detail">{{ st.loc }} — {{ st.detail }}</span>
          <span v-else-if="st.loc" class="run-flow-loc">{{ st.loc }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.run-flow {
  --rf-accent: var(--bp-neon, #ffd23f);
  position: relative;
  margin: 12px 0;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 12px;
  padding: 12px 14px;
  background: linear-gradient(180deg, rgba(20, 26, 34, 0.6), rgba(10, 13, 18, 0.4));
  font-family: var(--mono, "JetBrains Mono", monospace);
}
.run-flow-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--rf-accent);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.run-flow-head-mark { width: 8px; height: 8px; border-radius: 2px; background: var(--rf-accent); box-shadow: 0 0 8px var(--rf-accent); }
.run-flow-head-state { margin-left: auto; color: var(--bp-muted, #8b93a1); font-weight: 700; letter-spacing: 0.04em; }

.run-flow-rail { position: relative; padding-left: 6px; }
.run-flow-rail::before {
  content: "";
  position: absolute;
  left: 9px;
  top: 10px;
  bottom: 10px;
  width: 2px;
  background: rgba(255, 255, 255, 0.08);
}
.run-flow-step { position: relative; display: flex; gap: 12px; padding: 5px 0 5px 18px; cursor: pointer; }
.run-flow-node {
  position: absolute;
  left: 3px;
  top: 9px;
  width: 12px;
  height: 12px;
  border-radius: 999px;
  background: var(--bp-surface-raised, #181c25);
  box-shadow: inset 0 0 0 2px rgba(255, 255, 255, 0.14);
  transition: background 200ms ease, box-shadow 200ms ease;
}
.run-flow-step.is-live .run-flow-node { background: var(--rf-accent); box-shadow: 0 0 0 3px rgba(255, 210, 63, 0.16), 0 0 12px var(--rf-accent); animation: rf-breathe 1.2s ease-in-out infinite; }
.run-flow-step.is-done .run-flow-node { background: color-mix(in srgb, var(--rf-accent) 70%, transparent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--rf-accent) 14%, transparent); }
.run-flow-step.is-failed .run-flow-node { background: #ff5c5c; box-shadow: 0 0 0 3px rgba(255, 92, 92, 0.16), 0 0 12px rgba(255, 92, 92, 0.5); }
@keyframes rf-breathe { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }

.run-flow-step-card { flex: 1; min-width: 0; }
.run-flow-step-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.run-flow-badge {
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid color-mix(in srgb, var(--rf-accent) 40%, transparent);
  background: color-mix(in srgb, var(--rf-accent) 10%, transparent);
  color: var(--rf-accent);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: -0.2px;
  text-transform: lowercase;
}
.run-flow-label { color: var(--ink, #f2f4f8); font-size: 12px; font-weight: 600; letter-spacing: -0.5px; }
.run-flow-dur { margin-left: auto; color: var(--bp-muted, #8b93a1); font-size: 10px; font-variant-numeric: tabular-nums; }
.run-flow-loc { display: block; margin-top: 2px; color: var(--bp-muted, #8b93a1); font-size: 10px; letter-spacing: -0.3px; }
.run-flow-detail {
  display: block;
  margin-top: 4px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #05070a;
  color: var(--rf-accent);
  font-size: 10.5px;
  line-height: 1.5;
  letter-spacing: -0.5px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
