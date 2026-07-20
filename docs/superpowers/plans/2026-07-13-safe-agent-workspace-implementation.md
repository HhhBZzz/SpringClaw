# Safe Agent Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the default Agent page into a safe, understandable task workspace: login before execution, clear task lifecycle, direct approval, truthful asynchronous tool results, and opt-in developer diagnostics.

**Architecture:** Keep `AgentView.vue` as the owner of the existing chat/SSE/resource state and backend contracts. Add a pure lifecycle module, a bounded proposal-monitor composable, and small presentational components; derive every user-visible state from the existing stream and proposal records rather than adding a second runtime path.

**Tech Stack:** Vue 3 Composition API, TypeScript 6, Vite 8, Pinia, existing REST/SSE APIs, Vitest 4, GSAP, Maven/JUnit backend regression suite.

## Global Constraints

- Do not change Java Agent engines, SSE event names, proposal state transitions, authorization rules, or tool-risk policy.
- Preserve all existing `/api/chat/**` and `/api/tool-proposals/**` request contracts; only add the already-served `GET /api/tool-proposals/{proposalId}` frontend wrapper.
- Use Chinese user-facing copy for the task path; internal IDs, Git SHA values, trace payloads, and raw proposal metadata remain in developer/resource views.
- `EXECUTING` tool proposals poll once per second, stop at terminal status, stop on unmount/new task/session change, and enter an explicit unknown state after three consecutive fetch failures or five minutes without a terminal status.
- The default path is `登录 → 提交任务 → 读状态 → 确认/拒绝 → 读真实结果`; trace, tools, memory, and logs are opt-in details.
- Keep the existing runtime navigation and resource pages working. This work changes the default console experience, not the developer console’s available data.
- Use `npm` with the committed `frontend/package-lock.json`; do not leave generated `frontend/dist/` or test reports tracked.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `frontend/package.json` | Add deterministic test scripts and the Vitest development dependency. |
| `frontend/package-lock.json` | Lock the Vitest dependency graph created by npm. |
| `frontend/src/features/task-workspace/taskLifecycle.ts` | Pure status resolution, user-facing status text, tool terminal classification, and safe execution-result summarization. |
| `frontend/src/features/task-workspace/taskLifecycle.test.ts` | Unit tests for precedence, terminal states, elapsed/result behavior, and result parsing. |
| `frontend/src/composables/useToolProposalMonitor.ts` | Own one bounded polling lifecycle for a confirmed asynchronous tool proposal. |
| `frontend/src/composables/useToolProposalMonitor.test.ts` | Fake-timer tests for polling, terminal stop, retry limit, timeout, and cleanup. |
| `frontend/src/components/task/TaskStatusCard.vue` | Render one accessible task status/result card from the lifecycle projection. |
| `frontend/src/components/task/TaskApprovalCard.vue` | Render normal-action and tool-action approvals without exposing internal IDs. |
| `frontend/src/components/task/DeveloperDetailsToggle.vue` | Render the collapsed-by-default developer-details control. |
| `frontend/src/components/task/TaskLoginGate.vue` | Present the embedded login gate while preserving the draft owned by `AgentView`. |
| `frontend/src/components/LoginPanel.vue` | Emit successful authentication so the task composer can regain focus. |
| `frontend/src/services/api.ts` | Add `getToolProposal` and return the actual proposal DTO from confirm/reject calls. |
| `frontend/src/views/AgentView.vue` | Bind existing stream/proposal state to the new lifecycle and components; preserve resource/inspector content. |
| `frontend/src/assets/styles.css` | Add a final, scoped-by-class workspace layer and narrow-screen rules without rewriting unrelated legacy style blocks. |

