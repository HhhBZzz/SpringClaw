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
  originalQuestion?: string;
  effectiveQuestion?: string;
}

export interface AgentTraceEvent {
  requestId?: string;
  stepName: string;
  type: 'request' | 'route' | 'model' | 'tool' | 'skill' | 'agent' | 'fallback' | 'final' | string;
  status: 'started' | 'success' | 'failed' | 'skipped' | string;
  detail?: string;
  durationMs?: number;
  timestamp?: number;
  qualityScore?: number | null;
  qualityLevel?: string;
  evaluation?: AgentQualityScore | null;
}

export interface AgentCapabilityEvent {
  requestId?: string;
  capabilityId: string;
  toolset: string;
  status: string;
  summary?: string;
  durationMs?: number;
  riskLevel?: string;
  payload?: string;
}

export interface AgentVerificationEvent {
  requestId?: string;
  status: string;
  sufficient: boolean;
  summary?: string;
  qualityScore?: number;
  qualityLevel?: string;
  quality?: AgentQualityScore | null;
}

export interface AgentQualityScore {
  overallScore: number;
  routeScore: number;
  toolScore: number;
  evidenceScore: number;
  reflectionScore: number;
  answerScore: number;
  costScore: number;
  riskScore: number;
  level: string;
  reason?: string;
  reasons?: string[];
}

export interface AgentDecisionEvent {
  requestId?: string;
  intent: string;
  executionPath: string;
  selectedCapabilities?: string;
  riskLevel: string;
  requiresConfirmation: boolean;
  reason?: string;
}

export interface AgentActionProposal {
  proposalId: string;
  requestId?: string;
  actionType: string;
  title: string;
  summary: string;
  riskLevel: string;
  expiresAt: number;
  status: string;
}

export interface AgentActionProposalResult {
  proposalId: string;
  status: string;
  message: string;
  result?: Record<string, unknown>;
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
  agentRuns?: Array<{
    requestId?: string;
    sessionKey?: string;
    userId?: string;
    lastStep?: string;
    status?: string;
    detail?: string;
    timestamp?: number;
  }>;
}

export type RuntimeResourceView = 'console' | 'sessions' | 'agents' | 'skills' | 'tools' | 'tasks' | 'usage';

export interface RuntimeSkill {
  skillId: string;
  name: string;
  description?: string;
  sourceType?: string;
  sourceRef?: string;
  executorType?: string;
  executorRef?: string;
  enabled?: boolean;
  agentVisible?: boolean;
  priority?: number;
  toolPacks?: string[];
  triggerKeywords?: string[];
  preferredMode?: string;
  contextPolicy?: string;
}

export interface RuntimeTool {
  name: string;
  toolset: string;
  requiredToolPacks?: string[];
  allow?: boolean;
  enabled?: boolean;
  riskLevel?: string;
  priority?: number;
  description?: string;
  roleCode?: string;
}

export interface RuntimeTask {
  taskId: string;
  ownerUserId?: string;
  name: string;
  enabled?: boolean | number;
  scheduleType: string;
  scheduleExpression: string;
  targetType?: string;
  targetRef?: string;
  channel?: string;
  deliveryMode?: string;
  lastRunAt?: string;
  nextRunAt?: string;
  lastStatus?: string;
}

export interface RuntimeUsageSummary {
  scope?: string;
  totalCalls?: number;
  totalTokens?: number;
  totalPromptTokens?: number;
  totalCompletionTokens?: number;
  promptCacheHitRate?: number;
  topProvider?: string;
  topModel?: string;
  recent?: Record<string, unknown>[];
}

export interface RuntimeModelProvider {
  providerId: string;
  model?: string;
  defaultModel?: string;
  baseUrl?: string;
  enabled?: boolean;
  available?: boolean;
  active?: boolean;
  availableReason?: string;
  models?: string[];
  displayName?: string;
  status?: string;
  unavailableReason?: string;
}

export interface RuntimeModelProviders {
  activeProvider?: string;
  activeModel?: string;
  activeDisplay?: string;
  canSwitch?: boolean;
  scope?: string;
  switchMode?: string;
  providers: RuntimeModelProvider[];
}

export interface RuntimeOverview {
  user?: {
    username?: string;
    roleCode?: string;
    admin?: boolean;
  };
  runtimeSkills?: {
    count?: number;
    scope?: string;
    sourceCounts?: Record<string, number>;
    definitions?: RuntimeSkill[];
  };
  tools?: RuntimeTool[];
  tasks?: {
    total?: number;
    enabled?: number;
    disabled?: number;
    scope?: string;
    tasks?: RuntimeTask[];
  };
  llmUsage?: RuntimeUsageSummary;
  providers?: RuntimeModelProviders;
  agentRuns?: AdminDashboard['agentRuns'];
}
