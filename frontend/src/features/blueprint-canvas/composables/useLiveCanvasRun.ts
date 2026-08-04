/**
 * useLiveCanvasRun —— 把现有 /api/chat/stream 的 event:trace 接到画布
 *
 * 后端已在 SSE 推 AgentTraceEvent(无需新建后端端点)。本 composable:
 *   - start() 用当前范式 cloneBlueprint 设拓扑(onTopology)
 *   - 每个 trace 事件 advanceFrame 推进一帧(onAdvance) → 画布点亮 + 丰富真实日志
 *   - done/error 回调收尾
 * 节点丰富与渲染由画布(持有 reactive 节点)在 onAdvance 里完成,本 composable 只编排。
 */
import { ref } from 'vue';
import { readToken, streamChat } from '../../../services/api';
import type { AgentParadigm, AgentTraceEvent } from '../../../types';
import type { CanvasRunFrame, ParadigmBlueprint } from '../types';
import { cloneBlueprint } from './useParadigmTopology';
import { routeTraceEvent, traceToLogLine } from '../traceDriver';

export type LiveStatus = 'idle' | 'running' | 'done' | 'error';

export interface UseLiveCanvasRunOptions {
  /** 当前画布范式(live 请求用它) */
  paradigm: () => AgentParadigm;
  /** 收到范式 → 画布设置拓扑(cloneBlueprint 结果) */
  onTopology: (blueprint: ParadigmBlueprint) => void;
  /** 未到末帧:推进到 frameIndex,画布点亮该帧并丰富主节点真实日志 */
  onAdvance: (frameIndex: number, frame: CanvasRunFrame, event: AgentTraceEvent) => void;
  /** Fork B 已在末帧:不截断,把事件 append 到末帧主节点 X-Ray 终端 + 提速 glitch */
  onOverflow: (nodeId: string, logLine: string, event: AgentTraceEvent) => void;
  onDone: () => void;
  onError: (message: string) => void;
}

export function useLiveCanvasRun(opts: UseLiveCanvasRunOptions) {
  const status = ref<LiveStatus>('idle');
  const error = ref('');
  let controller: AbortController | null = null;
  let blueprint: ParadigmBlueprint | null = null;
  let frameIndex = -1;

  function stop() {
    controller?.abort();
    if (status.value === 'running') status.value = 'idle';
  }

  async function start(input: { userId: string; message: string; sessionKey?: string }) {
    if (!readToken()) {
      status.value = 'error';
      error.value = '先到 /agent 登录后再跑 live(需 auth token)';
      opts.onError(error.value);
      return;
    }
    status.value = 'running';
    error.value = '';
    frameIndex = -1;
    controller = new AbortController();

    const paradigm = opts.paradigm();
    blueprint = cloneBlueprint(paradigm);
    opts.onTopology(blueprint);

    const sessionKey = input.sessionKey || `canvas-${Math.random().toString(36).slice(2, 10)}`;
    try {
      await streamChat(
        {
          sessionKey,
          userId: input.userId,
          message: input.message,
          paradigm,
          responseMode: 'agent',
          channel: 'api'
        },
        {
          onTrace: (ev: AgentTraceEvent) => {
            if (!blueprint) return;
            const total = blueprint.frames.length;
            const route = routeTraceEvent(frameIndex, total);
            frameIndex = route.frameIndex;
            if (route.type === 'advance') {
              const frame = blueprint.frames[frameIndex];
              if (frame) opts.onAdvance(frameIndex, frame, ev);
            } else {
              // Fork B:已在末帧 → 溢出到末帧主节点 X-Ray 终端,不截断
              const lastFrame = blueprint.frames[total - 1];
              const activeId = lastFrame?.nodeIds[0];
              if (activeId) opts.onOverflow(activeId, traceToLogLine(ev), ev);
            }
          },
          onDone: () => {
            if (status.value === 'running') { status.value = 'done'; opts.onDone(); }
          },
          onError: (msg) => {
            status.value = 'error';
            error.value = msg;
            opts.onError(msg);
          }
        },
        { signal: controller.signal, timeoutMs: 180_000 }
      );
      if (status.value === 'running') { status.value = 'done'; opts.onDone(); }
    } catch (e) {
      if (controller.signal.aborted) { status.value = 'idle'; return; }
      status.value = 'error';
      error.value = e instanceof Error ? e.message : 'live 运行失败';
      opts.onError(error.value);
    }
  }

  return { status, error, start, stop };
}
