import { afterEach, describe, expect, it, vi } from 'vitest';
import { confirmToolProposal, getToolProposal, rejectToolProposal } from './api';

describe('tool proposal API', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('gets one proposal with its real execution result', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: {
        proposalId: 'tip-1',
        status: 'EXECUTED',
        toolName: 'workspaceWriteFile',
        targetPaths: ['src/A.java'],
        executionResult: 'written'
      }
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const proposal = await getToolProposal('tip-1');

    expect(fetchMock).toHaveBeenCalledWith('/api/tool-proposals/tip-1', expect.objectContaining({ credentials: 'include' }));
    expect(proposal.executionResult).toBe('written');
  });

  it('returns the complete proposal object after a confirmation decision', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      code: 0,
      message: 'ok',
      data: {
        proposalId: 'tip-1',
        status: 'EXECUTING',
        toolName: 'workspaceWriteFile',
        targetPaths: ['src/A.java'],
        previewSummary: '写入文件'
      }
    }), { status: 200 })));
    vi.stubGlobal('fetch', fetchMock);

    const confirmed = await confirmToolProposal('tip-1', '用户确认');
    const rejected = await rejectToolProposal('tip-1', '用户拒绝');

    expect(confirmed.previewSummary).toBe('写入文件');
    expect(rejected.status).toBe('EXECUTING');
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/tool-proposals/tip-1/confirm', expect.objectContaining({ method: 'POST' }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/tool-proposals/tip-1/reject', expect.objectContaining({ method: 'POST' }));
  });
});
