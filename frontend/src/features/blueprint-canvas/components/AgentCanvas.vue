<script setup lang="ts">
/**
 * AgentCanvas —— Blueprint Canvas 编排核心
 *
 * 结构:.bp-shell(顶栏 + 舞台 + 运行条)
 *   舞台 = JS scale 的 1000×620 设计空间,SVG(连线+光梭)与 HTML 节点共享统一坐标系。
 *
 * 回放状态机:frames 逐帧 → spawn 光梭沿 edge 流动 → 触达目标节点点亮(live)→ 上一帧节点 done。
 * 范式切换:useParadigmFlip 的 Flip.from 让节点流体位移到新拓扑(节点按 slot index 作 key)。
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import gsap from 'gsap';
import { useAuthStore } from '../../../stores/auth';
import type { AgentParadigm, AgentTraceEvent } from '../../../types';
import type { CanvasRunFrame, NodeStatus } from '../types';
import { cloneBlueprint, listParadigms, paradigmAccent } from '../composables/useParadigmTopology';
import { useParadigmFlip } from '../composables/useParadigmFlip';
import { useExecutionPulse } from '../composables/useExecutionPulse';
import { useNodeAssembly } from '../composables/useNodeAssembly';
import { useLiveCanvasRun } from '../composables/useLiveCanvasRun';
import { enrichNodeFromTrace, primaryNodeIdOf } from '../traceDriver';
import '../blueprint-canvas.css';
import ConnectionLine from './ConnectionLine.vue';
import NodeCard from './NodeCard.vue';

const auth = useAuthStore();
const props = defineProps<{ embedded?: boolean; minimal?: boolean }>();
const stageRef = ref<HTMLElement | null>(null);
const blueprint = ref(cloneBlueprint('REACT'));
const designScale = ref(1);

const playing = ref(false);
const runDot = ref<'idle' | 'running' | 'done'>('idle');
const runPhase = ref('就绪 · 点击 ▶ 或切换范式');
const progress = ref(0);
const xrayNodeId = ref<string | null>(null);
const activeShuttles = ref<{ id: string; edgeId: string }[]>([]);
const edgeState = ref<Record<string, 'idle' | 'active' | 'done'>>({});
const assemblyMode = ref(false);

// LIVE 模式(接 /api/chat/stream 真实 trace)
const showLive = ref(false);
const livePrompt = ref('');
let livePrevIds: string[] = [];

const { flipTo } = useParadigmFlip({ container: stageRef });
const { pulse } = useExecutionPulse();
const { attach: attachAssembly, detach: detachAssembly } = useNodeAssembly({ container: stageRef });
const liveRun = useLiveCanvasRun({
  paradigm: () => blueprint.value.paradigm,
  onTopology: (bp) => {
    stop();
    activeShuttles.value = [];
    edgeState.value = {};
    xrayNodeId.value = null;
    livePrevIds = [];
    blueprint.value = bp;
    runDot.value = 'running';
    runPhase.value = `LIVE · ${bp.label} · 等待 trace…`;
  },
  onAdvance: (fi, frame, ev) => executeLiveFrame(fi, frame, ev),
  onOverflow: (nodeId, line) => onLiveOverflow(nodeId, line),
  onDone: () => liveFinalize(),
  onError: (msg) => { runDot.value = 'idle'; runPhase.value = `LIVE 错误 · ${msg}`; }
});
const { status: liveStatus, error: liveError } = liveRun;

/** Fork B:溢出节点的提速 glitch(短暂高频闪烁) */
const fastGlitchIds = ref<string[]>([]);

function reducedMotion() {
  return typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}
const beat = (ms: number) => new Promise<void>((r) => setTimeout(r, reducedMotion() ? Math.min(ms, 140) : ms));

const nodePos = computed(() => {
  const m = new Map<string, { x: number; y: number }>();
  blueprint.value.nodes.forEach((n) => m.set(n.id, { x: n.x, y: n.y }));
  return m;
});
const edgesResolved = computed(() =>
  blueprint.value.edges.map((e) => {
    const f = nodePos.value.get(e.from);
    const t = nodePos.value.get(e.to);
    return { ...e, fx: f?.x ?? 0, fy: f?.y ?? 0, tx: t?.x ?? 0, ty: t?.y ?? 0 };
  })
);

function setNodeStatus(id: string, status: NodeStatus) {
  const n = blueprint.value.nodes.find((x) => x.id === id);
  if (n) n.status = status;
}
function setEdgeState(id: string, state: 'idle' | 'active' | 'done') {
  edgeState.value = { ...edgeState.value, [id]: state };
}
function onXray(id: string) {
  xrayNodeId.value = xrayNodeId.value === id ? null : id;
}

function stop() {
  playing.value = false;
  runDot.value = 'idle';
  gsap.killTweensOf('.bp-shuttle');
}

