import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const apiMocks = vi.hoisted(() => ({
  me: vi.fn(),
  logoutRequest: vi.fn()
}));

vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>();
  return {
    ...actual,
    me: apiMocks.me,
    logoutRequest: apiMocks.logoutRequest
  };
});

import { useAuthStore } from './auth';
import { ApiError, writeToken } from '../services/api';

describe('auth session restoration', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { removeItem: vi.fn() });
    setActivePinia(createPinia());
    apiMocks.me.mockReset();
    apiMocks.logoutRequest.mockReset();
    apiMocks.logoutRequest.mockResolvedValue(undefined);
    writeToken('');
  });

  afterEach(() => vi.unstubAllGlobals());

  it('restores the profile through the cookie-backed me endpoint after a refresh', async () => {
    apiMocks.me.mockResolvedValue({
      username: 'workspace-user',
      roleCode: 'USER',
      expireAt: 1_785_000_000_000
    });
    const auth = useAuthStore();

    expect(auth.token).toBe('');
    await auth.loadMe();

    expect(apiMocks.me).toHaveBeenCalledOnce();
    expect(auth.profile).toEqual({
      username: 'workspace-user',
      roleCode: 'USER',
      expireAt: 1_785_000_000_000
    });
  });

  it('keeps a first-time visitor anonymous when cookie verification returns unauthorized', async () => {
    apiMocks.me.mockRejectedValue(new ApiError('请先登录', 401));
    const auth = useAuthStore();

    await auth.loadMe();

    expect(apiMocks.me).toHaveBeenCalledOnce();
    expect(auth.profile).toBeNull();
    expect(auth.error).toBe('');
  });

  it('surfaces a forbidden response instead of hiding a proxy or permission diagnostic', async () => {
    apiMocks.me.mockRejectedValue(new ApiError('权限校验失败', 403));
    const auth = useAuthStore();

    await auth.loadMe();

    expect(apiMocks.me).toHaveBeenCalledOnce();
    expect(auth.profile).toBeNull();
    expect(auth.error).toBe('权限校验失败');
    expect(auth.errorStatus).toBe(403);
  });

  it('clears a stale in-memory token and explains that its session expired', async () => {
    writeToken('stale-token');
    apiMocks.me.mockRejectedValue(new ApiError('请先登录', 401));
    const auth = useAuthStore();

    await auth.loadMe();

    expect(auth.token).toBe('');
    expect(auth.profile).toBeNull();
    expect(auth.error).toBe('登录态已失效，请重新登录。');
  });
});
