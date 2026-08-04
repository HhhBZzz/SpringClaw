<script setup lang="ts">
/**
 * HarnessConveyor —— harness 装配流水线
 *
 * 工件(请求)从顶向下穿过 10 层 harness 外壳工位,光梭沿垂直脊逐工位流动、抵达即点亮。
 * 范式核心(core)工位嵌入 AgentCanvas(minimal)。非核心工位点击 → X-Ray 翻转,背面终端显示
 * 该层真实 trace 日志(live 收集)。
 * 驱动:① demo 回放(默认)② LIVE —— /api/chat/stream 真实 trace 按执行顺序点亮+收集日志。不改后端。
 */
import { nextTick, onMounted, onUnmounted, ref } from 'vue';
import { streamChat } from '../../../services/api';
import { useAuthStore } from '../../../stores/auth';
import type { AgentParadigm, AgentTraceEvent } from '../../../types';
import AgentCanvas from './AgentCanvas.vue';
import { HARNESS_STATIONS, TRACE_TO_STATION } from '../harnessStations';
import { listParadigms, paradigmAccent } from '../composables/useParadigmTopology';
import '../blueprint-canvas.css';

const auth = useAuthStore();
defineProps<{ embedded?: boolean }>();
const railRef = ref<HTMLElement | null>(null);
const shuttleRef = ref<HTMLElement | null>(null);
const canvasRef = ref<{ selectParadigm: (p: AgentParadigm) => void } | null>(null);
const status = ref<string[]>(HARNESS_STATIONS.map(() => 'idle'));
const playing = ref(false);
const phase = ref('就绪 · ▶ 启动流水线 / 切 LIVE 跑真实 agent');
const stationLogs = ref<Record<string, string[]>>({});
const xrayId = ref<string | null>(null);

// LIVE
const showLive = ref(false);
const livePrompt = ref('');
const liveParadigm = ref<AgentParadigm>('REACT');
const liveStatus = ref<'idle' | 'running' | 'done' | 'error'>('idle');
const liveError = ref('');
let liveController: AbortController | null = null;

function reduced() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}
const beat = (ms: number) => new Promise<void>((r) => setTimeout(r, reduced() ? 120 : ms));

async function play() {
  if (playing.value) return;
  playing.value = true;
  status.value = HARNESS_STATIONS.map(() => 'idle');
  stationLogs.value = {};
  xrayId.value = null;
  const shuttle = shuttleRef.value;
  const rail = railRef.value;
  if (!shuttle || !rail) { playing.value = false; return; }
  const stations = Array.from(rail.querySelectorAll<HTMLElement>('.harness-station'));
  shuttle.style.opacity = '1';
  shuttle.style.transform = 'translateY(0px)';
  await nextTick();
  const travel = reduced() ? 120 : 520;
  const gap = reduced() ? 100 : 420;
  for (let i = 0; i < HARNESS_STATIONS.length && i < stations.length; i++) {
    if (!playing.value) break;
    const el = stations[i];
    const y = el.offsetTop + el.offsetHeight / 2 - 7;
    phase.value = `工位 ${String(HARNESS_STATIONS[i].layer).padStart(2, '0')} · ${HARNESS_STATIONS[i].label}`;
    shuttle.style.transform = `translateY(${y}px)`;
    await beat(travel);
    const nextS = [...status.value];
    nextS[i] = 'live';
    if (i > 0 && nextS[i - 1] === 'live') nextS[i - 1] = 'done';
    status.value = nextS;
    await beat(gap);
  }
  if (!playing.value) return;
  const last = [...status.value];
  last[last.length - 1] = 'done';
  status.value = last;
  phase.value = 'run complete · 已穿透全部外壳';
  playing.value = false;
}

function stationElById(id: string): HTMLElement | null {
  return railRef.value?.querySelector<HTMLElement>(`.harness-station[data-id="${id}"]`) || null;
}
function moveShuttleToStation(id: string) {
  const el = stationElById(id);
  const sh = shuttleRef.value;
  if (!el || !sh) return;
  sh.style.opacity = '1';
  sh.style.transform = `translateY(${el.offsetTop + el.offsetHeight / 2 - 7}px)`;
}