async function play() {
  if (playing.value) return;
  blueprint.value.nodes.forEach((n) => { n.status = 'idle'; n.combo = 0; n.log = undefined; });
  edgeState.value = {};
  activeShuttles.value = [];
  progress.value = 0;
  playing.value = true;
  runDot.value = 'running';
  await nextTick();

  const travelMs = reducedMotion() ? 120 : 680;
  const gapMs = reducedMotion() ? 80 : 160;
  const frames = blueprint.value.frames;
  let prevLive: string[] = [];
  for (let i = 0; i < frames.length; i++) {
    if (!playing.value) break;
    const f = frames[i];
    runPhase.value = f.phase;
    progress.value = Math.round((i / frames.length) * 100);
    prevLive.filter((id) => !f.nodeIds.includes(id)).forEach((id) => setNodeStatus(id, 'done'));

    if (f.edgeIds.length === 0) {
      f.nodeIds.forEach((id) => setNodeStatus(id, 'live'));
      prevLive = [...f.nodeIds];
      runPhase.value = f.phase;
      await beat(360);
      continue;
    }
    runPhase.value = f.phase;
    f.edgeIds.forEach((eid) => setEdgeState(eid, 'active'));
    const shuttles = f.edgeIds.map((eid) => ({ id: `s-${eid}-${i}`, edgeId: eid }));
    activeShuttles.value = [...activeShuttles.value, ...shuttles];
    await nextTick();
    // fire-and-forget:光梭动画触达即点亮目标;回放循环绝不 await tween 完成
    shuttles.forEach((s) => {
      const el = stageRef.value?.querySelector(`#bp-shuttle-${s.id}`) as SVGElement | null;
      if (!el) return;
      const edge = blueprint.value.edges.find((e) => e.id === s.edgeId);
      pulse(el, `#bp-edge-${s.edgeId}`, {
        onArrive: () => { if (edge) setNodeStatus(edge.to, 'live'); }
      });
    });
    // 计时器推进:到点即点亮本帧节点并标 edge done,不依赖 tween 回调
    await beat(travelMs);
    f.edgeIds.forEach((eid) => setEdgeState(eid, 'done'));
    f.nodeIds.forEach((id) => setNodeStatus(id, 'live'));
    prevLive = [...f.nodeIds];
    // 用 id 集合移除本帧光梭(reactive Proxy 会让对象身份比较 === 失效)
    const removeIds = new Set(shuttles.map((s) => s.id));
    activeShuttles.value = activeShuttles.value.filter((s) => !removeIds.has(s.id));
    await beat(gapMs);
  }
  if (!playing.value) return;
  progress.value = 100;
  prevLive.forEach((id) => setNodeStatus(id, 'done'));
  runDot.value = 'done';
  runPhase.value = `${blueprint.value.label} · run complete`;
  playing.value = false;
}

async function replay() {
  stop();
  activeShuttles.value = [];
  xrayNodeId.value = null;
  await nextTick();
  play();
}

async function selectParadigm(np: AgentParadigm) {
  if (liveStatus.value === 'running') liveRun.stop();
  if (np === blueprint.value.paradigm) {
    if (assemblyMode.value) return; // 装配模式下同范式不重放
    await replay();
    return;
  }
  stop();
  const wasAssembly = assemblyMode.value;
  if (wasAssembly) detachAssembly(); // 装配模式下 Draggable 与 Flip 都管 .bp-node transform,先卸载
  activeShuttles.value = [];
  xrayNodeId.value = null;
  await flipTo(() => { blueprint.value = cloneBlueprint(np); edgeState.value = {}; });
  await nextTick();
  if (wasAssembly) {
    attachAssembly(); // 装配模式:换拓扑后重新挂 Draggable,不回放
    runPhase.value = `${blueprint.value.label} · ASSEMBLY`;
  } else {
    play();
  }
}

/** 切换 ASSEMBLY 模式:进入则停回放并挂 Draggable;退出则复位节点回拓扑 */
async function toggleAssembly() {
  if (liveStatus.value === 'running') liveRun.stop();
  assemblyMode.value = !assemblyMode.value;
  if (assemblyMode.value) {
    stop();
    activeShuttles.value = [];
    await nextTick();
    attachAssembly();
    runPhase.value = `${blueprint.value.label} · ASSEMBLY · 拖拽节点入模`;
  } else {
    detachAssembly();
    runPhase.value = '就绪 · 点击 ▶ 或切换范式';
  }
}

