import { onScopeDispose, ref } from 'vue';
import { isToolProposalTerminal } from '../features/task-workspace/taskLifecycle';
import type { RuntimeToolProposal } from '../types';

type FetchProposal = (proposalId: string) => Promise<RuntimeToolProposal>;

type MonitorOptions = {
  intervalMs?: number;
  maxElapsedMs?: number;
  maxFailures?: number;
};

export function useToolProposalMonitor(fetchProposal: FetchProposal, options: MonitorOptions = {}) {
  const intervalMs = options.intervalMs ?? 1000;
  const maxElapsedMs = options.maxElapsedMs ?? 5 * 60 * 1000;
  const maxFailures = options.maxFailures ?? 3;
  const proposal = ref<RuntimeToolProposal | null>(null);
  const polling = ref(false);
  const unknown = ref(false);

  let timer: ReturnType<typeof setInterval> | undefined;
  let activeProposalId = '';
  let startedAt = 0;
  let failures = 0;
  let inFlight = false;
  let generation = 0;

  function stop() {
    if (timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
    polling.value = false;
  }

  function invalidatePendingRequest() {
    generation += 1;
    inFlight = false;
  }

  function clear() {
    invalidatePendingRequest();
    stop();
    activeProposalId = '';
    startedAt = 0;
    failures = 0;
    inFlight = false;
    unknown.value = false;
    proposal.value = null;
  }

  async function refresh() {
    if (!activeProposalId || inFlight) return proposal.value;

    const requestGeneration = generation;
    const requestProposalId = activeProposalId;
    inFlight = true;
    try {
      const next = await fetchProposal(requestProposalId);
      if (requestGeneration !== generation || requestProposalId !== activeProposalId) return proposal.value;

      proposal.value = next;
      failures = 0;
      unknown.value = false;
      if (isToolProposalTerminal(next.status)) stop();
      return next;
    } catch {
      if (requestGeneration !== generation || requestProposalId !== activeProposalId) return proposal.value;

      failures += 1;
      if (failures >= maxFailures) {
        unknown.value = true;
        stop();
      }
      return proposal.value;
    } finally {
      if (requestGeneration === generation) inFlight = false;
    }
  }

  async function start(nextProposalId: string, initialProposal?: RuntimeToolProposal) {
    clear();
    activeProposalId = nextProposalId;
    proposal.value = initialProposal || null;
    startedAt = Date.now();

    if (!activeProposalId || (initialProposal && isToolProposalTerminal(initialProposal.status))) {
      return proposal.value;
    }

    const timerGeneration = generation;
    polling.value = true;
    timer = setInterval(() => {
      if (timerGeneration !== generation) return;
      if (Date.now() - startedAt >= maxElapsedMs) {
        invalidatePendingRequest();
        unknown.value = true;
        stop();
        return;
      }
      void refresh();
    }, intervalMs);

    return refresh();
  }

  onScopeDispose(clear);

  return { proposal, polling, unknown, start, refresh, stop, clear };
}
