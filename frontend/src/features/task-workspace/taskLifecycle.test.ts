import { describe, expect, it } from 'vitest';
import { isTaskInputLocked, resolveTaskPhase, summarizeToolProposal } from './taskLifecycle';

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
      proposalId: 'tip-1',
      status: 'EXECUTED',
      toolName: 'workspaceWriteFile',
      targetPaths: ['src/A.java'],
      executionResult: JSON.stringify({
        schema: 'springclaw.tool-execution-result.v1',
        success: true,
        noOp: false,
        result: 'written',
        changedFiles: ['src/A.java'],
        gitCommitSha: 'abc1234'
      })
    });

    expect(summary).toContain('written');
    expect(summary).toContain('1 个文件');
    expect(summary).not.toContain('abc1234');
  });

  it('uses the server failure text and never changes FAILED into a success message', () => {
    expect(summarizeToolProposal({
      proposalId: 'tip-2',
      status: 'FAILED',
      toolName: 'workspaceWriteFile',
      targetPaths: [],
      executionError: '路径越界'
    })).toBe('执行失败：路径越界');
  });
});

describe('isTaskInputLocked', () => {
  it('locks a second task while an existing stream or approval is active, but leaves login drafts editable', () => {
    expect(isTaskInputLocked({ streamActive: true, phase: 'running' })).toBe(true);
    expect(isTaskInputLocked({ streamActive: false, phase: 'awaiting_approval' })).toBe(true);
    expect(isTaskInputLocked({ streamActive: false, phase: 'executing_approved_tool' })).toBe(true);
    expect(isTaskInputLocked({ streamActive: false, phase: 'idle' })).toBe(false);
  });
});