/** LIVE:一个真实 trace 事件 → 点亮该帧 + 丰富主节点真实日志 + 装饰光梭 */
async function executeLiveFrame(i: number, frame: CanvasRunFrame, event: AgentTraceEvent) {
  runPhase.value = `LIVE · ${frame.phase}`;
  progress.value = Math.round(((i + 1) / blueprint.value.frames.length) * 100);
  livePrevIds.filter((id) => !frame.nodeIds.includes(id)).forEach((id) => setNodeStatus(id, 'done'));
  const pid = primaryNodeIdOf(frame.nodeIds);
  if (pid && event) {
    const idx = blueprint.value.nodes.findIndex((n) => n.id === pid);
    if (idx >= 0) blueprint.value.nodes[idx] = enrichNodeFromTrace(blueprint.value.nodes[idx], event);
  }
  frame.nodeIds.forEach((id) => setNodeStatus(id, 'live'));
  livePrevIds = [...frame.nodeIds];
  if (frame.edgeIds.length) {
    frame.edgeIds.forEach((eid) => setEdgeState(eid, 'active'));
    const shuttles = frame.edgeIds.map((eid) => ({ id: `ls-${eid}-${i}`, edgeId: eid }));
    activeShuttles.value = [...activeShuttles.value, ...shuttles];
    await nextTick();
    shuttles.forEach((s) => {
      const el = stageRef.value?.querySelector(`#bp-shuttle-${s.id}`) as SVGElement | null;
      if (el) pulse(el, `#bp-edge-${s.edgeId}`); // 装饰性:节点已即时点亮,不依赖 onArrive
    });
    const removeIds = new Set(shuttles.map((s) => s.id));
    setTimeout(() => {
      frame.edgeIds.forEach((eid) => setEdgeState(eid, 'done'));
      activeShuttles.value = activeShuttles.value.filter((s) => !removeIds.has(s.id));
    }, reducedMotion() ? 120 : 720);
  }
}

function liveFinalize() {
  livePrevIds.forEach((id) => setNodeStatus(id, 'done'));
  fastGlitchIds.value = [];
  progress.value = 100;
  runDot.value = 'done';
  runPhase.value = `LIVE · ${blueprint.value.label} · run complete`;
}

/** Fork B:溢出——把真实事件 append 到激活节点 X-Ray 终端日志,并提速 glitch + 连击计数 */
function onLiveOverflow(nodeId: string, logLine: string) {
  const idx = blueprint.value.nodes.findIndex((n) => n.id === nodeId);
  if (idx < 0) return;
  const node = blueprint.value.nodes[idx];
  const log = node.log ? [...node.log, logLine] : [logLine];
  blueprint.value.nodes[idx] = { ...node, log, status: 'live', combo: (node.combo ?? 1) + 1 };
  if (!fastGlitchIds.value.includes(nodeId)) {
    fastGlitchIds.value = [...fastGlitchIds.value, nodeId];
  }
  // 提速 glitch 一小段后回落(节点仍 live,但闪烁回归常态)
  const stamp = nodeId;
  setTimeout(() => {
    fastGlitchIds.value = fastGlitchIds.value.filter((id) => id !== stamp);
  }, 650);
}

async function startLive() {
  if (liveStatus.value === 'running') { liveRun.stop(); return; }
  if (!auth.isLoggedIn) {
    runPhase.value = 'LIVE 需先登录 · 到 /agent 登录后回来(同会话 token 共享)';
    return;
  }
  const message = livePrompt.value.trim();
  if (!message) { runPhase.value = 'LIVE · 请输入 prompt'; return; }
  await liveRun.start({ userId: auth.username, message });
}

function updateScale() {
  const el = stageRef.value;
  if (!el) return;
  designScale.value = el.clientWidth / 1000;
}

let ro: ResizeObserver | undefined;
onMounted(() => {
  updateScale();
  ro = new ResizeObserver(() => updateScale());
  if (stageRef.value) ro.observe(stageRef.value);
  setTimeout(() => { if (!props.minimal) play(); }, 280);
});
onUnmounted(() => {
  stop();
  ro?.disconnect();
});

defineExpose({ play, replay, selectParadigm });
</script>

