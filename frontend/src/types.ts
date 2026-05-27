export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

export interface AuthTokenResponse {
  token: string;
  username: string;
  roleCode: string;
  expireAt: number;
}

export interface AuthProfileResponse {
  username: string;
  roleCode: string;
  expireAt: number;
}

export interface ChatResponse {
  sessionKey: string;
  answer: string;
  model: string;
  timestamp: number;
}

export type ChatResponseMode = 'agent' | 'fast' | 'deep' | 'tool';

export interface ChatStreamMeta {
  requestId?: string;
  responseMode?: ChatResponseMode | string;
  executionMode?: string;
  intent?: string;
  routingReason?: string;
}

export interface ModelStatusResponse {
  activeProvider: string;
  activeModel: string;
  activeDisplay: string;
  available: boolean;
  status: 'online' | 'degraded' | string;
  unavailableReason?: string;
  recommendation?: string;
  checkedAt?: number;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'agent' | 'system';
  content: string;
  model?: string;
  createdAt: number;
}

export interface ChatHistoryResponse {
  sessionKey: string;
  messages: ChatMessage[];
}

export interface ChatSessionSummary {
  sessionKey: string;
  title: string;
  updatedAt: number;
  messageCount: number;
}

export interface TokenRuntimeStats {
  activeTokenCount: number;
  activeUserCount: number;
  redisTokenCount: number;
  localTokenCount: number;
  expiringSoonCount: number;
  redisBacked: boolean;
  tokenTtlSeconds: number;
}

export interface ActiveSession {
  username: string;
  roleCode: string;
  expireAt: number;
  tokenPreview: string;
  storage: string;
}

export interface RecentUserActivity {
  userId: string;
  recentEvents: number;
  lastSeenAt: string;
  channels: string[];
}

export interface AuditRecord {
  id?: number | string;
  sessionKey?: string;
  channel?: string;
  userId?: string;
  role?: string;
  eventType?: string;
  requestId?: string;
  content?: string;
  processingStatus?: string;
  errorMessage?: string;
  createTime?: string;
}

export interface AuditLogPage {
  page: number;
  size: number;
  total: number;
  records: AuditRecord[];
}

export interface AdminDashboard {
  dbEnabled: boolean;
  auditStats?: Record<string, unknown>;
  userCount: number;
  toolPermissionCount: number;
  skillDescriptorCount: number;
  skillPolicyCount: number;
  runtimeSkillCount: number;
  roleCounts?: Record<string, number>;
  tokenStats?: TokenRuntimeStats;
  activeSessions?: ActiveSession[];
  recentUsers?: RecentUserActivity[];
  runtimeSkills?: {
    count?: number;
    enabled?: boolean;
    sourceCounts?: Record<string, number>;
    definitions?: unknown[];
  };
  providers?: Record<string, unknown>;
  llmUsage?: Record<string, unknown>;
  recentLlmUsage?: unknown[];
  chatRouting?: Record<string, unknown>;
}