/** live:trace 事件 → 点亮对应工位 + 光梭跟随 + 收集该层真实日志 */
function activateByTrace(ev: AgentTraceEvent) {
  const id = (TRACE_TO_STATION as Record<string, string>)[ev.type];
  if (!id) return;
  const idx = HARNESS_STATIONS.findIndex((s) => s.id === id);
  if (idx < 0) return;
  const cur = [...status.value];
  cur[idx] = 'live';
  status.value = cur;
  const logs = { ...stationLogs.value };
  logs[id] = [...(logs[id] || []), `${ev.stepName} [${ev.type}]${ev.detail ? ` — ${ev.detail}` : ''}`];
  stationLogs.value = logs;
  moveShuttleToStation(id);
  phase.value = `LIVE · 工位 ${String(HARNESS_STATIONS[idx].layer).padStart(2, '0')} · ${HARNESS_STATIONS[idx].label} [${ev.type}]`;
}

// —— X-Ray 翻转(同步设 transform + CSS transition;CDP 下 rAF 不从 eval 触发,同步设可靠、真实浏览器 transition 平滑) ——
function flipStation(id: string, toBack: boolean) {
  const el = stationElById(id)?.querySelector<HTMLElement>('.harness-flip');
  if (!el) return;
  const target = toBack ? 180 : 0;
  el.style.transform = `rotateY(${target}deg)`;
  el.dataset.deg = String(target);
}
function toggleXray(id: string) {
  if (id === 'core') return;
  const prev = xrayId.value;
  if (prev && prev !== id) flipStation(prev, false);
  if (xrayId.value === id) { flipStation(id, false); xrayId.value = null; }
  else { xrayId.value = id; flipStation(id, true); }
}

async function startLive() {
  if (liveStatus.value === 'running') { stopLive(); return; }
  if (!auth.isLoggedIn) { phase.value = 'LIVE 需先登录 · 到 /agent 登录后回来'; return; }
  const message = livePrompt.value.trim();
  if (!message) { phase.value = 'LIVE · 请输入 prompt'; return; }
  playing.value = false;
  status.value = HARNESS_STATIONS.map(() => 'idle');
  stationLogs.value = {};
  xrayId.value = null;
  liveStatus.value = 'running';
  liveError.value = '';
  liveController = new AbortController();
  phase.value = `LIVE · ${liveParadigm.value} · 等待 trace…`;
  const sessionKey = `harness-${Math.random().toString(36).slice(2, 10)}`;
  try {
    await streamChat(
      { sessionKey, userId: auth.username, message, paradigm: liveParadigm.value, responseMode: 'agent', channel: 'api' },
      {
        onTrace: (ev) => activateByTrace(ev),
        onDone: () => { if (liveStatus.value === 'running') { liveStatus.value = 'done'; phase.value = 'LIVE · run complete'; } },
        onError: (msg) => { liveStatus.value = 'error'; liveError.value = msg; phase.value = `LIVE 错误 · ${msg}`; }
      },
      { signal: liveController.signal, timeoutMs: 180_000 }
    );
    if (liveStatus.value === 'running') { liveStatus.value = 'done'; phase.value = 'LIVE · run complete'; }
  } catch (e) {
    if (liveController.signal.aborted) { liveStatus.value = 'idle'; return; }
    liveStatus.value = 'error';
    liveError.value = e instanceof Error ? e.message : 'live 运行失败';
    phase.value = `LIVE 错误 · ${liveError.value}`;
  }
}
function stopLive() {
  liveController?.abort();
  if (liveStatus.value === 'running') liveStatus.value = 'idle';
}
function selectParadigmForLive(p: AgentParadigm) {
  liveParadigm.value = p;
  canvasRef.value?.selectParadigm(p);
}
function setCanvasRef(el: unknown) {
  canvasRef.value = (el as { selectParadigm: (p: AgentParadigm) => void } | null) ?? null;
}

const busy = () => playing.value || liveStatus.value === 'running';

onMounted(() => { setTimeout(() => play(), 300); });
onUnmounted(() => {
  playing.value = false;
  liveController?.abort();
});
defineExpose({ play, activateByTrace });
</script>