<template>
  <div class="bp-shell" :class="{ 'bp-shell--embedded': embedded }">
    <!-- 顶栏:范式选择器(语义色编码)+ 重放 -->
    <header v-if="!minimal" class="bp-topbar">
      <div class="bp-brand">
        <span class="bp-brand-mark" aria-hidden="true" />
        <span class="bp-brand-title">
          <strong>Blueprint Canvas</strong>
          <small>SpringClaw · agent assembly line</small>
        </span>
      </div>
      <span class="bp-divider" />
      <span class="bp-para-label">Paradigm</span>
      <div class="bp-pills" role="tablist" aria-label="Agent 范式">
        <button
          v-for="p in listParadigms()"
          :key="p.value"
          class="bp-pill"
          :class="p.value === blueprint.paradigm && 'is-active'"
          :style="{ '--p': paradigmAccent(p.value) }"
          role="tab"
          :aria-selected="p.value === blueprint.paradigm"
          @click="selectParadigm(p.value)"
        >
          <span class="dot" />{{ p.label }}
        </button>
      </div>
      <span class="bp-spacer" />
      <button
        class="bp-btn ghost"
        :aria-pressed="showLive"
        :class="showLive && 'is-on'"
        title="LIVE 模式 · 接真实 agent trace 驱动画布(需登录)"
        @click="showLive = !showLive"
      >LIVE</button>
      <button
        class="bp-btn ghost"
        :aria-pressed="assemblyMode"
        :title="assemblyMode ? '退出装配模式' : '进入装配模式 · 拖拽节点入模'"
        @click="toggleAssembly"
      >ASSEMBLY</button>
      <button class="bp-btn" :disabled="playing || assemblyMode" @click="replay">
        <span aria-hidden="true">▶</span> {{ playing ? 'running…' : '重放' }}
      </button>
    </header>

    <!-- LIVE 条:接 /api/chat/stream 真实 trace -->
    <div v-if="showLive && !minimal" class="bp-live-strip">
      <span class="bp-live-tag">LIVE · {{ blueprint.label }}</span>
      <textarea
        v-model="livePrompt"
        class="bp-live-input"
        rows="1"
        placeholder="输入 prompt,用当前范式跑真实 agent → trace 事件实时点亮画布(需先在 /agent 登录)"
        :disabled="liveStatus === 'running'"
        @keydown.ctrl.enter.prevent="startLive"
      />
      <button class="bp-btn" :class="liveStatus === 'running' && 'is-stop'" @click="startLive">
        {{ liveStatus === 'running' ? '■ STOP' : '▶ LIVE 运行' }}
      </button>
      <span class="bp-live-status" :data-state="liveStatus">
        {{ liveStatus === 'running' ? '运行中…' : liveStatus === 'done' ? '完成' : liveStatus === 'error' ? (liveError || '错误') : '就绪' }}
      </span>
    </div>

    <!-- 舞台 -->
    <div class="bp-stage-wrap">
      <div
        ref="stageRef"
        class="bp-stage"
        :class="assemblyMode && 'is-assembly'"
        :style="{ '--bp-para': blueprint.color }"
      >
        <div class="bp-grid" aria-hidden="true" />
        <span v-if="assemblyMode" class="bp-assembly-tag">ASSEMBLY · 拖拽磁吸入模</span>
        <span class="bp-stage-corner tl" aria-hidden="true" />
        <span class="bp-stage-corner tr" aria-hidden="true" />
        <span class="bp-stage-corner bl" aria-hidden="true" />
        <span class="bp-stage-corner br" aria-hidden="true" />

        <div class="bp-design" :style="{ transform: `translate(-50%, -50%) scale(${designScale})` }">
          <svg class="bp-edges" viewBox="0 0 1000 620" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
            <defs>
              <filter id="bp-glow" x="-200%" y="-200%" width="500%" height="500%">
                <feGaussianBlur stdDeviation="5" result="b" />
                <feMerge>
                  <feMergeNode in="b" />
                  <feMergeNode in="SourceGraphic" />
                </feMerge>
              </filter>
            </defs>
            <ConnectionLine
              v-for="e in edgesResolved"
              :key="e.id"
              :edge="e"
              :from-x="e.fx"
              :from-y="e.fy"
              :to-x="e.tx"
              :to-y="e.ty"
              :state="edgeState[e.id]"
            />
            <g v-for="s in activeShuttles" :key="s.id" :id="`bp-shuttle-${s.id}`" class="bp-shuttle">
              <circle class="glow" cx="0" cy="0" r="9" filter="url(#bp-glow)" />
              <circle class="core" cx="0" cy="0" r="3.5" />
            </g>
          </svg>

          <div class="bp-nodes">
            <!-- key=index:切范式时同 DOM 元素从旧坐标滑向新坐标,Flip 流体位移 -->
            <NodeCard
              v-for="(n, i) in blueprint.nodes"
              :key="i"
              :node="n"
              :is-xray="xrayNodeId === n.id"
              :fast-glitch="fastGlitchIds.includes(n.id)"
              @xray="onXray"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 运行条 -->
    <footer v-if="!minimal" class="bp-runbar">
      <span class="bp-run-dot" :class="runDot === 'running' ? 'is-running' : runDot === 'done' ? 'is-done' : ''" />
      <span class="bp-run-id">run · {{ blueprint.paradigm.toLowerCase() }}</span>
      <span class="bp-run-phase">{{ runPhase }}</span>
      <span class="bp-run-progress" :style="{ '--bp-progress': progress + '%' }"><i /></span>
      <span class="bp-run-para-tag">{{ blueprint.label }}</span>
    </footer>
  </div>
</template>

<script lang="ts">
// 让 Vue 保留组件名(便于 devtools + keep-alive)
export default { name: 'AgentCanvas' };
</script>
