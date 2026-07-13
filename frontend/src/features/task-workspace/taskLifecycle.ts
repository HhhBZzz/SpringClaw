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

type ToolExecutionResult = {
  changedFiles?: unknown;
  noOp?: boolean;
  result?: unknown;
  resultTruncated?: boolean;
};

const CANCELLED_TOOL_STATUSES = new Set(['REJECTED', 'EXPIRED', 'CANCELLED']);
const TERMINAL_TOOL_STATUSES = new Set(['EXECUTED', 'FAILED', ...CANCELLED_TOOL_STATUSES]);

export function isToolProposalTerminal(status?: string) {
  return TERMINAL_TOOL_STATUSES.has(normalizeStatus(status));
}

export function isTaskInputLocked(input: Pick<TaskLifecycleInput, 'streamActive'> & { phase: TaskPhase }) {
  return input.streamActive || input.phase === 'awaiting_approval' || input.phase === 'executing_approved_tool';
}

export function resolveTaskPhase(input: TaskLifecycleInput): TaskPhase {
  const toolStatus = normalizeStatus(input.toolProposal?.status);

  if (input.hasPendingAction || input.hasPendingToolAction) return 'awaiting_approval';
  if (toolStatus === 'EXECUTED') return 'completed';
  if (toolStatus === 'FAILED') return 'failed';
  if (CANCELLED_TOOL_STATUSES.has(toolStatus)) return 'cancelled';
  if (input.toolMonitorUnknown) return 'status_unknown';
  if (toolStatus === 'EXECUTING' || toolStatus === 'APPROVED') return 'executing_approved_tool';
  if (input.normalActionOutcome === 'cancelled' || input.stoppedByUser) return 'cancelled';
  if (input.streamFailed) return 'failed';
  if (input.normalActionOutcome === 'confirmed' || input.streamFinished) return 'completed';
  if (!input.taskStarted) return 'idle';
  if (!input.hasProgress) return 'preparing';
  if (input.streamActive || input.hasProgress) return 'running';
  return 'idle';
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
  if (normalizeStatus(proposal.status) === 'FAILED') {
    return `执行失败：${proposal.executionError || '服务端没有返回错误说明。'}`;
  }
  if (normalizeStatus(proposal.status) !== 'EXECUTED') return '';

  const parsed = parseExecutionResult(proposal.executionResult);
  const changedFiles = parsed?.changedFiles.length ? parsed.changedFiles : proposal.gitChangedFiles || proposal.targetPaths || [];
  const changedSuffix = changedFiles.length ? `，影响 ${changedFiles.length} 个文件` : '';

  if (parsed?.noOp) return `操作已完成，未产生文件变更${changedSuffix}。`;
  if (parsed?.result) {
    return `操作已完成：${parsed.result}${changedSuffix}。${parsed.resultTruncated ? ' 结果已截断，请在开发者详情查看完整记录。' : ''}`;
  }
  if (proposal.executionResult?.trim() && !parsed) {
    return `操作已完成：${proposal.executionResult.trim().slice(0, 240)}${changedSuffix}。`;
  }
  return `操作已完成${changedSuffix}。`;
}

function normalizeStatus(status?: string) {
  return (status || '').trim().toUpperCase();
}

function parseExecutionResult(raw?: string): (ToolExecutionResult & { changedFiles: string[]; result: string }) | null {
  if (!raw?.trim()) return null;

  try {
    const value = JSON.parse(raw) as ToolExecutionResult;
    const changedFiles = Array.isArray(value.changedFiles)
      ? value.changedFiles.filter((file): file is string => typeof file === 'string' && file.trim().length > 0)
      : [];
    const result = typeof value.result === 'string'
      ? value.result
      : value.result == null
        ? ''
        : JSON.stringify(value.result);
    return { ...value, changedFiles, result };
  } catch {
    return null;
  }
}