<template>
  <div class="harness-conveyor" :class="{ 'harness-conveyor--embedded': embedded }">
    <header class="harness-topbar">
      <span class="harness-brand"><i aria-hidden="true" /> HARNESS CONVEYOR</span>
      <span class="harness-sub">SpringClaw · 完整产品外壳装配线(10 层 harness · 工位点击 X-Ray 看真实日志)</span>
      <span class="harness-spacer" />
      <button class="bp-btn ghost" :aria-pressed="showLive" :class="showLive && 'is-on'" title="LIVE · 接真实 agent trace 点亮外壳" @click="showLive = !showLive">LIVE</button>
      <button class="bp-btn" :disabled="busy()" @click="play">▶ {{ playing ? 'running…' : '重放' }}</button>
    </header>

    <div v-if="showLive" class="bp-live-strip">
      <span class="bp-live-tag">LIVE · {{ liveParadigm }}</span>
      <div class="bp-pills" role="tablist" aria-label="范式">
        <button
          v-for="p in listParadigms()"
          :key="p.value"
          class="bp-pill"
          :class="p.value === liveParadigm && 'is-active'"
          :style="{ '--p': paradigmAccent(p.value) }"
          @click="selectParadigmForLive(p.value)"
        ><span class="dot" />{{ p.label }}</button>
      </div>
      <textarea
        v-model="livePrompt"
        class="bp-live-input"
        rows="1"
        placeholder="输入 prompt,真实 trace 按 route→model→tool→… 点亮这 10 层外壳;点工位翻转看该层真实日志(需先 /agent 登录)"
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

    <div ref="railRef" class="harness-rail">
      <span class="harness-spine" aria-hidden="true" />
      <span ref="shuttleRef" class="harness-shuttle" aria-hidden="true" />
      <div class="harness-stations">
        <div
          v-for="(s, i) in HARNESS_STATIONS"
          :key="s.id"
          :data-id="s.id"
          class="harness-station"
          :class="[`is-${status[i] || 'idle'}`, `is-group-${s.group}`, s.group === 'core' && 'is-core']"
        >
          <span class="harness-node" aria-hidden="true" />

          <!-- 范式核心:嵌入画布(不翻转) -->
          <div v-if="s.group === 'core'" class="harness-station-card">
            <div class="harness-station-head">
              <span class="harness-layer">{{ String(s.layer).padStart(2, '0') }}</span>
              <strong>{{ s.label }}</strong>
              <span v-if="s.traceTypes.length" class="harness-trace">{{ s.traceTypes.join(' · ') }}</span>
            </div>
            <code class="harness-cls">{{ s.cls }}</code>
            <p class="harness-desc">{{ s.desc }}</p>
            <div class="harness-core-embed">
              <AgentCanvas :ref="(el) => setCanvasRef(el)" embedded minimal />
            </div>
          </div>

          <!-- 非核心:可翻转 X-Ray,背面显示该层真实 trace 日志 -->
          <div
            v-else
            class="harness-station-card is-flippable"
            :class="xrayId === s.id && 'is-xray'"
            role="button"
            tabindex="0"
            :title="`X-Ray · 查看 ${s.label} 的真实日志`"
            @click="toggleXray(s.id)"
            @keydown.enter.prevent="toggleXray(s.id)"
          >
            <div class="harness-flip" :data-id="s.id">
              <!-- 正面 -->
              <div class="harness-face harness-front">
                <div class="harness-station-head">
                  <span class="harness-layer">{{ String(s.layer).padStart(2, '0') }}</span>
                  <strong>{{ s.label }}</strong>
                  <span v-if="s.traceTypes.length" class="harness-trace">{{ s.traceTypes.join(' · ') }}</span>
                  <span v-else class="harness-trace is-demo">demo</span>
                </div>
                <code class="harness-cls">{{ s.cls }}</code>
                <p class="harness-desc">{{ s.desc }}</p>
                <span class="harness-xray-hint">{{ xrayId === s.id ? '点击返回' : 'X-Ray · 点击翻转看日志' }}</span>
              </div>
              <!-- 背面:X-Ray 终端 -->
              <div class="harness-face harness-back">
                <span class="harness-xray-head">// harness log · {{ s.label }}</span>
                <code class="harness-cls">{{ s.cls }}</code>
                <div class="harness-xray-log" role="log" aria-live="polite">
                  <span v-if="!(stationLogs[s.id] || []).length" class="harness-xray-empty">
                    {{ s.traceTypes.length ? '等待 live trace 事件…' : '此层暂无离散 trace 事件(demo)' }}
                  </span>
                  <span v-for="(line, k) in (stationLogs[s.id] || [])" :key="k" class="harness-xray-line">{{ line }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <footer class="harness-statusbar">
      <span class="harness-phase">{{ phase }}</span>
    </footer>
  </div>
</template>