### Task 1: Establish lifecycle tests and the pure task-state contract

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/src/features/task-workspace/taskLifecycle.ts`
- Create: `frontend/src/features/task-workspace/taskLifecycle.test.ts`

**Interfaces:**
- Produces `TaskPhase`, `TaskLifecycleInput`, `resolveTaskPhase`, `taskPhaseCopy`, `isToolProposalTerminal`, and `summarizeToolProposal`.
- Consumes `RuntimeToolProposal` from `frontend/src/types.ts`; no Vue refs, timers, browser globals, HTTP calls, or component imports are allowed in this module.
- Later tasks consume the exported phase, copy, terminal predicate, and summary function unchanged.

- [ ] **Step 1: Add the test runner and failing lifecycle tests.**

  Run from `frontend/`:

  ```bash
  npm install --save-dev vitest
  npm pkg set scripts.test="vitest run"
  npm pkg set scripts.test:watch="vitest"
  ```

  Create `frontend/src/features/task-workspace/taskLifecycle.test.ts` with these executable expectations:

  ```ts
  import { describe, expect, it } from 'vitest';
  import { resolveTaskPhase, summarizeToolProposal } from './taskLifecycle';

  describe('resolveTaskPhase', () => {
    const base = {
      authenticated: true,
      taskStarted: true,
      hasProgress: false,
      streamActive: false,
      streamFinished: false,
      streamFailed: false,
      stoppedByUser: false,
      hasPendingAction: false,
      hasPendingToolAction: false,
      normalActionOutcome: 'none' as const,
      toolProposal: null,
      toolMonitorUnknown: false
    };

    it('starts in preparing before the first trace or token', () => {
      expect(resolveTaskPhase(base)).toBe('preparing');
    });

    it('puts either approval ahead of a completed stream', () => {
      expect(resolveTaskPhase({ ...base, streamFinished: true, hasPendingToolAction: true })).toBe('awaiting_approval');
    });

    it('does not mark an executing approved tool complete merely because its original stream ended', () => {
      expect(resolveTaskPhase({
        ...base,
        streamFinished: true,
        toolProposal: { proposalId: 'tip-1', status: 'EXECUTING', toolName: 'write', targetPaths: [] }
      })).toBe('executing_approved_tool');
    });

    it('maps every terminal tool proposal to a truthful user terminal state', () => {
      expect(resolveTaskPhase({ ...base, toolProposal: { proposalId: 'tip-1', status: 'EXECUTED', toolName: 'write', targetPaths: [] } })).toBe('completed');
      expect(resolveTaskPhase({ ...base, toolProposal: { proposalId: 'tip-1', status: 'FAILED', toolName: 'write', targetPaths: [] } })).toBe('failed');
      expect(resolveTaskPhase({ ...base, toolProposal: { proposalId: 'tip-1', status: 'REJECTED', toolName: 'write', targetPaths: [] } })).toBe('cancelled');
    });

    it('keeps a missing tool result explicit instead of reporting success', () => {
      expect(resolveTaskPhase({ ...base, toolMonitorUnknown: true })).toBe('status_unknown');
    });
  });

  describe('summarizeToolProposal', () => {
    it('reads the structured execution envelope without exposing internal JSON', () => {
      const summary = summarizeToolProposal({
        proposalId: 'tip-1', status: 'EXECUTED', toolName: 'workspaceWriteFile', targetPaths: ['src/A.java'],
        executionResult: JSON.stringify({
          schema: 'springclaw.tool-execution-result.v1', success: true, noOp: false,
          result: 'written', changedFiles: ['src/A.java'], gitCommitSha: 'abc1234'
        })
      });
      expect(summary).toContain('written');
      expect(summary).toContain('1 个文件');
      expect(summary).not.toContain('abc1234');
    });

    it('uses the server failure text and never changes FAILED into a success message', () => {
      expect(summarizeToolProposal({
        proposalId: 'tip-2', status: 'FAILED', toolName: 'workspaceWriteFile', targetPaths: [], executionError: '路径越界'
      })).toBe('执行失败：路径越界');
    });
  });
  ```

- [ ] **Step 2: Run the test and verify it fails because the module does not exist.**

  Run:

  ```bash
  npm test -- --run src/features/task-workspace/taskLifecycle.test.ts
  ```

  Expected: a failed test-file load with `Failed to load url ./taskLifecycle` or equivalent missing-module error.

- [ ] **Step 3: Implement the pure lifecycle module.**

  Create `frontend/src/features/task-workspace/taskLifecycle.ts` with the following public contract. Keep `RuntimeToolProposal` optional fields defensive because SSE fallback payloads can be incomplete.

  ```ts
  import type { RuntimeToolProposal } from '../../types';

  export type TaskPhase =
    | 'idle'
    | 'preparing'
    | 'running'
    | 'awaiting_approval'
    | 'executing_approved_tool'
    | 'completed'
    | 'failed'
    | 'cancelled'
    | 'status_unknown';

  export type NormalActionOutcome = 'none' | 'confirmed' | 'cancelled';

  export type TaskLifecycleInput = {
    authenticated: boolean;
    taskStarted: boolean;
    hasProgress: boolean;
    streamActive: boolean;
    streamFinished: boolean;
    streamFailed: boolean;
    stoppedByUser: boolean;
    hasPendingAction: boolean;
    hasPendingToolAction: boolean;
    normalActionOutcome: NormalActionOutcome;
    toolProposal: Pick<RuntimeToolProposal, 'proposalId' | 'status' | 'toolName' | 'targetPaths' | 'executionResult' | 'executionError' | 'gitChangedFiles'> | null;
    toolMonitorUnknown: boolean;
  };

  const cancelledToolStatuses = new Set(['REJECTED', 'EXPIRED', 'CANCELLED']);

  export function isToolProposalTerminal(status?: string) {
    return ['EXECUTED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes((status || '').toUpperCase());
  }

  export function resolveTaskPhase(input: TaskLifecycleInput): TaskPhase {
    const toolStatus = input.toolProposal?.status?.toUpperCase();
    if (input.hasPendingAction || input.hasPendingToolAction) return 'awaiting_approval';
    if (input.toolMonitorUnknown) return 'status_unknown';
    if (toolStatus === 'EXECUTED') return 'completed';
    if (toolStatus === 'FAILED') return 'failed';
    if (cancelledToolStatuses.has(toolStatus || '')) return 'cancelled';
    if (toolStatus === 'EXECUTING' || toolStatus === 'APPROVED') return 'executing_approved_tool';
    if (input.normalActionOutcome === 'cancelled' || input.stoppedByUser) return 'cancelled';
    if (input.streamFailed) return 'failed';
    if (input.normalActionOutcome === 'confirmed') return 'completed';
    if (input.streamFinished) return 'completed';
    if (!input.taskStarted) return 'idle';
    if (input.streamActive && !input.hasProgress) return 'preparing';
    if (input.streamActive || input.hasProgress) return 'running';
    return input.authenticated ? 'idle' : 'idle';
  }

  export function taskPhaseCopy(phase: TaskPhase) {
    const copy: Record<TaskPhase, { title: string; detail: string }> = {
      idle: { title: '可以开始新任务', detail: '描述你希望 Agent 完成的事情。' },
      preparing: { title: '正在理解任务', detail: '正在准备上下文和执行路径。' },
      running: { title: '正在处理任务', detail: 'Agent 正在分析、调用能力或整理结果。' },
      awaiting_approval: { title: '需要你的确认', detail: '确认前不会执行风险操作。' },
      executing_approved_tool: { title: '已确认，正在安全执行', detail: '正在等待工具返回真实执行结果。' },
      completed: { title: '任务已完成', detail: '结果已保留在当前会话中。' },
      failed: { title: '任务未完成', detail: '请查看错误说明后重试或重新发起任务。' },
      cancelled: { title: '操作未执行', detail: '本次风险操作已被拒绝、取消或过期。' },
      status_unknown: { title: '暂时无法确认最终状态', detail: '系统不会把未知状态当作成功；可以重新查询。' }
    };
    return copy[phase];
  }

  export function summarizeToolProposal(proposal: TaskLifecycleInput['toolProposal']) {
    if (!proposal) return '';
    if (proposal.status?.toUpperCase() === 'FAILED') return `执行失败：${proposal.executionError || '服务端没有返回错误说明。'}`;
    if (proposal.status?.toUpperCase() !== 'EXECUTED') return '';
    const changedFiles = proposal.gitChangedFiles || proposal.targetPaths || [];
    const changedSuffix = changedFiles.length ? `，影响 ${changedFiles.length} 个文件` : '';
    try {
      const parsed = JSON.parse(proposal.executionResult || '{}') as { result?: unknown; noOp?: boolean; resultTruncated?: boolean };
      const value = typeof parsed.result === 'string' ? parsed.result : parsed.result == null ? '' : JSON.stringify(parsed.result);
      if (parsed.noOp) return `操作已完成，未产生文件变更${changedSuffix}。`;
      if (value) return `操作已完成：${value}${changedSuffix}。${parsed.resultTruncated ? ' 结果已截断，请在开发者详情查看完整记录。' : ''}`;
    } catch {
      if (proposal.executionResult?.trim()) return `操作已完成：${proposal.executionResult.trim().slice(0, 240)}${changedSuffix}。`;
    }
    return `操作已完成${changedSuffix}。`;
  }
  ```

- [ ] **Step 4: Run the lifecycle tests and type check.**

  Run:

  ```bash
  npm test -- --run src/features/task-workspace/taskLifecycle.test.ts
  npm run typecheck
  ```

  Expected: `1 passed` test file with all lifecycle assertions green, then `vue-tsc --noEmit` exits 0.

- [ ] **Step 5: Commit the test foundation.**

  ```bash
  git add frontend/package.json frontend/package-lock.json frontend/src/features/task-workspace/taskLifecycle.ts frontend/src/features/task-workspace/taskLifecycle.test.ts
  git commit -m "test: cover task workspace lifecycle"
  ```

### Task 2: Add bounded, testable monitoring for asynchronous tool proposals

**Files:**
- Modify: `frontend/src/services/api.ts`
- Create: `frontend/src/composables/useToolProposalMonitor.ts`
- Create: `frontend/src/composables/useToolProposalMonitor.test.ts`

**Interfaces:**
- Consumes `GET /api/tool-proposals/{proposalId}`, already provided by `ToolProposalController`.
- Produces `proposal`, `unknown`, `polling`, `start`, `refresh`, `stop`, and `clear` from `useToolProposalMonitor`.
- `start(proposalId, initialProposal?)` monitors only one proposal; it replaces and stops any previous monitor.
- Later `AgentView` code calls `start` only after `confirmToolProposal` returns `EXECUTING`, and calls `stop` on a new task, new session, and component unmount.

- [ ] **Step 1: Write failing monitor tests using fake timers.**

  Create `frontend/src/composables/useToolProposalMonitor.test.ts`:

  ```ts
  import { beforeEach, describe, expect, it, vi } from 'vitest';
  import { effectScope, nextTick } from 'vue';
  import { useToolProposalMonitor } from './useToolProposalMonitor';

  const executing = { proposalId: 'tip-1', status: 'EXECUTING', toolName: 'write', targetPaths: [] };
  const executed = { ...executing, status: 'EXECUTED', executionResult: 'written' };

  describe('useToolProposalMonitor', () => {
    beforeEach(() => vi.useFakeTimers());

    it('polls an executing proposal and stops when the server reports EXECUTED', async () => {
      const fetchProposal = vi.fn().mockResolvedValueOnce(executing).mockResolvedValueOnce(executed);
      const scope = effectScope();
      const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, { intervalMs: 1000, maxElapsedMs: 5000, maxFailures: 3 }))!;
      await monitor.start('tip-1', executing);
      await vi.advanceTimersByTimeAsync(1000);
      expect(fetchProposal).toHaveBeenCalledWith('tip-1');
      expect(monitor.proposal.value?.status).toBe('EXECUTED');
      expect(monitor.polling.value).toBe(false);
      scope.stop();
    });

    it('reports unknown only after the configured consecutive failures', async () => {
      const fetchProposal = vi.fn().mockRejectedValue(new Error('network'));
      const scope = effectScope();
      const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, { intervalMs: 1000, maxElapsedMs: 5000, maxFailures: 3 }))!;
      await monitor.start('tip-1', executing);
      await vi.advanceTimersByTimeAsync(3000);
      expect(monitor.unknown.value).toBe(true);
      expect(monitor.polling.value).toBe(false);
      scope.stop();
    });

    it('reports unknown when an executing proposal exceeds its bounded wait time', async () => {
      const fetchProposal = vi.fn().mockResolvedValue(executing);
      const scope = effectScope();
      const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, { intervalMs: 1000, maxElapsedMs: 2000, maxFailures: 3 }))!;
      await monitor.start('tip-1', executing);
      await vi.advanceTimersByTimeAsync(2000);
      expect(monitor.unknown.value).toBe(true);
      expect(monitor.polling.value).toBe(false);
      scope.stop();
    });

    it('cleans its interval when the owner scope stops', async () => {
      const fetchProposal = vi.fn().mockResolvedValue(executing);
      const scope = effectScope();
      const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, { intervalMs: 1000, maxElapsedMs: 5000, maxFailures: 3 }))!;
      await monitor.start('tip-1', executing);
      fetchProposal.mockClear();
      scope.stop();
      await vi.advanceTimersByTimeAsync(3000);
      expect(fetchProposal).not.toHaveBeenCalled();
    });
  });
  ```

- [ ] **Step 2: Verify the monitor test fails.**

  Run:

  ```bash
  npm test -- --run src/composables/useToolProposalMonitor.test.ts
  ```

  Expected: missing-module failure for `./useToolProposalMonitor`.

- [ ] **Step 3: Implement the API wrapper and monitor.**

  In `frontend/src/services/api.ts`, add and use the actual proposal DTO:

  ```ts
  export function getToolProposal(proposalId: string) {
    return request<RuntimeToolProposal>(`/api/tool-proposals/${encodeURIComponent(proposalId)}`);
  }

  export function confirmToolProposal(proposalId: string, reason = '用户确认执行') {
    return request<RuntimeToolProposal>(`/api/tool-proposals/${encodeURIComponent(proposalId)}/confirm`, {
      method: 'POST', body: JSON.stringify({ reason })
    });
  }

  export function rejectToolProposal(proposalId: string, reason = '用户拒绝执行') {
    return request<RuntimeToolProposal>(`/api/tool-proposals/${encodeURIComponent(proposalId)}/reject`, {
      method: 'POST', body: JSON.stringify({ reason })
    });
  }
  ```

  Create `frontend/src/composables/useToolProposalMonitor.ts` with one interval and no overlapping request:

  ```ts
  import { onScopeDispose, ref } from 'vue';
  import type { RuntimeToolProposal } from '../types';
  import { isToolProposalTerminal } from '../features/task-workspace/taskLifecycle';

  type FetchProposal = (proposalId: string) => Promise<RuntimeToolProposal>;
  type MonitorOptions = { intervalMs?: number; maxElapsedMs?: number; maxFailures?: number };

  export function useToolProposalMonitor(fetchProposal: FetchProposal, options: MonitorOptions = {}) {
    const intervalMs = options.intervalMs ?? 1000;
    const maxElapsedMs = options.maxElapsedMs ?? 5 * 60 * 1000;
    const maxFailures = options.maxFailures ?? 3;
    const proposal = ref<RuntimeToolProposal | null>(null);
    const polling = ref(false);
    const unknown = ref(false);
    let timer: number | undefined;
    let proposalId = '';
    let startedAt = 0;
    let failures = 0;
    let inFlight = false;

    function stop() {
      if (timer) window.clearInterval(timer);
      timer = undefined;
      polling.value = false;
      inFlight = false;
    }

    function clear() {
      stop();
      proposalId = '';
      startedAt = 0;
      failures = 0;
      unknown.value = false;
      proposal.value = null;
    }

    async function refresh() {
      if (!proposalId || inFlight) return proposal.value;
      inFlight = true;
      try {
        const next = await fetchProposal(proposalId);
        proposal.value = next;
        failures = 0;
        if (isToolProposalTerminal(next.status)) stop();
        return next;
      } catch {
        failures += 1;
        if (failures >= maxFailures) {
          unknown.value = true;
          stop();
        }
        return proposal.value;
      } finally {
        inFlight = false;
      }
    }

    async function start(nextProposalId: string, initialProposal?: RuntimeToolProposal) {
      clear();
      proposalId = nextProposalId;
      proposal.value = initialProposal || null;
      startedAt = Date.now();
      if (!proposalId || (initialProposal && isToolProposalTerminal(initialProposal.status))) return proposal.value;
      polling.value = true;
      timer = window.setInterval(() => {
        if (Date.now() - startedAt >= maxElapsedMs) {
          unknown.value = true;
          stop();
          return;
        }
        void refresh();
      }, intervalMs);
      return refresh();
    }

    onScopeDispose(stop);
    return { proposal, polling, unknown, start, refresh, stop, clear };
  }
  ```

- [ ] **Step 4: Run monitor and lifecycle tests.**

  Run:

  ```bash
  npm test -- --run src/composables/useToolProposalMonitor.test.ts src/features/task-workspace/taskLifecycle.test.ts
  npm run typecheck
  ```

  Expected: both test files pass and `vue-tsc --noEmit` exits 0.

- [ ] **Step 5: Commit proposal monitoring.**

  ```bash
  git add frontend/src/services/api.ts frontend/src/composables/useToolProposalMonitor.ts frontend/src/composables/useToolProposalMonitor.test.ts
  git commit -m "feat: monitor approved tool proposals"
  ```

### Task 3: Create small, accessible task-workspace presentation components

**Files:**
- Modify: `frontend/src/components/LoginPanel.vue`
- Create: `frontend/src/components/task/TaskStatusCard.vue`
- Create: `frontend/src/components/task/TaskApprovalCard.vue`
- Create: `frontend/src/components/task/DeveloperDetailsToggle.vue`
- Create: `frontend/src/components/task/TaskLoginGate.vue`

**Interfaces:**
- `LoginPanel` emits `authenticated` after `auth.signIn` resolves.
- `TaskStatusCard` consumes `TaskPhase`, status copy, elapsed label, result text and emits `retry`, `refresh-status`, and `open-details`.
- `TaskApprovalCard` consumes sanitized action/tool details and emits `approve` or `reject`; it never receives a proposal ID for rendering.
- `DeveloperDetailsToggle` owns only the accessible toggle UI; existing inspector markup remains under `AgentView` control.
- `TaskLoginGate` renders `LoginPanel` and emits `authenticated`; the parent-owned `input` ref remains the sole draft storage.

- [ ] **Step 1: Write the components with explicit props and emits.**

  Add this success emit immediately after the successful `auth.signIn` call in `frontend/src/components/LoginPanel.vue`:

  ```ts
  const emit = defineEmits<{ authenticated: [] }>();

  // inside submit, directly after password.value = ''
  emit('authenticated');
  ```

  Create `TaskStatusCard.vue` with an assertive user-state region but do not use color as its sole signal:

  ```vue
  <script setup lang="ts">
  import type { TaskPhase } from '../../features/task-workspace/taskLifecycle';
  defineProps<{ phase: TaskPhase; title: string; detail: string; elapsedLabel: string; result: string; canRetry: boolean; canRefreshStatus: boolean }>();
  const emit = defineEmits<{ retry: []; refreshStatus: []; openDetails: [] }>();
  </script>
  <template>
    <section class="task-status-card" :class="`is-${phase}`" aria-live="polite" aria-atomic="true">
      <div class="task-status-card__copy">
        <span class="task-status-card__phase">{{ phase.replaceAll('_', ' ') }}</span>
        <h3>{{ title }}</h3><p>{{ result || detail }}</p>
        <small v-if="elapsedLabel">已用时 {{ elapsedLabel }}</small>
      </div>
      <div class="task-status-card__actions">
        <button v-if="canRetry" class="btn-subtle" type="button" @click="emit('retry')">重新发起</button>
        <button v-if="canRefreshStatus" class="btn-subtle" type="button" @click="emit('refreshStatus')">重新查询状态</button>
        <button class="btn-subtle" type="button" @click="emit('openDetails')">开发者详情</button>
      </div>
    </section>
  </template>
  ```

  Create `TaskApprovalCard.vue` with no internal proposal identifier in its template:

  ```vue
  <script setup lang="ts">
  defineProps<{ title: string; summary: string; riskLevel: string; targetPaths: string[]; submitting: boolean; approveLabel: string }>();
  const emit = defineEmits<{ approve: []; reject: [] }>();
  </script>
  <template>
    <section class="task-approval-card" aria-live="assertive" aria-atomic="true">
      <div><span class="risk-badge">{{ riskLevel }}</span><h3>{{ title }}</h3><p>{{ summary }}</p>
        <p v-if="targetPaths.length" class="task-approval-card__impact">影响范围：{{ targetPaths.join('、') }}</p>
        <small>确认前不会执行。</small>
      </div>
      <div class="task-approval-card__actions">
        <button class="btn-subtle light" type="button" :disabled="submitting" @click="emit('reject')">拒绝</button>
        <button class="btn-primary" type="button" :disabled="submitting" @click="emit('approve')">{{ approveLabel }}</button>
      </div>
    </section>
  </template>
  ```

  Create `DeveloperDetailsToggle.vue` and `TaskLoginGate.vue`:

  ```vue
  <!-- DeveloperDetailsToggle.vue -->
  <script setup lang="ts">defineProps<{ open: boolean; summary: string }>(); const emit = defineEmits<{ toggle: [] }>();</script>
  <template><button class="developer-details-toggle" type="button" :aria-expanded="open" @click="emit('toggle')">{{ open ? '收起开发者详情' : '查看开发者详情' }}<small>{{ summary }}</small></button></template>
  ```

  ```vue
  <!-- TaskLoginGate.vue -->
  <script setup lang="ts">import LoginPanel from '../LoginPanel.vue'; defineProps<{ hasDraft: boolean }>(); const emit = defineEmits<{ authenticated: [] }>();</script>
  <template><section class="task-login-gate" aria-label="登录后执行任务"><p class="eyebrow">开始前</p><h3>登录后即可执行任务</h3><p>{{ hasDraft ? '你已输入的任务会保留，登录后由你决定何时发送。' : '登录后可以提交任务、查看执行状态和确认风险操作。' }}</p><LoginPanel @authenticated="emit('authenticated')" /></section></template>
  ```

- [ ] **Step 2: Type check the components before integration.**

  Run:

  ```bash
  npm run typecheck
  npm run build
  ```

  Expected: both commands exit 0; no component is yet imported, but every declared prop/event is checked.

- [ ] **Step 3: Commit the presentational boundary.**

  ```bash
  git add frontend/src/components/LoginPanel.vue frontend/src/components/task
  git commit -m "feat: add task workspace components"
  ```

### Task 4: Integrate the lifecycle, login gate, approval card, and real tool result into AgentView

**Files:**
- Modify: `frontend/src/views/AgentView.vue`

**Interfaces:**
- Consumes all exports from Tasks 1–3 and existing `streamChat`, action confirmation APIs, tool proposal APIs, message state, trace state, and inspector state.
- Produces one task lifecycle projection for the default `console` view; resource navigation and existing developer-inspector data remain intact.
- A new outgoing stream resets only task-lifecycle state and proposal monitoring; it must not erase the login draft on authentication failure or historical messages.

- [ ] **Step 1: Add the lifecycle state and computed projection at the top of `AgentView.vue`.**

  Add these imports beside the existing imports:

  ```ts
  import TaskStatusCard from '../components/task/TaskStatusCard.vue';
  import TaskApprovalCard from '../components/task/TaskApprovalCard.vue';
  import DeveloperDetailsToggle from '../components/task/DeveloperDetailsToggle.vue';
  import TaskLoginGate from '../components/task/TaskLoginGate.vue';
  import { taskPhaseCopy, resolveTaskPhase, summarizeToolProposal, type NormalActionOutcome } from '../features/task-workspace/taskLifecycle';
  import { useToolProposalMonitor } from '../composables/useToolProposalMonitor';
  ```

  Add `getToolProposal` to the existing API import list, then add the following local state after the current stream refs:

  ```ts
  const taskStartedAt = ref<number | null>(null);
  const taskHasProgress = ref(false);
  const taskStreamFinished = ref(false);
  const taskStreamFailed = ref(false);
  const taskStoppedByUser = ref(false);
  const normalActionOutcome = ref<NormalActionOutcome>('none');
  const normalActionResult = ref('');
  const approvalSubmitting = ref<'none' | 'action' | 'tool'>('none');
  const toolProposalMonitor = useToolProposalMonitor(getToolProposal);

  const taskPhase = computed(() => resolveTaskPhase({
    authenticated: Boolean(auth.profile), taskStarted: taskStartedAt.value != null,
    hasProgress: taskHasProgress.value, streamActive: busy.value,
    streamFinished: taskStreamFinished.value, streamFailed: taskStreamFailed.value,
    stoppedByUser: taskStoppedByUser.value, hasPendingAction: Boolean(pendingAction.value),
    hasPendingToolAction: Boolean(pendingToolAction.value), normalActionOutcome: normalActionOutcome.value,
    toolProposal: toolProposalMonitor.proposal.value, toolMonitorUnknown: toolProposalMonitor.unknown.value
  }));
  const taskCopy = computed(() => taskPhaseCopy(taskPhase.value));
  const taskElapsedLabel = computed(() => taskStartedAt.value == null ? '' : `${elapsedSeconds.value}s`);
  const taskResult = computed(() => normalActionResult.value || summarizeToolProposal(toolProposalMonitor.proposal.value));
  const taskInputLocked = computed(() => ['awaiting_approval', 'executing_approved_tool'].includes(taskPhase.value));
  const composerDisabledReason = computed(() => taskPhase.value === 'awaiting_approval' ? '请先完成当前风险操作的确认或拒绝。' : taskPhase.value === 'executing_approved_tool' ? '工具正在执行，等待真实结果后再开始新任务。' : '');
  ```

  Replace the existing `canSend` computed with:

  ```ts
  const canSend = computed(() => Boolean(auth.profile && input.value.trim() && !busy.value && !taskInputLocked.value));
  ```

- [ ] **Step 2: Reset and feed the lifecycle from every existing stream event.**

  Add one helper before `send()` and call it at the start of `send()` before `busy.value = true`:

  ```ts
  function resetTaskLifecycle(startedAt: number | null) {
    toolProposalMonitor.clear();
    taskStartedAt.value = startedAt;
    taskHasProgress.value = false;
    taskStreamFinished.value = false;
    taskStreamFailed.value = false;
    taskStoppedByUser.value = false;
    normalActionOutcome.value = 'none';
    normalActionResult.value = '';
    approvalSubmitting.value = 'none';
  }
  ```

  In `send()`, replace the current `const startedAt = Date.now();` section with:

  ```ts
  const startedAt = Date.now();
  resetTaskLifecycle(startedAt);
  ```

  Update stream handlers so status is never inferred solely from trace length:

  ```ts
  onDecision(decision) { taskHasProgress.value = true; agentDecision.value = decision; streamStatus.value = `正在判断任务处理方式。`; },
  onTrace(event) { taskHasProgress.value = true; pushTraceEvent(event); if (event.detail) streamStatus.value = event.detail; },
  onToolCall(event) { taskHasProgress.value = true; pushCapabilityEvent(event); },
  onSkillCall(event) { taskHasProgress.value = true; pushCapabilityEvent(event); },
  onVerification(event) { taskHasProgress.value = true; verificationEvent.value = event; },
  onActionRequired(proposal) { pendingAction.value = proposal; actionStatus.value = '等待你的确认，确认前不会执行。'; },
  onToolActionRequired(proposal) { pendingToolAction.value = proposal; toolActionStatus.value = '等待你的确认，确认前不会执行。'; },
  onToken(token) {
    taskHasProgress.value = true;
    if (firstTokenMs.value == null) firstTokenMs.value = Date.now() - startedAt;
    appendMessageContent(agentMessageId, token);
    scheduleScrollBottom();
  },
  onError(message) {
    taskStreamFailed.value = true;
    const current = getMessageContent(agentMessageId);
    setMessageContent(agentMessageId, current ? `${current}\n${message}` : message);
    void loadModelStatus();
  },
  onDone() { taskStreamFinished.value = true; streamStatus.value = '已完成'; }
  ```

  In the `catch` branch set the persistent terminal state before changing the visible message:

  ```ts
  if (stoppingStream.value) {
    taskStoppedByUser.value = true;
    taskStreamFinished.value = false;
    const current = getMessageContent(agentMessageId).trim();
    setMessageContent(agentMessageId, current ? `${current}\n\n已停止生成。` : '已停止生成。');
  } else {
    taskStreamFailed.value = true;
    setMessageContent(agentMessageId, `请求失败：${error instanceof Error ? error.message : '未知错误'}`);
    void loadModelStatus();
  }
  ```

  Do not reset `taskStoppedByUser` in `finally`; `resetTaskLifecycle()` resets it only when a new task or session begins.

  In `newSession()` call `resetTaskLifecycle(null)`. In `onUnmounted()` call `toolProposalMonitor.stop()` before persisting messages.

- [ ] **Step 3: Make confirmations produce the correct lifecycle outcomes.**

  Replace the four current confirmation functions with the following bodies. Each function owns its submission guard, so a slow endpoint cannot create duplicate safety decisions:

  ```ts
  async function confirmPendingAction() {
    if (!pendingAction.value || busy.value || approvalSubmitting.value !== 'none') return;
    approvalSubmitting.value = 'action';
    actionStatus.value = '正在确认执行…';
    try {
      const result = await confirmActionProposal(pendingAction.value.proposalId, sessionKey.value);
      normalActionOutcome.value = 'confirmed';
      normalActionResult.value = result.message || '操作已确认并完成。';
      actionStatus.value = normalActionResult.value;
      addMessage('agent', normalActionResult.value);
      pendingAction.value = null;
      await scrollBottom();
    } catch (error) {
      actionStatus.value = error instanceof Error ? error.message : '确认失败。';
    } finally {
      approvalSubmitting.value = 'none';
    }
  }

  async function cancelPendingAction() {
    if (!pendingAction.value || busy.value || approvalSubmitting.value !== 'none') return;
    approvalSubmitting.value = 'action';
    actionStatus.value = '正在取消…';
    try {
      const result = await cancelActionProposal(pendingAction.value.proposalId);
      normalActionOutcome.value = 'cancelled';
      normalActionResult.value = result.message || '已取消，未执行任何动作。';
      actionStatus.value = normalActionResult.value;
      addMessage('system', normalActionResult.value);
      pendingAction.value = null;
      await scrollBottom();
    } catch (error) {
      actionStatus.value = error instanceof Error ? error.message : '取消失败。';
    } finally {
      approvalSubmitting.value = 'none';
    }
  }

  async function confirmPendingToolAction() {
    if (!pendingToolAction.value || busy.value || approvalSubmitting.value !== 'none') return;
    if (!pendingToolAction.value.proposalId) { toolActionStatus.value = '确认失败：缺少确认单标识，请重新发起请求。'; return; }
    approvalSubmitting.value = 'tool';
    toolActionStatus.value = '正在确认工具调用…';
    try {
      const proposal = await confirmToolProposal(pendingToolAction.value.proposalId, '用户确认执行工具调用');
      toolActionStatus.value = '已确认，正在等待真实执行结果。';
      pendingToolAction.value = null;
      await toolProposalMonitor.start(proposal.proposalId, proposal);
      await scrollBottom();
    } catch (error) {
      toolActionStatus.value = error instanceof Error ? error.message : '确认工具调用失败。';
    } finally {
      approvalSubmitting.value = 'none';
    }
  }

  async function rejectPendingToolAction() {
    if (!pendingToolAction.value || busy.value || approvalSubmitting.value !== 'none') return;
    if (!pendingToolAction.value.proposalId) { toolActionStatus.value = '拒绝失败：缺少确认单标识，请重新发起请求。'; return; }
    approvalSubmitting.value = 'tool';
    toolActionStatus.value = '正在拒绝工具调用…';
    try {
      const proposal = await rejectToolProposal(pendingToolAction.value.proposalId, '用户拒绝执行工具调用');
      toolProposalMonitor.clear();
      await toolProposalMonitor.start(proposal.proposalId, proposal);
      toolActionStatus.value = '已拒绝，风险操作没有执行。';
      pendingToolAction.value = null;
      await scrollBottom();
    } catch (error) {
      toolActionStatus.value = error instanceof Error ? error.message : '拒绝工具调用失败。';
    } finally {
      approvalSubmitting.value = 'none';
    }
  }

  async function refreshToolProposalStatus() {
    const latest = await toolProposalMonitor.refresh();
    toolActionStatus.value = latest ? `当前状态：${latest.status}。` : '暂时无法取得工具状态，请稍后重新查询。';
  }
  ```

- [ ] **Step 4: Replace default-console markup with the task-workspace pieces.**

  Inside the existing `activeResourceView === 'console'` branch, place the following immediately before `runtime-chat-log`:

  ```vue
  <TaskStatusCard
    v-if="taskStartedAt != null || !auth.profile"
    :phase="taskPhase" :title="taskCopy.title" :detail="composerDisabledReason || taskCopy.detail"
    :elapsed-label="taskElapsedLabel" :result="taskResult" :can-retry="taskPhase === 'failed' || taskPhase === 'cancelled'"
    :can-refresh-status="taskPhase === 'status_unknown'" @retry="retryLastPrompt"
    @refresh-status="refreshToolProposalStatus" @open-details="toggleRuntimeInspector"
  />
  <TaskLoginGate v-if="!auth.profile" :has-draft="Boolean(input.trim())" @authenticated="focusComposer" />
  ```

  Replace both raw `action-required-card` blocks inside `confirmation-tray` with the following components. Do not pass proposal IDs into either template:

  ```vue
  <TaskApprovalCard
    v-if="pendingAction" :title="pendingAction.title || '需要确认的操作'" :summary="pendingAction.summary"
    :risk-level="pendingAction.riskLevel" :target-paths="[]" :submitting="busy || approvalSubmitting === 'action'" approve-label="确认执行"
    @approve="confirmPendingAction" @reject="cancelPendingAction"
  />
  <TaskApprovalCard
    v-if="pendingToolAction" title="需要确认的工具操作"
    :summary="pendingToolAction.previewSummary || pendingToolAction.toolName" :risk-level="pendingToolAction.riskLevel"
    :target-paths="pendingToolAction.targetPaths" :submitting="busy || approvalSubmitting === 'tool'" approve-label="确认执行"
    @approve="confirmPendingToolAction" @reject="rejectPendingToolAction"
  />
  ```

  Replace the composer’s `RouterLink` login prompt with a plain explanatory notice, keep the draft-bound textarea editable before login, set `:aria-describedby="composerDisabledReason ? 'composer-task-lock-reason' : undefined"`, and add a visible `<p id="composer-task-lock-reason">{{ composerDisabledReason }}</p>` only when the task input is locked. Add `:disabled="taskInputLocked"` to the textarea and disable all quick-prompt buttons when `taskInputLocked` is true.

  Replace the raw `runtime-debug-summary` button with:

  ```vue
  <DeveloperDetailsToggle
    v-if="hasRunDetails" :open="inspectorDrawerOpen"
    :summary="`${runCurrentStepLabel} · ${runDurationLabel}`" @toggle="toggleRuntimeInspector"
  />
  ```

  Retain the existing inspector `<aside v-if="showRuntimeInspector">` and all its tabs/data. It is now reachable only through this explicit toggle, so it is no longer a default competing surface.

- [ ] **Step 5: Verify integration before visual styling.**

  Run:

  ```bash
  npm test -- --run src/features/task-workspace/taskLifecycle.test.ts src/composables/useToolProposalMonitor.test.ts
  npm run typecheck
  npm run build
  ```

  Expected: both unit suites pass, `vue-tsc` exits 0, and Vite produces `frontend/dist/` without type or template errors.

- [ ] **Step 6: Commit the integrated task path.**

  ```bash
  git add frontend/src/views/AgentView.vue
  git commit -m "feat: focus agent on safe task workspace"
  ```

### Task 5: Apply responsive, accessible workspace styling without disturbing resource pages

**Files:**
- Modify: `frontend/src/assets/styles.css`
- Modify: `frontend/src/composables/useAgentGsapMotion.ts`

**Interfaces:**
- New styles are scoped under `.runtime-console` plus `.task-*`/`.developer-details-toggle` selectors.
- Existing `.runtime-inspector`, `.runtime-chat-log`, and `.runtime-composer` selectors retain their roles; no global theme or unrelated Home/Admin rules are rewritten.
- GSAP continues to animate existing chat/inspector selectors, and also reveals `TaskStatusCard`/`TaskApprovalCard` only when reduced motion is not requested.

- [ ] **Step 1: Add the workspace style layer at the end of `styles.css`.**

  Append this complete selector group, then tune only dimensions proven wrong during browser QA:

  ```css
  .runtime-console .task-status-card,
  .runtime-console .task-login-gate,
  .runtime-console .task-approval-card {
    margin: 12px clamp(14px, 3vw, 32px) 0;
    border: 1px solid var(--line);
    border-radius: 12px;
    background: #121720;
  }
  .runtime-console .task-status-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 16px; align-items: center; padding: 14px 16px; }
  .runtime-console .task-status-card__phase { color: var(--amber); font: 760 11px/1 var(--font); letter-spacing: .1em; text-transform: uppercase; }
  .runtime-console .task-status-card h3, .runtime-console .task-approval-card h3, .runtime-console .task-login-gate h3 { margin: 6px 0; color: var(--ink); font-size: 16px; }
  .runtime-console .task-status-card p, .runtime-console .task-approval-card p, .runtime-console .task-login-gate p { margin: 0; color: var(--muted); font-size: 13px; line-height: 1.55; }
  .runtime-console .task-status-card small { display: block; margin-top: 6px; color: var(--muted); }
  .runtime-console .task-status-card__actions, .runtime-console .task-approval-card__actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
  .runtime-console .task-status-card.is-awaiting_approval, .runtime-console .task-approval-card { border-color: rgba(242, 201, 76, .56); background: rgba(242, 201, 76, .08); }
  .runtime-console .task-status-card.is-failed { border-color: rgba(255, 107, 107, .62); }
  .runtime-console .task-status-card.is-completed { border-color: rgba(74, 222, 128, .5); }
  .runtime-console .task-login-gate { display: grid; gap: 12px; padding: 16px; }
  .runtime-console .task-login-gate .login-card { max-width: 520px; margin-top: 4px; background: #0f1117; }
  .runtime-console .task-approval-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 16px; align-items: center; padding: 14px 16px; }
  .runtime-console .task-approval-card__impact { margin-top: 8px; overflow-wrap: anywhere; }
  .runtime-console .developer-details-toggle { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; width: calc(100% - clamp(28px, 6vw, 64px)); margin: 8px auto 12px; border: 1px solid rgba(13, 148, 136, .42); border-radius: 8px; padding: 9px 12px; color: #b6fff5; background: rgba(13, 148, 136, .1); text-align: left; }
  .runtime-console .developer-details-toggle small { color: #9fb2c1; text-align: right; }
  @media (max-width: 760px) {
    .runtime-console .task-status-card, .runtime-console .task-approval-card { grid-template-columns: 1fr; margin-inline: 12px; }
    .runtime-console .task-status-card__actions, .runtime-console .task-approval-card__actions { justify-content: stretch; }
    .runtime-console .task-status-card__actions > *, .runtime-console .task-approval-card__actions > * { flex: 1 1 140px; }
    .runtime-console .developer-details-toggle { width: calc(100% - 24px); }
    .runtime-console .runtime-chat-log.stitch-chat { min-height: 44dvh; padding-bottom: 18px; }
    .runtime-console .runtime-composer.stitch-composer { position: sticky; bottom: 0; z-index: 8; }
  }
  ```

- [ ] **Step 2: Update motion selectors safely.**

  In `useAgentGsapMotion.ts`, keep the reduced-motion exit unchanged and add the two new targets to `revealActionCard` by replacing its selector call with:

  ```ts
  async function revealActionCard() {
    await revealCard('.task-approval-card, .action-required-card', { y: 14, scale: 0.975 });
  }
  ```

  Add this method beside `revealActionCard` and return it from the composable:

  ```ts
  async function revealTaskStatus() {
    await revealCard('.task-status-card', { y: 10, scale: 0.985 });
  }
  ```

  In `AgentView.vue`, watch `taskPhase` and call `motion.revealTaskStatus()` only when it changes away from `idle`.

- [ ] **Step 3: Run build and inspect narrow-screen constraints.**

  Run:

  ```bash
  npm run typecheck
  npm run build
  ```

  Expected: exit 0. Then open the local frontend at 390 px and 1440 px widths; at 390 px the message area, composer and both approval buttons remain visible without opening developer details.

- [ ] **Step 4: Commit responsive workbench styling.**

  ```bash
  git add frontend/src/assets/styles.css frontend/src/composables/useAgentGsapMotion.ts frontend/src/views/AgentView.vue
  git commit -m "style: prioritize safe task workspace"
  ```

### Task 6: Verify the end-to-end user path and complete the branch

**Files:**
- Modify only if verification exposes a defect in files from Tasks 1–5.
- Do not stage `.claude/worktrees/`, `.tmp_interview_doc/`, `frontend/dist/`, browser screenshots, or runtime logs.

**Interfaces:**
- Verification proves the user-visible objective, not merely compilation: login gate, normal lifecycle, action approval, async tool approval result, failure/unknown state, opt-in details, and responsive layout.

- [ ] **Step 1: Run deterministic frontend checks.**

  Run from `frontend/`:

  ```bash
  npm test -- --run
  npm run typecheck
  npm run build
  ```

  Expected: all Vitest files pass; typecheck and build exit 0.

- [ ] **Step 2: Run the backend regression suite from the repository root.**

  ```bash
  mvn test
  ```

  Expected: Maven exits 0 with no test failures or errors. The frontend only reuses existing API contracts, but this full suite guards those contracts and safety-state behavior.

- [ ] **Step 3: Run a browser smoke test against the local application.**

  Start the backend and frontend with a disposable development configuration, then verify each observable scenario using browser automation or a real local browser:

  ```bash
  OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run
  npm --prefix frontend run dev
  ```

  Record the observed outcome for each case:

  1. Logged out: a draft can be typed, the embedded login gate is visible, clicking send does not create a user message or clear the draft, and successful login focuses the composer.
  2. Normal stream: status progresses from “正在理解任务” to “正在处理任务” to “任务已完成” or a truthful failure; elapsed time is wall-clock time, not summed trace duration.
  3. Ordinary action proposal: the task area renders title, summary, risk and confirm/reject without the proposal ID; confirmation or cancellation updates terminal status.
  4. Tool proposal: confirmation shows “正在安全执行”; the page fetches the same proposal until `EXECUTED` or `FAILED`; result/error text comes from the returned proposal; three fetch failures or five minutes produce “暂时无法确认最终状态”, never “完成”.
  5. Developer details: closed by default, can be opened explicitly, and shows existing trace/tool/memory/log content without changing task state.
  6. At 390 px width: the current message, composer and two approval buttons are visible; no inspector overlay opens unless the user explicitly chooses details.

- [ ] **Step 4: Review the final diff and working tree.**

  Run:

  ```bash
  git diff --check
  git status --short
  git diff origin/codex/flyway-schema-migration...HEAD --stat
  ```

  Expected: no whitespace errors; only intended source, test, package-lock, and documentation files are staged/committed; unrelated untracked personal/worktree directories remain untouched.

- [ ] **Step 5: Commit, push, and report verification evidence.**

  ```bash
  git add frontend/package.json frontend/package-lock.json frontend/src
  git commit -m "feat: deliver safe agent task workspace"
  git push origin codex/flyway-schema-migration
  ```

  Expected: the branch is synchronized with `origin/codex/flyway-schema-migration`; final handoff names the lifecycle tests, frontend typecheck/build, Maven suite, and browser scenarios actually run.
