import type {
  AdminDashboard,
  AgentActionProposal,
  AgentActionProposalResult,
  AgentCapabilityEvent,
  AgentDecisionEvent,
  AgentTraceEvent,
  AgentVerificationEvent,
  ApiEnvelope,
  AuditLogPage,
  AuthProfileResponse,
  AuthTokenResponse,
  ChatHistoryResponse,
  ChatResponse,
  ChatResponseMode,
  ChatStreamMeta,
  ModelStatusResponse,
  RuntimeKnowledgeSourceReviewItem,
  RuntimeKnowledgeSourceReviewStatus,
  RuntimeKnowledgeSourceSnapshot,
  RuntimeKnowledgeSourceStatusUpdate,
  RuntimeLearningReviewItem,
  RuntimeLearningReviewStatus,
  RuntimeLearningStatusUpdate,
  RuntimeMemoryCandidateListStatus,
  RuntimeMemoryCandidateReviewItem,
  RuntimeMemoryCandidateReviewStatus,
  RuntimeMemoryCandidateStatusUpdate,
  RuntimeMemoryUsageTrace,
  RuntimeModelProviders,
  RuntimeOverview,
  RuntimeSkill,
  RuntimeTask,
  RuntimeTool,
  RuntimeToolProposal,
  RuntimeUsageSummary,
  ToolActionRequiredEvent,
  ToolProposalResult
} from '../types';

const TOKEN_KEY = 'springclaw.frontend.token';
let memoryToken = '';
export type ApiErrorKind = 'empty' | 'http' | 'network' | 'non_json';

export class ApiError extends Error {
  readonly status: number;
  readonly code?: number;
  readonly kind: ApiErrorKind;

  constructor(message: string, status: number, code?: number, kind: ApiErrorKind = 'http') {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.kind = kind;
  }
}

export function readToken(): string {
  return memoryToken;
}

export function writeToken(token: string) {
  memoryToken = token || '';
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(url: string, options: RequestInit & { auth?: boolean } = {}): Promise<T> {
  const token = readToken();
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'application/json');
  if (options.auth !== false && token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(url, { ...options, headers, credentials: 'include' });
  const raw = await response.text();
  let payload: ApiEnvelope<T>;
  if (!raw) {
    throw new ApiError('响应为空', response.status, undefined, 'empty');
  }
  try {
    payload = JSON.parse(raw) as ApiEnvelope<T>;
  } catch {
    const contentType = response.headers.get('Content-Type') || 'unknown';
    const method = options.method || 'GET';
    const snippet = raw.replace(/\s+/g, ' ').slice(0, 120);
    throw new ApiError(`接口返回非 JSON：${method} ${url} -> HTTP ${response.status} (${contentType})。请确认后端 18080 已启动，且当前页面通过 Vue/Vite 5173 访问。响应片段：${snippet || '空响应'}`, response.status, undefined, 'non_json');
  }
  if (!response.ok || payload.code !== 0) {
    throw new ApiError(payload.message || `请求失败 (${response.status})`, response.status, payload.code);
  }
  return payload.data;
}

export function login(username: string, password: string) {
  return request<AuthTokenResponse>('/api/auth/login', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ username, password })
  });
}

export function register(username: string, password: string) {
  return request<AuthTokenResponse>('/api/auth/register', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ username, password })
  });
}

export function me() {
  return request<AuthProfileResponse>('/api/auth/me');
}

export function logoutRequest() {
  return request<void>('/api/auth/logout', {
    method: 'POST',
    body: JSON.stringify({})
  });
}

export function sendChat(input: { sessionKey: string; userId: string; message: string; channel?: string; responseMode?: ChatResponseMode }) {
  return request<ChatResponse>('/api/chat/send', {
    method: 'POST',
    body: JSON.stringify({ ...input, channel: input.channel || 'api' })
  });
}

