import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import { useToolProposalMonitor } from './useToolProposalMonitor';

const executing = {
  proposalId: 'tip-1',
  status: 'EXECUTING',
  toolName: 'write',
  targetPaths: []
};

const executed = {
  ...executing,
  status: 'EXECUTED',
  executionResult: 'written'
};

describe('useToolProposalMonitor', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('polls an executing proposal and stops when the server reports EXECUTED', async () => {
    const fetchProposal = vi.fn().mockResolvedValueOnce(executing).mockResolvedValueOnce(executed);
    const scope = effectScope();
    const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, {
      intervalMs: 1000,
      maxElapsedMs: 5000,
      maxFailures: 3
    }))!;

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
    const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, {
      intervalMs: 1000,
      maxElapsedMs: 5000,
      maxFailures: 3
    }))!;

    await monitor.start('tip-1', executing);
    await vi.advanceTimersByTimeAsync(3000);

    expect(monitor.unknown.value).toBe(true);
    expect(monitor.polling.value).toBe(false);
    scope.stop();
  });

  it('reports unknown when an executing proposal exceeds its bounded wait time', async () => {
    const fetchProposal = vi.fn().mockResolvedValue(executing);
    const scope = effectScope();
    const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, {
      intervalMs: 1000,
      maxElapsedMs: 2000,
      maxFailures: 3
    }))!;

    await monitor.start('tip-1', executing);
    await vi.advanceTimersByTimeAsync(2000);

    expect(monitor.unknown.value).toBe(true);
    expect(monitor.polling.value).toBe(false);
    scope.stop();
  });

  it('cleans its interval when the owner scope stops', async () => {
    const fetchProposal = vi.fn().mockResolvedValue(executing);
    const scope = effectScope();
    const monitor = scope.run(() => useToolProposalMonitor(fetchProposal, {
      intervalMs: 1000,
      maxElapsedMs: 5000,
      maxFailures: 3
    }))!;

    await monitor.start('tip-1', executing);
    fetchProposal.mockClear();
    scope.stop();
    await vi.advanceTimersByTimeAsync(3000);

    expect(fetchProposal).not.toHaveBeenCalled();
  });
});