export function getChatHistory(sessionKey: string, limit = 80) {
  const params = new URLSearchParams({
    sessionKey,
    limit: String(limit)
  });
  return request<ChatHistoryResponse>(`/api/chat/history?${params.toString()}`);
}

export function getModelStatus() {
  return request<ModelStatusResponse>('/api/chat/model-status');
}

export function confirmActionProposal(proposalId: string, currentSessionKey: string) {
  return request<AgentActionProposalResult>(`/api/chat/action-proposals/${encodeURIComponent(proposalId)}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ currentSessionKey })
  });
}

export function cancelActionProposal(proposalId: string) {
  return request<AgentActionProposalResult>(`/api/chat/action-proposals/${encodeURIComponent(proposalId)}/cancel`, {
    method: 'POST',
    body: JSON.stringify({})
  });
}

export function confirmToolProposal(proposalId: string, reason = '用户确认执行') {
  return request<ToolProposalResult>(`/api/tool-proposals/${encodeURIComponent(proposalId)}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  });
}

export function rejectToolProposal(proposalId: string, reason = '用户拒绝执行') {
  return request<ToolProposalResult>(`/api/tool-proposals/${encodeURIComponent(proposalId)}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  });
}

export function getToolProposals(input: { sessionKey?: string; status?: string } = {}) {
  const params = new URLSearchParams();
  if (input.sessionKey) params.set('sessionKey', input.sessionKey);
  if (input.status) params.set('status', input.status);
  const query = params.toString();
  return request<RuntimeToolProposal[]>(`/api/tool-proposals${query ? `?${query}` : ''}`);
}

export function getAdminDashboard() {
  return request<AdminDashboard>('/api/admin/manage/dashboard');
}

export function getRuntimeOverview() {
  return request<RuntimeOverview>('/api/runtime-console/overview');
}

export function getRuntimeSkills() {
  return request<{ count?: number; scope?: string; sourceCounts?: Record<string, number>; definitions?: RuntimeSkill[] }>('/api/runtime-console/skills');
}

export function getRuntimeTools() {
  return request<RuntimeTool[]>('/api/runtime-console/tools');
}

export function getRuntimeTasks(limit = 20) {
  return request<{ total?: number; enabled?: number; disabled?: number; scope?: string; tasks?: RuntimeTask[] }>(`/api/runtime-console/tasks?limit=${encodeURIComponent(String(limit))}`);
}

export function getRuntimeLearningEntries(limit = 50) {
  return request<RuntimeLearningReviewItem[]>(`/api/runtime-console/learning?limit=${encodeURIComponent(String(limit))}`);
}

export function getRuntimeMemoryCandidates(status: RuntimeMemoryCandidateListStatus = 'CANDIDATE', limit = 50) {
  return request<RuntimeMemoryCandidateReviewItem[]>(`/api/runtime-console/memory/candidates?status=${encodeURIComponent(status)}&limit=${encodeURIComponent(String(limit))}`);
}

export function getRuntimeKnowledgeSources(limit = 50) {
  return request<RuntimeKnowledgeSourceReviewItem[]>(`/api/runtime-console/knowledge-sources?limit=${encodeURIComponent(String(limit))}`);
}

export function getRuntimeKnowledgeSourceSnapshot() {
  return request<RuntimeKnowledgeSourceSnapshot>('/api/runtime-console/knowledge-sources/snapshot');
}

export function updateRuntimeKnowledgeSourceStatus(path: string, status: RuntimeKnowledgeSourceReviewStatus, reason?: string) {
  return request<RuntimeKnowledgeSourceStatusUpdate>('/api/runtime-console/knowledge-sources/status', {
    method: 'POST',
    body: JSON.stringify({ path, status, reason })
  });
}

export function updateRuntimeLearningStatus(signature: string, status: RuntimeLearningReviewStatus, reason?: string) {
  return request<RuntimeLearningStatusUpdate>('/api/runtime-console/learning/status', {
    method: 'POST',
    body: JSON.stringify({ signature, status, reason })
  });
}

export function updateRuntimeMemoryCandidateStatus(memoryVersionId: string, status: RuntimeMemoryCandidateReviewStatus, reason?: string) {
  return request<RuntimeMemoryCandidateStatusUpdate>('/api/runtime-console/memory/candidates/status', {
    method: 'POST',
    body: JSON.stringify({ memoryVersionId, status, reason })
  });
}

export function getRuntimeUsage(recentLimit = 20) {
  return request<RuntimeUsageSummary>(`/api/runtime-console/usage?recentLimit=${encodeURIComponent(String(recentLimit))}`);
}

export function getRuntimeModelProviders() {
  return request<RuntimeModelProviders>('/api/runtime-console/model-providers');
}

export function switchRuntimeModelProvider(providerId: string, model?: string) {
  return request<RuntimeModelProviders>('/api/runtime-console/model-providers/switch', {
    method: 'POST',
    body: JSON.stringify({ providerId, model })
  });
}

export function getRuntimeRuns(limit = 20) {
  return request<NonNullable<RuntimeOverview['agentRuns']>>(`/api/runtime-console/runs?limit=${encodeURIComponent(String(limit))}`);
}

export function getRunTrace(requestId: string, limit = 80) {
  return request<AgentTraceEvent[]>(`/api/chat/runs/${encodeURIComponent(requestId)}/trace?limit=${encodeURIComponent(String(limit))}`);
}

export function getRunMemoryUsage(requestId: string) {
  return request<RuntimeMemoryUsageTrace>(`/api/runtime-console/runs/memory-usage?requestId=${encodeURIComponent(requestId)}`);
}

export function getAuditLogs(input: { page?: number; size?: number } = {}) {
  const page = input.page ?? 1;
  const size = input.size ?? 12;
  return request<AuditLogPage>(`/api/admin/audit/logs?page=${page}&size=${size}`);
}

export interface ChatStreamHandlers {
  onToken?: (token: string) => void;
  onStatus?: (status: string) => void;
  onMeta?: (meta: ChatStreamMeta) => void;
  onDecision?: (decision: AgentDecisionEvent) => void;
  onTrace?: (event: AgentTraceEvent) => void;
  onToolCall?: (event: AgentCapabilityEvent) => void;
  onSkillCall?: (event: AgentCapabilityEvent) => void;
  onVerification?: (event: AgentVerificationEvent) => void;
  onActionRequired?: (proposal: AgentActionProposal) => void;
  onToolActionRequired?: (proposal: ToolActionRequiredEvent) => void;
  onError?: (message: string) => void;
  onDone?: () => void;
}

export interface ChatStreamOptions {
  signal?: AbortSignal;
  timeoutMs?: number;
}

export async function streamChat(
  input: { sessionKey: string; userId: string; message: string; channel?: string; responseMode?: ChatResponseMode },
  handlers: ChatStreamHandlers = {},
  options: ChatStreamOptions = {}
) {
  const token = readToken();
  const headers = new Headers();
  headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'text/event-stream');
  if (token) {
      headers.set('Authorization', `Bearer ${token}`);
  }

  const controller = new AbortController();
  const timeoutMs = Math.max(1000, options.timeoutMs ?? 60_000);
  const timeout = window.setTimeout(() => controller.abort('timeout'), timeoutMs);
  const abortFromCaller = () => controller.abort(options.signal?.reason || 'cancelled');
  if (options.signal?.aborted) {
    controller.abort(options.signal.reason || 'cancelled');
  } else {
    options.signal?.addEventListener('abort', abortFromCaller, { once: true });
  }

  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers,
      signal: controller.signal,
      credentials: 'include',
      body: JSON.stringify({ ...input, channel: input.channel || 'api', responseMode: input.responseMode || 'agent' })
    });

    if (!response.ok) {
      const raw = await response.text();
      throw new Error(extractHttpError(raw, response.status));
    }
    if (!response.body) {
      throw new Error('浏览器不支持流式响应。');
    }

    reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = consumeSseBuffer(buffer, handlers);
    }
    buffer += decoder.decode();
    consumeSseBuffer(buffer + '\n\n', handlers);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error(controller.signal.reason === 'timeout' ? 'Agent 流式响应超时，请稍后重试。' : 'Agent 流式请求已取消。');
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
    options.signal?.removeEventListener('abort', abortFromCaller);
    reader?.releaseLock();
  }
}

function consumeSseBuffer(buffer: string, handlers: ChatStreamHandlers) {
  const parts = buffer.split(/\r?\n\r?\n/);
  const rest = parts.pop() || '';
  for (const part of parts) {
    dispatchSseEvent(part, handlers);
  }
  return rest;
}

function dispatchSseEvent(raw: string, handlers: ChatStreamHandlers) {
  let eventName = 'message';
  const dataLines: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
    }
  }
  const data = dataLines.join('\n');
  if (eventName === 'token' || eventName === 'message') {
    handlers.onToken?.(data);
  } else if (eventName === 'status') {
    handlers.onStatus?.(data);
  } else if (eventName === 'meta') {
    try {
      handlers.onMeta?.(JSON.parse(data) as ChatStreamMeta);
    } catch {
      handlers.onMeta?.({ routingReason: data });
    }
  } else if (eventName === 'trace') {
    try {
      handlers.onTrace?.(JSON.parse(data) as AgentTraceEvent);
    } catch {
      handlers.onTrace?.({ stepName: '执行流程', type: 'agent', status: 'success', detail: data });
    }
  } else if (eventName === 'decision') {
    try {
      handlers.onDecision?.(JSON.parse(data) as AgentDecisionEvent);
    } catch {
      handlers.onDecision?.({ intent: 'unknown', executionPath: 'ask_clarification', riskLevel: 'read', requiresConfirmation: false, reason: data });
    }
  } else if (eventName === 'tool_call') {
    try {
      handlers.onToolCall?.(JSON.parse(data) as AgentCapabilityEvent);
    } catch {
      handlers.onToolCall?.({ capabilityId: 'tool', toolset: 'tool', status: 'success', payload: data });
    }
  } else if (eventName === 'skill_call') {
    try {
      handlers.onSkillCall?.(JSON.parse(data) as AgentCapabilityEvent);
    } catch {
      handlers.onSkillCall?.({ capabilityId: 'skill', toolset: 'skills', status: 'success', payload: data });
    }
  } else if (eventName === 'verification') {
    try {
      handlers.onVerification?.(JSON.parse(data) as AgentVerificationEvent);
    } catch {
      handlers.onVerification?.({ status: 'success', sufficient: true, summary: data });
    }
  } else if (eventName === 'action_required') {
    try {
      handlers.onActionRequired?.(JSON.parse(data) as AgentActionProposal);
    } catch {
      handlers.onActionRequired?.({ proposalId: '', actionType: 'unknown', title: '需要确认', summary: data, riskLevel: 'side_effect', expiresAt: Date.now(), status: 'PENDING' });
    }
  } else if (eventName === 'tool_action_required') {
    try {
      handlers.onToolActionRequired?.(JSON.parse(data) as ToolActionRequiredEvent);
    } catch {
      handlers.onToolActionRequired?.({
        proposalId: '',
        requestId: '',
        runId: '',
        toolName: 'unknown',
        riskLevel: 'write',
        targetPaths: [],
        previewSummary: data,
        workspaceDirty: false,
        expiresAt: ''
      });
    }
  } else if (eventName === 'error') {
    handlers.onError?.(data);
  } else if (eventName === 'done') {
    handlers.onDone?.();
  }
}

function extractHttpError(raw: string, status: number) {
  try {
    const payload = JSON.parse(raw) as ApiEnvelope<unknown>;
    return payload.message || `请求失败 (${status})`;
  } catch {
    return raw.replace(/\s+/g, ' ').slice(0, 160) || `请求失败 (${status})`;
  }
}
