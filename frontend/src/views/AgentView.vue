<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import AgentMessage from '../components/AgentMessage.vue';
import LoginPanel from '../components/LoginPanel.vue';
import { useAgentGsapMotion } from '../composables/useAgentGsapMotion';
import {
  cancelActionProposal,
  confirmActionProposal,
  getChatHistory,
  getModelStatus,
  getRunTrace,
  getRuntimeModelProviders,
  getRuntimeOverview,
  getRuntimeRuns,
  getRuntimeSkills,
  getRuntimeTasks,
  getRuntimeTools,
  getRuntimeUsage,
  streamChat,
  switchRuntimeModelProvider
} from '../services/api';
import { useAuthStore } from '../stores/auth';
import type { AgentActionProposal, AgentCapabilityEvent, AgentDecisionEvent, AgentQualityScore, AgentTraceEvent, AgentVerificationEvent, ChatMessage, ChatResponseMode, ChatSessionSummary, ChatStreamMeta, ModelStatusResponse, RuntimeModelProviders, RuntimeOverview, RuntimeResourceView, RuntimeSkill, RuntimeTask, RuntimeTool, RuntimeUsageSummary } from '../types';

const auth = useAuthStore();
const SESSION_KEY = 'springclaw.frontend.session';
const SESSIONS_KEY = 'springclaw.frontend.chat.sessions.v1';
const RESPONSE_MODE_KEY = 'springclaw.frontend.responseMode';
const SIDEBAR_PINNED_KEY = 'springclaw.frontend.sidebarPinned';
const SESSION_TITLES_KEY = 'springclaw.frontend.chat.sessionTitles.v1';
const input = ref('');
const sessionKey = ref(localStorage.getItem(SESSION_KEY) || makeSessionKey());
const messages = ref<ChatMessage[]>(readLocalMessages(sessionKey.value));
const historySessions = ref<ChatSessionSummary[]>(readHistorySessions());
const customSessionTitles = ref<Record<string, string>>(readSessionTitles());
const busy = ref(false);
const responseMode = ref<ChatResponseMode>(readResponseMode());
const sidebarPinned = ref(readSidebarPinned());
const sidebarHovered = ref(false);
const sidebarCollapsed = ref(false);
const streamStatus = ref('');
const streamMeta = ref<ChatStreamMeta | null>(null);
const traceEvents = ref<AgentTraceEvent[]>([]);
const capabilityEvents = ref<AgentCapabilityEvent[]>([]);
const verificationEvent = ref<AgentVerificationEvent | null>(null);
const agentDecision = ref<AgentDecisionEvent | null>(null);
const pendingAction = ref<AgentActionProposal | null>(null);
const actionStatus = ref('');
const modelStatus = ref<ModelStatusResponse | null>(null);
const modelStatusLoading = ref(false);
const modelStatusError = ref('');
const historyStatus = ref('');
const historyLoading = ref(false);
const elapsedSeconds = ref(0);
const firstTokenMs = ref<number | null>(null);
const lastUserPrompt = ref('');
const stoppingStream = ref(false);
const runtimeActionStatus = ref('');
const activeResourceView = ref<RuntimeResourceView>('console');
const runtimeResourceLoading = ref(false);
const runtimeResourceError = ref('');
const runtimeOverview = ref<RuntimeOverview | null>(null);
const runtimeSkills = ref<{ count?: number; scope?: string; sourceCounts?: Record<string, number>; definitions?: RuntimeSkill[] } | null>(null);
const runtimeTools = ref<RuntimeTool[]>([]);
const runtimeTasks = ref<{ total?: number; enabled?: number; disabled?: number; scope?: string; tasks?: RuntimeTask[] } | null>(null);
const runtimeUsage = ref<RuntimeUsageSummary | null>(null);
const runtimeRuns = ref<NonNullable<RuntimeOverview['agentRuns']>>([]);
const runtimeModelProviders = ref<RuntimeModelProviders | null>(null);
const modelSwitcherOpen = ref(false);
const sessionSearchOpen = ref(false);
const sessionSearchQuery = ref('');
const taskMetaExpanded = ref(false);
const studioRoot = ref<HTMLElement | null>(null);
const chatLog = ref<HTMLElement | null>(null);
const motion = useAgentGsapMotion({ root: studioRoot, chatLog });
let persistTimer: number | undefined;
let scrollQueued = false;
let elapsedTimer: number | undefined;
let activeStreamController: AbortController | null = null;

const modeActions = [
  {
    label: '项目审查',
    prompt: '请审查当前项目源码，输出架构、冗余、风险和下一步优化清单。'
  },
  {
    label: '运行 Skill',
    prompt: '列出当前可运行 skills，并说明每个 skill 的输入、输出和适合场景。'
  },
  {
    label: '定时任务',
    prompt: '生成一个每天 9 点运行 web_crawler 的定时任务草稿，先不要直接落库。'
  },
  {
    label: '后台排障',
    prompt: '检查后端启动、数据库、Redis、管理接口和模型配置是否正常。'
  }
] as const;
const prompts = [
  '帮我讲清楚当前项目的 Agent 执行链路。',
  '请审查当前项目源码，看看架构是否合理，有没有冗余垃圾代码和风险点。',
  '梳理现有 skills，并判断哪些适合继续收口。',
  '生成一个每天 9 点运行 web_crawler 的任务草稿。'
] as const;

const engineerNavItems: Array<{ key: RuntimeResourceView; label: string; className: string }> = [
  { key: 'console', label: 'Agent Console', className: 'nav-console' },
  { key: 'sessions', label: 'Sessions', className: 'nav-sessions' },
  { key: 'agents', label: 'Agents', className: 'nav-agents' },
  { key: 'skills', label: 'Skills', className: 'nav-skills' },
  { key: 'tools', label: 'Tools', className: 'nav-tools' },
  { key: 'tasks', label: 'Tasks', className: 'nav-tasks' },
  { key: 'usage', label: 'Usage', className: 'nav-usage' }
];

type RuntimeNavKey = RuntimeResourceView;

type InspectorTab = 'trace' | 'tools' | 'memory' | 'logs';
type RunStepStatus = 'completed' | 'running' | 'pending' | 'failed';

const responseModes: Array<{ value: ChatResponseMode; label: string; description: string }> = [
  { value: 'agent', label: 'Agent', description: '默认，模型主导并可调用工具' },
  { value: 'fast', label: '快速', description: '轻量回答，减少多轮规划' },
  { value: 'deep', label: '深度', description: '强制 OPAR 深度链路' },
  { value: 'tool', label: '工具优先', description: '适合明确执行 skill/tool' }
];
const activeInspectorTab = ref<InspectorTab>('trace');

const inspectorTabs: Array<{ value: InspectorTab; label: string }> = [
  { value: 'trace', label: 'Run Trace' },
  { value: 'tools', label: 'Tool Calls' },
  { value: 'memory', label: 'Memory' },
  { value: 'logs', label: 'Logs' }
];

const workspaceShortcuts = [
  {
    label: '本地项目审查',
    description: '扫描当前工作区，输出架构、风险、冗余和阅读顺序',
    prompt: '请审查当前项目源码，看看架构是否合理，有没有冗余垃圾代码和风险点。'
  },
  {
    label: '授权文件搜索',
    description: '在电脑已授权目录中查找简历、论文、文档或项目资料',
    prompt: '帮我在本地电脑授权文件里找一下简历相关文件。'
  },
  {
    label: '本地文件边界',
    description: '说明 Agent 能读取哪些项目文件，以及不能读取什么',
    prompt: '说明你现在能看到哪些本地项目文件，读取边界是什么。'
  }
] as const;

const capabilitySummary = [
  { label: 'Runtime', value: 'ChatService + Skill Executor' },
  { label: 'History', value: 'local cache + server events' },
  { label: 'Mode', value: 'agent by default' }
] as const;

const operationShortcuts = [
  {
    label: '检查 DeepSeek',
    description: '确认当前模型通道、V4 Pro 配置和后端兼容状态',
    prompt: '查看当前 AI provider 和模型配置，确认 DeepSeek V4 Pro 是否正常可用。'
  },
  {
    label: '查看 Skill 清单',
    description: '列出真实可运行 skills，区分脚本、Markdown 和内置能力',
    prompt: '列出当前所有可运行 skills，说明它们分别怎么运行、能做什么、是否有重复。'
  },
  {
    label: '解释任务底座',
    description: '说明 XXL-Job dispatcher、ScheduledTask 和 Agent 主链路关系',
    prompt: '讲清楚当前定时任务底座怎么运行，XXL-Job dispatcher 和 skill/agent 执行之间是什么关系。'
  }
] as const;

const skillCards = [
  { id: 'workspace-review', type: 'BUILTIN', description: '审查项目源码、模块边界、冗余和风险。' },
  { id: 'local-files', type: 'BUILTIN', description: '读取授权本地目录，适合文档和桌面文件查询。' },
  { id: 'repo_inspector', type: 'PYTHON', description: '定位仓库文件、实现类和关键代码片段。' },
  { id: 'web_crawler', type: 'PYTHON', description: '抓取网页并清洗正文，适合研究资料采集。' },
  { id: 'clawhub-summarize', type: 'PROMPT', description: 'Markdown prompt skill，用于结构化总结。' }
] as const;

const memoryContexts: Array<{ source: string; score: string; detail: string }> = [];

const sessionState = computed(() => {
  if (!auth.profile) return '需要登录';
  if (busy.value) return '执行中';
  return messages.value.length ? '会话进行中' : '等待输入';
});

const currentSessionSummary = computed(() => {
  return historySessions.value.find((item) => item.sessionKey === sessionKey.value);
});

const filteredHistorySessions = computed(() => {
  const query = sessionSearchQuery.value.trim().toLowerCase();
  const source = historySessions.value.slice(0, 20);
  if (!query) return source;
  return source.filter((item) => item.title.toLowerCase().includes(query) || item.sessionKey.toLowerCase().includes(query));
});

const responseTiming = computed(() => {
  if (!busy.value) return '';
  if (firstTokenMs.value != null) {
    return `首字 ${formatMs(firstTokenMs.value)}，已运行 ${elapsedSeconds.value}s`;
  }
  return `等待首字 ${elapsedSeconds.value}s`;
});

const latestTrace = computed(() => traceEvents.value[traceEvents.value.length - 1]);

const currentRequestId = computed(() => {
  return streamMeta.value?.requestId || agentDecision.value?.requestId || latestTrace.value?.requestId || '-';
});

const resolvedQuestionLabel = computed(() => {
  const original = (streamMeta.value?.originalQuestion || '').trim();
  const effective = (streamMeta.value?.effectiveQuestion || '').trim();
  if (!effective) return '-';
  if (original && original !== effective) return `${original} -> ${effective}`;
  return effective;
});

const compactSessionId = computed(() => {
  const key = sessionKey.value || '-';
  if (key.length <= 14) return key;
  return `${key.slice(0, 6)}...${key.slice(-6)}`;
});

const taskTitle = computed(() => {
  const customTitle = customSessionTitles.value[sessionKey.value];
  if (customTitle) return customTitle;
  const latestUserMessage = [...messages.value].reverse().find((item) => item.role === 'user' && item.content.trim());
  if (!latestUserMessage) return '新建 Agent 任务';
  return inferSessionTitle([latestUserMessage]);
});

const taskCreatedLabel = computed(() => {
  const updatedAt = currentSessionSummary.value?.updatedAt || messages.value[0]?.createdAt || Date.now();
  return new Date(updatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
});

const runStatus = computed(() => {
  if (busy.value) return 'Running';
  if (traceEvents.value.some((event) => event.status === 'failed')) return 'Failed';
  if (traceEvents.value.length) return 'Completed';
  return 'Ready';
});

const runDurationLabel = computed(() => {
  if (busy.value) return `${elapsedSeconds.value || 1}s`;
  const totalDuration = traceEvents.value.reduce((sum, event) => sum + (typeof event.durationMs === 'number' ? event.durationMs : 0), 0);
  return totalDuration > 0 ? formatMs(totalDuration) : '-';
});

const agentQuality = computed<AgentQualityScore | null>(() => {
  if (verificationEvent.value?.quality) return verificationEvent.value.quality;
  const traceQuality = [...traceEvents.value].reverse().find((event) => event.evaluation)?.evaluation;
  if (traceQuality) return traceQuality;
  if (typeof verificationEvent.value?.qualityScore === 'number') {
    return qualityFromFlatEvent(verificationEvent.value.qualityScore, verificationEvent.value.qualityLevel || 'weak', verificationEvent.value.summary || '');
  }
  const flatTrace = [...traceEvents.value].reverse().find((event) => typeof event.qualityScore === 'number');
  if (flatTrace && typeof flatTrace.qualityScore === 'number') {
    return qualityFromFlatEvent(flatTrace.qualityScore, flatTrace.qualityLevel || 'weak', flatTrace.detail || '');
  }
  return null;
});

const qualityLevelLabel = computed(() => {
  const level = agentQuality.value?.level || '';
  if (level === 'strong') return 'Strong';
  if (level === 'acceptable') return 'Acceptable';
  if (level === 'weak') return 'Weak';
  if (level === 'failed') return 'Failed';
  return '-';
});

const qualityMetricRows = computed(() => {
  const quality = agentQuality.value;
  if (!quality) return [];
  return [
    { key: 'route', label: 'Route', score: quality.routeScore },
    { key: 'tool', label: 'Tool', score: quality.toolScore },
    { key: 'evidence', label: 'Evidence', score: quality.evidenceScore },
    { key: 'reflect', label: 'Reflect', score: quality.reflectionScore },
    { key: 'answer', label: 'Answer', score: quality.answerScore },
    { key: 'cost', label: 'Cost', score: quality.costScore },
    { key: 'risk', label: 'Risk', score: quality.riskScore }
  ];
});

const visibleRunSteps = computed(() => {
  return traceEvents.value.map((event, index) => ({
    label: event.stepName || `Step ${index + 1}`,
    detail: event.detail || traceStatusLabel(event.status),
    status: normalizeStepStatus(event.status),
    duration: traceDurationLabel(event) || (event.status === 'started' ? 'running' : '-'),
    tools: event.type
  }));
});

const currentStep = computed(() => {
  return visibleRunSteps.value.find((step) => step.status === 'running')
    || [...visibleRunSteps.value].reverse().find((step) => step.status === 'completed')
    || visibleRunSteps.value[0];
});

const selectedCapabilitiesList = computed(() => {
  const raw = agentDecision.value?.selectedCapabilities;
  if (!raw) return [];
  return raw
    .split(/[,;\n]/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 8);
});

const visibleToolCalls = computed(() => {
  return capabilityEvents.value.map((event) => ({
    name: event.capabilityId,
    risk: event.riskLevel || agentDecision.value?.riskLevel || 'read',
    duration: typeof event.durationMs === 'number' && event.durationMs > 0 ? formatMs(event.durationMs) : '-',
    status: event.status,
    summary: event.summary || event.payload || '',
    toolset: event.toolset
  }));
});

const visibleLogs = computed(() => {
  const rows = traceEvents.value.map((event) => ({
    level: event.status === 'failed' ? 'error' : event.status === 'skipped' ? 'warn' : 'info',
    message: `${event.stepName}: ${event.detail || traceStatusLabel(event.status)}`,
    at: event.timestamp ? formatClock(event.timestamp) : taskCreatedLabel.value
  }));
  if (agentDecision.value) {
    rows.unshift({
      level: agentDecision.value.requiresConfirmation ? 'warn' : 'info',
      message: `decision ${agentDecision.value.intent} -> ${agentDecision.value.executionPath}`,
      at: taskCreatedLabel.value
    });
  }
  return rows;
});

const executionPayload = computed(() => {
  const step = currentStep.value;
  return JSON.stringify({
    action: normalizeActionName(step?.label || 'idle'),
    status: step?.status || 'pending',
    progress: step?.status === 'completed' ? 100 : step?.status === 'running' ? 68 : 0,
    verification: verificationEvent.value?.status || 'pending',
    quality: agentQuality.value?.overallScore ?? null,
    model: modelStatus.value?.activeModel || modelStatusLabel.value,
    requestId: currentRequestId.value
  }, null, 2);
});

const decisionLabel = computed(() => {
  if (!agentDecision.value) return '自动判断';
  return `${agentDecision.value.intent} · ${agentDecision.value.executionPath}`;
});

const activeResponseMode = computed(() => {
  return responseModes.find((mode) => mode.value === responseMode.value) || responseModes[0];
});

const sidebarOpen = computed(() => sidebarPinned.value || sidebarHovered.value);

const sidebarModeLabel = computed(() => {
  return sidebarPinned.value ? '固定显示' : '悬停展开';
});

const modelHealthClass = computed(() => {
  if (!auth.profile) return 'is-idle';
  if (modelStatusLoading.value) return 'is-checking';
  if (modelStatusError.value) return 'is-error';
  if (!modelStatus.value) return 'is-idle';
  return modelStatus.value.available ? 'is-online' : 'is-degraded';
});

const modelStatusLabel = computed(() => {
  if (!auth.profile) return '登录后检测';
  if (modelStatusLoading.value) return '检测中';
  if (modelStatusError.value) return '检测失败';
  if (!modelStatus.value) return '未检测';
  return modelStatus.value.available ? modelStatus.value.activeModel : '模型降级';
});

const modelStatusDetail = computed(() => {
  if (!auth.profile) return '登录后查看后端真实模型状态';
  if (modelStatusError.value) return modelStatusError.value;
  if (!modelStatus.value) return '点击刷新获取当前 provider 状态';
  if (modelStatus.value.available) return `${modelStatus.value.activeProvider} · 主链路可用`;
  return modelStatus.value.unavailableReason || 'provider 当前不可用';
});

const modelCheckedAtLabel = computed(() => {
  const checkedAt = modelStatus.value?.checkedAt;
  if (!checkedAt) return '-';
  return new Date(checkedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
});

const canRetryLastPrompt = computed(() => {
  return Boolean(auth.profile && lastUserPrompt.value.trim() && !busy.value);
});

const runtimeHealthLabel = computed(() => {
  if (!auth.profile) return 'Login required';
  if (modelStatusLoading.value) return 'Checking';
  if (modelStatusError.value) return 'Degraded';
  if (!modelStatus.value) return 'Unknown';
  return modelStatus.value.available ? 'Healthy' : 'Degraded';
});

const runtimeModelDisplay = computed(() => {
  if (modelStatus.value?.activeDisplay) return modelStatus.value.activeDisplay;
  const provider = modelStatus.value?.activeProvider || 'coding-plan';
  const model = modelStatus.value?.activeModel || 'o3';
  return `${provider} / ${model}`;
});

const runtimeModelProvider = computed(() => modelStatus.value?.activeProvider || 'coding-plan');

const runtimeModelName = computed(() => modelStatus.value?.activeModel || 'o3');

const runtimeUserInitial = computed(() => {
  const username = auth.username || 'Guest';
  return username.trim().slice(0, 1).toUpperCase();
});

const runtimeRoleLabel = computed(() => {
  if (!auth.profile) return 'LOGIN';
  return auth.roleCode === 'ADMIN' ? 'ADMIN' : auth.roleCode;
});

const runtimeResourceTitle = computed(() => {
  switch (activeResourceView.value) {
    case 'sessions':
      return 'Sessions';
    case 'agents':
      return 'Agent Runs';
    case 'skills':
      return 'Installed Skills';
    case 'tools':
      return 'Tool Permissions';
    case 'tasks':
      return 'Scheduled Tasks';
    case 'usage':
      return 'Token Usage';
    default:
      return 'Agent Console';
  }
});

const runtimeResourceSubtitle = computed(() => {
  switch (activeResourceView.value) {
    case 'sessions':
      return 'Local and server-backed sessions for the current Java engineer.';
    case 'agents':
      return 'Recent request traces restored from the runtime event stream.';
    case 'skills':
      return 'Local SKILL.md registry and Agent-visible execution capabilities.';
    case 'tools':
      return 'Tool providers resolved through runtime policy and allowed tool packs.';
    case 'tasks':
      return 'User-accessible scheduled tasks, next run time, and latest status.';
    case 'usage':
      return 'LLM calls, token totals, cache hit rate, provider and model distribution.';
    default:
      return 'Chat, trace, tools, memory, and logs share one runtime shell.';
  }
});

const runtimeSkillItems = computed<RuntimeSkill[]>(() => {
  return runtimeSkills.value?.definitions
    || runtimeOverview.value?.runtimeSkills?.definitions
    || [];
});

const runtimeToolItems = computed<RuntimeTool[]>(() => {
  return runtimeTools.value.length ? runtimeTools.value : runtimeOverview.value?.tools || [];
});

const runtimeTaskItems = computed<RuntimeTask[]>(() => {
  return runtimeTasks.value?.tasks || runtimeOverview.value?.tasks?.tasks || [];
});

const runtimeUsageSummary = computed<RuntimeUsageSummary>(() => {
  return runtimeUsage.value || runtimeOverview.value?.llmUsage || {};
});

const runtimeUsageCards = computed(() => [
  { label: 'Calls', value: formatMetric(runtimeUsageSummary.value.totalCalls), detail: runtimeUsageSummary.value.scope || '-' },
  { label: 'Total Tokens', value: formatMetric(runtimeUsageSummary.value.totalTokens), detail: 'prompt + completion' },
  { label: 'Prompt Tokens', value: formatMetric(runtimeUsageSummary.value.totalPromptTokens), detail: `cache ${formatRate(runtimeUsageSummary.value.promptCacheHitRate)}` },
  { label: 'Top Model', value: runtimeUsageSummary.value.topModel || '-', detail: runtimeUsageSummary.value.topProvider || '-' }
]);

const runtimeProviderItems = computed(() => runtimeModelProviders.value?.providers || runtimeOverview.value?.providers?.providers || []);

const runtimeRunItems = computed(() => {
  return runtimeRuns.value.length ? runtimeRuns.value : runtimeOverview.value?.agentRuns || [];
});

const runtimeResourceCount = computed(() => {
  switch (activeResourceView.value) {
    case 'agents':
      return runtimeRunItems.value.length;
    case 'skills':
      return runtimeSkillItems.value.length;
    case 'tools':
      return runtimeToolItems.value.length;
    case 'tasks':
      return runtimeTaskItems.value.length;
    case 'usage':
      return runtimeUsageSummary.value.totalCalls || 0;
    case 'sessions':
      return historySessions.value.length;
    default:
      return messages.value.length;
  }
});

watch(responseMode, (value) => {
  localStorage.setItem(RESPONSE_MODE_KEY, value);
});

watch(sidebarPinned, (value) => {
  localStorage.setItem(SIDEBAR_PINNED_KEY, value ? 'true' : 'false');
});

watch(() => messages.value.length, (length, previousLength) => {
  if (length > previousLength) {
    void motion.revealLastMessage();
  }
});

watch(() => traceEvents.value.length, (length, previousLength) => {
  if (length > previousLength) {
    void motion.revealTrace();
  }
});

watch(agentDecision, (value) => {
  if (value) {
    void motion.revealDecision();
  }
});

watch(pendingAction, (value) => {
  if (value) {
    void motion.revealActionCard();
  }
});

watch(activeInspectorTab, () => {
  void motion.revealInspectorPanel();
}, { flush: 'post' });

watch(sidebarOpen, (open) => {
  motion.animateSidebar(open, sidebarPinned.value);
}, { flush: 'post' });

watch(sidebarPinned, (pinned) => {
  motion.animateSidebar(sidebarOpen.value, pinned);
}, { flush: 'post' });

watch(() => auth.profile?.username, (username) => {
  if (username) {
    void loadModelStatus();
  } else {
    modelStatus.value = null;
    modelStatusError.value = '';
  }
});

function makeSessionKey() {
  return `agent:vue:${Date.now().toString(36)}:${crypto.randomUUID()}`;
}

function readResponseMode(): ChatResponseMode {
  const raw = localStorage.getItem(RESPONSE_MODE_KEY);
  return raw === 'fast' || raw === 'deep' || raw === 'tool' || raw === 'agent' ? raw : 'agent';
}

function readSidebarPinned() {
  return localStorage.getItem(SIDEBAR_PINNED_KEY) === 'true';
}

function readSessionTitles(): Record<string, string> {
  try {
    const raw = JSON.parse(localStorage.getItem(SESSION_TITLES_KEY) || '{}');
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
    return Object.fromEntries(
      Object.entries(raw)
        .filter(([key, value]) => typeof key === 'string' && typeof value === 'string' && value.trim())
        .map(([key, value]) => [key, String(value).trim()])
    );
  } catch {
    return {};
  }
}

function saveSessionTitles() {
  localStorage.setItem(SESSION_TITLES_KEY, JSON.stringify(customSessionTitles.value));
}

function toggleSidebarPinned() {
  sidebarPinned.value = !sidebarPinned.value;
  if (sidebarPinned.value) {
    sidebarHovered.value = true;
  }
}

function closeSidebarPreview() {
  if (!sidebarPinned.value) {
    sidebarHovered.value = false;
  }
}

function handleSidebarFocusOut(event: FocusEvent) {
  const current = event.currentTarget;
  const next = event.relatedTarget;
  if (current instanceof HTMLElement && (!next || !(next instanceof Node) || !current.contains(next))) {
    closeSidebarPreview();
  }
}

function setRuntimeStatus(message: string) {
  runtimeActionStatus.value = message;
}

function toggleSessionSearch() {
  sessionSearchOpen.value = !sessionSearchOpen.value;
  setRuntimeStatus(sessionSearchOpen.value ? '已打开 Sessions 搜索。' : '已关闭 Sessions 搜索。');
}

function toggleRuntimeSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value;
  setRuntimeStatus(sidebarCollapsed.value ? '侧栏已折叠。' : '侧栏已展开。');
}

async function activateRuntimeNav(key: RuntimeNavKey) {
  if (busy.value) return;
  activeResourceView.value = key;
  if (key === 'console') {
    sessionSearchOpen.value = false;
    runtimeResourceError.value = '';
    setRuntimeStatus('已回到 Agent Console。');
    focusComposer();
    return;
  }
  if (key === 'sessions') {
    sessionSearchOpen.value = true;
    setRuntimeStatus('已打开 Sessions 面板，可按标题或 sessionKey 过滤。');
  }
  if (key === 'agents') activeInspectorTab.value = 'trace';
  if (key === 'skills' || key === 'tools') activeInspectorTab.value = 'tools';
  await loadRuntimeResource(key);
}

async function loadRuntimeResource(view: RuntimeResourceView = activeResourceView.value) {
  if (!auth.profile) {
    runtimeResourceError.value = '当前资源需要登录后访问。';
    setRuntimeStatus('请先登录后查看 Runtime Console 资源。');
    return;
  }
  runtimeResourceLoading.value = true;
  runtimeResourceError.value = '';
  try {
    if (view === 'sessions') {
      await loadServerHistory();
    } else if (view === 'agents') {
      runtimeRuns.value = await getRuntimeRuns(20);
    } else if (view === 'skills') {
      runtimeSkills.value = await getRuntimeSkills();
    } else if (view === 'tools') {
      runtimeTools.value = await getRuntimeTools();
    } else if (view === 'tasks') {
      runtimeTasks.value = await getRuntimeTasks(30);
    } else if (view === 'usage') {
      runtimeUsage.value = await getRuntimeUsage(30);
    } else {
      runtimeOverview.value = await getRuntimeOverview();
      runtimeModelProviders.value = runtimeOverview.value.providers || runtimeModelProviders.value;
    }
    setRuntimeStatus(`${runtimeResourceTitle.value} 已刷新。`);
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Runtime resource loading failed.';
    runtimeResourceError.value = message;
    setRuntimeStatus(message);
  } finally {
    runtimeResourceLoading.value = false;
  }
}

async function openModelSwitcher() {
  if (!auth.profile) {
    setRuntimeStatus('请先登录后查看模型提供方。');
    return;
  }
  modelSwitcherOpen.value = !modelSwitcherOpen.value;
  if (!modelSwitcherOpen.value) return;
  await loadRuntimeModelProviders();
}

async function loadRuntimeModelProviders() {
  modelStatusLoading.value = true;
  modelStatusError.value = '';
  try {
    runtimeModelProviders.value = await getRuntimeModelProviders();
  } catch (error) {
    modelStatusError.value = error instanceof Error ? error.message : '模型提供方加载失败。';
  } finally {
    modelStatusLoading.value = false;
  }
}

async function selectRuntimeModel(providerId: string, model?: string) {
  if (!runtimeModelProviders.value?.canSwitch) {
    setRuntimeStatus('当前账号没有模型切换权限。');
    return;
  }
  modelStatusLoading.value = true;
  modelStatusError.value = '';
  try {
    runtimeModelProviders.value = await switchRuntimeModelProvider(providerId, model);
    await loadModelStatus();
    modelSwitcherOpen.value = false;
    setRuntimeStatus(`模型已切换到 ${providerId}${model ? ` / ${model}` : ''}。`);
  } catch (error) {
    modelStatusError.value = error instanceof Error ? error.message : '模型切换失败。';
    setRuntimeStatus(modelStatusError.value);
  } finally {
    modelStatusLoading.value = false;
  }
}

async function replayRunTrace(requestId?: string) {
  if (!requestId) {
    setRuntimeStatus('缺少 requestId，无法回放 trace。');
    return;
  }
  try {
    traceEvents.value = await getRunTrace(requestId, 80);
    activeInspectorTab.value = 'trace';
    setRuntimeStatus(`已回放 ${requestId} 的 trace。`);
  } catch (error) {
    setRuntimeStatus(error instanceof Error ? error.message : 'Trace 回放失败。');
  }
}

function openNotifications() {
  activeInspectorTab.value = 'logs';
  setRuntimeStatus('已打开 Logs。当前通知来源会映射到 trace、错误和模型状态。');
}

function renameCurrentSession() {
  const nextTitle = window.prompt('Session title', taskTitle.value)?.trim();
  if (!nextTitle) {
    setRuntimeStatus('已取消重命名。');
    return;
  }
  customSessionTitles.value = {
    ...customSessionTitles.value,
    [sessionKey.value]: nextTitle.slice(0, 60)
  };
  saveSessionTitles();
  upsertHistorySession();
  setRuntimeStatus('会话标题已更新。');
}

function toggleTaskMeta() {
  taskMetaExpanded.value = !taskMetaExpanded.value;
  setRuntimeStatus(taskMetaExpanded.value ? '已展开当前任务运行信息。' : '已收起当前任务运行信息。');
}

function startAttachFlow() {
  activeResourceView.value = 'skills';
  void loadRuntimeResource('skills');
  setRuntimeStatus('已打开本地文件相关 Skills，请选择后在对话中授权读取范围。');
}

async function copyText(value: string, label: string) {
  try {
    await navigator.clipboard.writeText(value);
    setRuntimeStatus(`${label} 已复制。`);
  } catch {
    setRuntimeStatus(`${label} 复制失败，请手动选择文本。`);
  }
}

async function focusComposer() {
  await nextTick();
  document.getElementById('agent-input')?.focus();
}

function addMessage(role: ChatMessage['role'], content: string, usedModel = '') {
  const message = {
    id: crypto.randomUUID(),
    role,
    content,
    model: usedModel,
    createdAt: Date.now()
  };
  messages.value = [...messages.value, message];
  persistCurrentMessages();
  return message.id;
}

function appendMessageContent(messageId: string, token: string) {
  const index = messages.value.findIndex((item) => item.id === messageId);
  if (index < 0 || !token) return;
  const current = messages.value[index];
  messages.value[index] = { ...current, content: `${current.content}${token}` };
  persistCurrentMessages();
}

function setMessageContent(messageId: string, content: string) {
  const index = messages.value.findIndex((item) => item.id === messageId);
  if (index < 0) return;
  messages.value[index] = { ...messages.value[index], content };
  persistCurrentMessages(true);
}

function pushTraceEvent(event: AgentTraceEvent) {
  const normalized: AgentTraceEvent = {
    ...event,
    stepName: event.stepName || '执行流程',
    type: event.type || 'agent',
    status: event.status || 'success',
    timestamp: event.timestamp || Date.now()
  };
  traceEvents.value = [...traceEvents.value.slice(-8), normalized];
}

function pushCapabilityEvent(event: AgentCapabilityEvent) {
  const normalized: AgentCapabilityEvent = {
    ...event,
    capabilityId: event.capabilityId || 'capability',
    toolset: event.toolset || 'tool',
    status: event.status || 'success',
    riskLevel: event.riskLevel || agentDecision.value?.riskLevel || 'read'
  };
  capabilityEvents.value = [...capabilityEvents.value.slice(-12), normalized];
}

function getMessageContent(messageId: string) {
  return messages.value.find((item) => item.id === messageId)?.content || '';
}

async function scrollBottom() {
  await nextTick();
  if (chatLog.value) {
    chatLog.value.scrollTop = chatLog.value.scrollHeight;
  }
}

function scheduleScrollBottom() {
  if (scrollQueued) return;
  scrollQueued = true;
  window.requestAnimationFrame(() => {
    scrollQueued = false;
    void scrollBottom();
  });
}

async function send() {
  const text = input.value.trim();
  if (!text || busy.value) return;
  if (!auth.profile) {
    addMessage('system', '请先登录，再开始和 Agent 对话。');
    await scrollBottom();
    return;
  }
  input.value = '';
  busy.value = true;
  streamStatus.value = '正在连接 Agent 流式通道';
  streamMeta.value = null;
  traceEvents.value = [];
  capabilityEvents.value = [];
  verificationEvent.value = null;
  agentDecision.value = null;
  pendingAction.value = null;
  actionStatus.value = '';
  historyStatus.value = '';
  firstTokenMs.value = null;
  stoppingStream.value = false;
  lastUserPrompt.value = text;
  const startedAt = Date.now();
  const streamController = new AbortController();
  activeStreamController = streamController;
  startElapsedTimer(startedAt);
  localStorage.setItem(SESSION_KEY, sessionKey.value);
  addMessage('user', text);
  const agentMessageId = addMessage('agent', '');
  void motion.nudgeComposer();
  await scrollBottom();
  try {
    await streamChat(
      {
        sessionKey: sessionKey.value,
        userId: auth.profile.username,
        message: text,
        channel: 'api',
        responseMode: responseMode.value
      },
      {
        onStatus(status) {
          streamStatus.value = status;
        },
        onMeta(meta) {
          streamMeta.value = meta;
        },
        onDecision(decision) {
          agentDecision.value = decision;
          streamStatus.value = `自动判断：${decision.intent} / ${decision.executionPath}`;
        },
        onTrace(event) {
          pushTraceEvent(event);
          if (event.detail) {
            streamStatus.value = `${event.stepName}：${event.detail}`;
          }
        },
        onToolCall(event) {
          pushCapabilityEvent(event);
          streamStatus.value = `${event.capabilityId}：${event.summary || event.status}`;
        },
        onSkillCall(event) {
          pushCapabilityEvent(event);
          streamStatus.value = `${event.capabilityId}：${event.summary || event.status}`;
        },
        onVerification(event) {
          verificationEvent.value = event;
          streamStatus.value = `校验证据：${event.summary || event.status}`;
        },
        onActionRequired(proposal) {
          pendingAction.value = proposal;
          actionStatus.value = '等待确认，确认前不会执行。';
        },
        onToken(token) {
          if (firstTokenMs.value == null) {
            firstTokenMs.value = Date.now() - startedAt;
          }
          appendMessageContent(agentMessageId, token);
          scheduleScrollBottom();
        },
        onError(message) {
          const current = getMessageContent(agentMessageId);
          setMessageContent(agentMessageId, current ? `${current}\n${message}` : message);
          void loadModelStatus();
        },
        onDone() {
          streamStatus.value = '已完成';
        }
      },
      { timeoutMs: 90_000, signal: streamController.signal }
    );
    if (!getMessageContent(agentMessageId).trim()) {
      setMessageContent(agentMessageId, '没有返回内容。');
    }
  } catch (error) {
    if (stoppingStream.value) {
      const current = getMessageContent(agentMessageId).trim();
      setMessageContent(agentMessageId, current ? `${current}\n\n已停止生成。` : '已停止生成。');
    } else {
      setMessageContent(agentMessageId, `请求失败：${error instanceof Error ? error.message : '未知错误'}`);
      void loadModelStatus();
    }
  } finally {
    busy.value = false;
    stoppingStream.value = false;
    if (activeStreamController === streamController) {
      activeStreamController = null;
    }
    stopElapsedTimer();
    streamStatus.value = '';
    persistCurrentMessages(true);
    await scrollBottom();
  }
}

function stopStreaming() {
  if (!busy.value || !activeStreamController) return;
  stoppingStream.value = true;
  streamStatus.value = '正在停止生成';
  activeStreamController.abort('user-stop');
}

async function retryLastPrompt() {
  if (!canRetryLastPrompt.value) return;
  input.value = lastUserPrompt.value;
  await send();
}

async function confirmPendingAction() {
  if (!pendingAction.value || busy.value) return;
  actionStatus.value = '正在确认执行...';
  try {
    const result = await confirmActionProposal(pendingAction.value.proposalId, sessionKey.value);
    actionStatus.value = result.message || '已确认。';
    addMessage('agent', result.message || '动作已确认。');
    pendingAction.value = null;
    await scrollBottom();
  } catch (error) {
    actionStatus.value = error instanceof Error ? error.message : '确认失败。';
  }
}

async function cancelPendingAction() {
  if (!pendingAction.value || busy.value) return;
  actionStatus.value = '正在取消...';
  try {
    const result = await cancelActionProposal(pendingAction.value.proposalId);
    actionStatus.value = result.message || '已取消。';
    addMessage('system', result.message || '已取消，未执行任何动作。');
    pendingAction.value = null;
    await scrollBottom();
  } catch (error) {
    actionStatus.value = error instanceof Error ? error.message : '取消失败。';
  }
}

function newSession() {
  persistCurrentMessages(true);
  sessionKey.value = makeSessionKey();
  localStorage.setItem(SESSION_KEY, sessionKey.value);
  messages.value = [];
  input.value = '';
  traceEvents.value = [];
  capabilityEvents.value = [];
  verificationEvent.value = null;
  agentDecision.value = null;
  pendingAction.value = null;
  taskMetaExpanded.value = false;
  historyStatus.value = '已创建新会话。';
  setRuntimeStatus('已创建新会话。');
  void focusComposer();
}

function usePrompt(prompt: string) {
  input.value = prompt;
}

async function runShortcut(prompt: string) {
  if (busy.value) return;
  input.value = prompt;
  await send();
}

async function switchSession(targetSessionKey: string) {
  if (busy.value || !targetSessionKey || targetSessionKey === sessionKey.value) return;
  persistCurrentMessages(true);
  sessionKey.value = targetSessionKey;
  localStorage.setItem(SESSION_KEY, targetSessionKey);
  messages.value = readLocalMessages(targetSessionKey);
  input.value = '';
  historyStatus.value = messages.value.length ? '已从本地恢复历史。' : '正在读取服务端历史。';
  await loadServerHistory();
  await scrollBottom();
}

function clearCurrentHistory() {
  if (busy.value) return;
  localStorage.removeItem(messagesStorageKey(sessionKey.value));
  historySessions.value = historySessions.value.filter((item) => item.sessionKey !== sessionKey.value);
  saveHistorySessions();
  messages.value = [];
  historyStatus.value = '当前会话历史已清空。';
}

async function loadServerHistory() {
  if (!auth.profile || !sessionKey.value) return;
  historyLoading.value = true;
  try {
    const response = await getChatHistory(sessionKey.value, 80);
    const serverMessages = normalizeMessages(response.messages);
    if (serverMessages.length > messages.value.length) {
      messages.value = serverMessages;
      historyStatus.value = '已同步服务端历史。';
      persistCurrentMessages(true);
    } else if (messages.value.length) {
      historyStatus.value = '已保留本地最新历史。';
    }
  } catch (error) {
    historyStatus.value = error instanceof Error ? `历史同步失败：${error.message}` : '历史同步失败。';
  } finally {
    historyLoading.value = false;
  }
}

async function loadModelStatus() {
  if (!auth.profile) {
    modelStatus.value = null;
    modelStatusError.value = '';
    return;
  }
  modelStatusLoading.value = true;
  modelStatusError.value = '';
  try {
    modelStatus.value = await getModelStatus();
  } catch (error) {
    modelStatusError.value = error instanceof Error ? error.message : '模型状态检测失败';
  } finally {
    modelStatusLoading.value = false;
  }
}

function refreshModelStatus() {
  void Promise.all([loadModelStatus(), loadRuntimeModelProviders()]);
}

function messagesStorageKey(key: string) {
  return `springclaw.frontend.chat.messages:${key}`;
}

function readLocalMessages(key: string): ChatMessage[] {
  try {
    return normalizeMessages(JSON.parse(localStorage.getItem(messagesStorageKey(key)) || '[]'));
  } catch {
    return [];
  }
}

function readHistorySessions(): ChatSessionSummary[] {
  try {
    const raw = JSON.parse(localStorage.getItem(SESSIONS_KEY) || '[]');
    if (!Array.isArray(raw)) return [];
    return raw
      .filter((item) => typeof item?.sessionKey === 'string')
      .map((item) => ({
        sessionKey: item.sessionKey,
        title: typeof item.title === 'string' && item.title.trim() ? item.title : '未命名会话',
        updatedAt: typeof item.updatedAt === 'number' ? item.updatedAt : 0,
        messageCount: typeof item.messageCount === 'number' ? item.messageCount : 0
      }))
      .filter((item) => !item.title.startsWith('请先登录'))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, 20);
  } catch {
    return [];
  }
}

function normalizeMessages(raw: unknown): ChatMessage[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((item) => item && typeof item.content === 'string' && item.content.trim())
    .map((item) => {
      const role = item.role === 'agent' || item.role === 'system' || item.role === 'user' ? item.role : 'agent';
      return {
        id: typeof item.id === 'string' && item.id ? item.id : crypto.randomUUID(),
        role,
        content: item.content,
        model: typeof item.model === 'string' ? item.model : '',
        createdAt: typeof item.createdAt === 'number' && item.createdAt > 0 ? item.createdAt : Date.now()
      };
    })
    .slice(-80);
}

function persistCurrentMessages(immediate = false) {
  if (persistTimer) {
    window.clearTimeout(persistTimer);
    persistTimer = undefined;
  }
  if (immediate) {
    writeCurrentMessages();
    return;
  }
  persistTimer = window.setTimeout(writeCurrentMessages, 360);
}

function writeCurrentMessages() {
  persistTimer = undefined;
  localStorage.setItem(messagesStorageKey(sessionKey.value), JSON.stringify(messages.value.slice(-80)));
  if (messages.value.some((item) => item.role === 'user' && item.content.trim())) {
    upsertHistorySession();
  }
}

function upsertHistorySession() {
  const summary: ChatSessionSummary = {
    sessionKey: sessionKey.value,
    title: customSessionTitles.value[sessionKey.value] || inferSessionTitle(messages.value),
    updatedAt: Date.now(),
    messageCount: messages.value.length
  };
  historySessions.value = [
    summary,
    ...historySessions.value.filter((item) => item.sessionKey !== sessionKey.value)
  ].slice(0, 20);
  saveHistorySessions();
}

function saveHistorySessions() {
  localStorage.setItem(SESSIONS_KEY, JSON.stringify(historySessions.value));
}

function inferSessionTitle(list: ChatMessage[]) {
  const firstUserMessage = list.find((item) => item.role === 'user' && item.content.trim());
  const title = firstUserMessage?.content || list.find((item) => item.content.trim())?.content || '未命名会话';
  return title.replace(/\s+/g, ' ').slice(0, 34);
}

function formatHistoryTime(value: number) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function formatRelativeHistoryTime(value: number) {
  if (!value) return '-';
  const delta = Date.now() - value;
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (delta < minute) return 'just now';
  if (delta < hour) return `${Math.max(1, Math.round(delta / minute))}m ago`;
  if (delta < day) return `${Math.round(delta / hour)}h ago`;
  if (delta < day * 2) return 'Yesterday';
  return `${Math.round(delta / day)} days ago`;
}

function formatMetric(value: number | string | undefined) {
  if (value == null || value === '') return '-';
  const numberValue = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numberValue)) return String(value);
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(numberValue);
}

function formatRate(value: number | undefined) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return '0%';
  return `${Math.round(value * 1000) / 10}%`;
}

function formatDateTimeLabel(value?: string | number) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function formatMs(value: number) {
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`;
}

function formatClock(value: number) {
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function qualityFromFlatEvent(score: number, level: string, reason: string): AgentQualityScore {
  const normalized = clampScore(score);
  return {
    overallScore: normalized,
    routeScore: normalized,
    toolScore: normalized,
    evidenceScore: normalized,
    reflectionScore: normalized,
    answerScore: normalized,
    costScore: normalized,
    riskScore: normalized,
    level,
    reason,
    reasons: reason ? [reason] : []
  };
}

function clampScore(value: number) {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}

function normalizeStepStatus(status?: string): RunStepStatus {
  if (status === 'success') return 'completed';
  if (status === 'started') return 'running';
  if (status === 'failed') return 'failed';
  return 'pending';
}

function normalizeActionName(label: string) {
  return label
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '_')
    .replace(/[^\w\u4e00-\u9fa5-]/g, '_')
    .slice(0, 48);
}

function traceStatusLabel(status?: string) {
  if (status === 'started') return '进行中';
  if (status === 'success') return '完成';
  if (status === 'failed') return '异常';
  if (status === 'skipped') return '跳过';
  return status || '记录';
}

function traceDurationLabel(event: AgentTraceEvent) {
  return typeof event.durationMs === 'number' && event.durationMs > 0 ? formatMs(event.durationMs) : '';
}

function startElapsedTimer(startedAt: number) {
  stopElapsedTimer();
  elapsedSeconds.value = 0;
  elapsedTimer = window.setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000);
  }, 1000);
}

function stopElapsedTimer() {
  if (elapsedTimer) {
    window.clearInterval(elapsedTimer);
    elapsedTimer = undefined;
  }
}

onMounted(async () => {
  try {
    await auth.loadMe();
  } finally {
    await Promise.all([loadServerHistory(), loadModelStatus(), loadRuntimeResource('console')]);
  }
});

onUnmounted(() => {
  persistCurrentMessages(true);
  stopElapsedTimer();
  if (persistTimer) {
    window.clearTimeout(persistTimer);
  }
});
</script>

<template>
  <div ref="studioRoot" class="agent-studio runtime-console" :class="{ 'sidebar-pinned': sidebarPinned, 'sidebar-preview': !sidebarPinned, 'sidebar-collapsed': sidebarCollapsed }">
    <main class="runtime-shell">
      <aside class="runtime-app-sidebar" :class="'runtime-left-sidebar'" aria-label="SpringClaw Runtime navigation">
        <RouterLink class="runtime-sidebar-brand" to="/" aria-label="SpringClaw home">
          <span class="runtime-brand-glyph" aria-hidden="true"><i></i><i></i><i></i></span>
          <span>
            <strong>SpringClaw</strong>
            <small>SpringClaw AI Runtime</small>
          </span>
        </RouterLink>

        <button class="runtime-new-session" type="button" :disabled="busy" @click="newSession">
          <span aria-hidden="true">+</span>
          <strong>New Session</strong>
          <kbd>K</kbd>
        </button>

        <nav class="runtime-sidebar-nav" aria-label="Runtime modules">
          <button
            v-for="item in engineerNavItems"
            :key="item.key"
            class="runtime-nav-item"
            :class="[item.className, { active: activeResourceView === item.key }]"
            :aria-current="activeResourceView === item.key ? 'page' : undefined"
            type="button"
            :disabled="busy"
            @click="activateRuntimeNav(item.key)"
          >
            <span aria-hidden="true"></span>
            <strong>{{ item.label }}</strong>
          </button>
        </nav>

        <section class="runtime-recent-panel" aria-label="Recent sessions">
          <div class="runtime-section-title">
            <span>Recent Sessions</span>
            <button type="button" title="Search sessions" :aria-pressed="sessionSearchOpen" @click="toggleSessionSearch">Search</button>
          </div>
          <input
            v-if="sessionSearchOpen"
            v-model="sessionSearchQuery"
            class="runtime-session-search"
            type="search"
            placeholder="Filter sessions..."
          />
          <div v-if="historySessions.length" class="runtime-history-list">
            <button
              v-for="item in filteredHistorySessions.slice(0, 5)"
              :key="item.sessionKey"
              class="history-item"
              :class="{ active: item.sessionKey === sessionKey }"
              type="button"
              :disabled="busy"
              @click="switchSession(item.sessionKey)"
            >
              <span aria-hidden="true"></span>
              <strong>{{ item.title }}</strong>
              <small>{{ formatRelativeHistoryTime(item.updatedAt) }}</small>
            </button>
          </div>
          <div v-else class="runtime-history-list">
            <div class="runtime-history-empty">
              登录并发起真实任务后，会话会出现在这里。
            </div>
          </div>
          <p v-if="historyStatus" class="history-status">{{ historyStatus }}</p>
        </section>

        <section class="runtime-product-card" aria-label="SpringClaw version">
          <div>
            <strong>SpringClaw</strong>
            <span>v1.2.0</span>
          </div>
          <p>Better Call Edwin. Building AI that solves real problems.</p>
          <div class="runtime-card-landscape" aria-hidden="true"></div>
        </section>

        <button class="runtime-collapse-button" type="button" @click="toggleRuntimeSidebar">
          <span aria-hidden="true">‹</span>
          {{ sidebarCollapsed ? 'Expand' : 'Collapse' }}
        </button>
      </aside>

      <section class="runtime-stage" aria-label="SpringClaw Agent workspace">
        <header class="runtime-worktop" :class="'studio-nav'" aria-label="Agent Runtime status bar">
          <div class="runtime-mode-switch" aria-label="Execution mode">
            <button
              type="button"
              :class="{ active: responseMode !== 'deep' }"
              :disabled="busy"
              @click="responseMode = 'agent'"
            >
              simplified
            </button>
            <button
              type="button"
              :class="{ active: responseMode === 'deep' }"
              :disabled="busy"
              @click="responseMode = 'deep'"
            >
              opar
            </button>
          </div>

          <div class="runtime-model-switcher-wrap">
            <button class="runtime-model-pill" :class="modelHealthClass" type="button" :disabled="modelStatusLoading" :aria-expanded="modelSwitcherOpen" @click="openModelSwitcher">
              <span class="runtime-pill-icon" aria-hidden="true"></span>
              <strong>{{ runtimeModelProvider }}</strong>
              <small>{{ runtimeModelName }}</small>
              <i class="model-health-dot" aria-hidden="true"></i>
              <em>{{ runtimeHealthLabel }}</em>
            </button>
            <div v-if="modelSwitcherOpen" class="runtime-model-menu" role="menu" aria-label="Model providers">
              <div class="runtime-model-menu-head">
                <strong>Model Router</strong>
                <span>{{ runtimeModelProviders?.canSwitch ? 'ADMIN write access' : 'Read only' }}</span>
              </div>
              <button
                v-for="provider in runtimeProviderItems"
                :key="provider.providerId"
                class="runtime-provider-row"
                :class="{ active: provider.active }"
                type="button"
                :disabled="!provider.available || modelStatusLoading"
                @click="selectRuntimeModel(provider.providerId, provider.model)"
              >
                <span>
                  <strong>{{ provider.providerId }}</strong>
                  <small>{{ provider.model || provider.defaultModel || '-' }}</small>
                </span>
                <em>{{ provider.available ? (provider.active ? 'Active' : 'Available') : 'Unavailable' }}</em>
              </button>
              <p v-if="modelStatusError" class="runtime-model-error">{{ modelStatusError }}</p>
            </div>
          </div>

          <nav class="runtime-worktop-actions" aria-label="Runtime quick links">
            <button class="runtime-quick-link" type="button" @click="activateRuntimeNav('tasks')">Tasks</button>
            <button class="runtime-quick-link" type="button" @click="activateRuntimeNav('usage')">Usage<span class="live-dot" aria-hidden="true"></span></button>
            <button class="runtime-notification-button" type="button" title="Notifications" aria-label="Notifications" @click="openNotifications">
              <svg aria-hidden="true" viewBox="0 0 24 24" focusable="false">
                <path d="M18 10.8c0-3.4-2.2-6.1-6-6.1s-6 2.7-6 6.1v2.6l-1.5 2.5h15L18 13.4v-2.6Z" />
                <path d="M9.6 18.5c.4 1 1.2 1.5 2.4 1.5s2-.5 2.4-1.5" />
              </svg>
            </button>
            <RouterLink class="runtime-user-chip" to="/admin">
              <span class="runtime-avatar" aria-hidden="true">{{ runtimeUserInitial }}</span>
              <strong>{{ auth.username || 'Guest' }}</strong>
              <small>{{ runtimeRoleLabel }}</small>
            </RouterLink>
          </nav>
        </header>

        <div class="runtime-workspace">
          <section class="runtime-main-panel" aria-label="Agent conversation">
            <div class="studio-canvas runtime-chat-canvas">
              <div class="studio-heading runtime-main-heading task-header">
                <div class="task-title-block">
                  <div class="task-title-row">
                    <h2>{{ taskTitle }}</h2>
                    <button class="task-icon-button" type="button" title="Rename session" @click="renameCurrentSession">Edit</button>
                  </div>
                  <div class="task-meta-row" aria-label="Current task metadata">
                    <span>Session ID <strong>{{ compactSessionId }}</strong></span>
                    <span>User <strong>{{ auth.username || 'Guest' }}</strong></span>
                    <span>Channel <strong>API</strong></span>
                    <span>Created <strong>{{ taskCreatedLabel }}</strong></span>
                    <button type="button" :aria-expanded="taskMetaExpanded" @click="toggleTaskMeta">More</button>
                  </div>
                  <div v-if="taskMetaExpanded" class="task-meta-details">
                    <button type="button" @click="copyText(sessionKey, 'Session ID')">Copy Session</button>
                    <button type="button" @click="copyText(currentRequestId, 'Request ID')">Copy Request</button>
                    <button type="button" :disabled="historyLoading || !auth.profile" @click="loadServerHistory">
                      {{ historyLoading ? 'Syncing' : 'Sync History' }}
                    </button>
                    <button type="button" :disabled="busy || !messages.length" @click="clearCurrentHistory">Clear Local</button>
                  </div>
                  <p v-if="runtimeActionStatus" class="runtime-action-status">{{ runtimeActionStatus }}</p>
                </div>
              </div>

              <section v-if="activeResourceView !== 'console'" class="runtime-resource-panel" aria-live="polite">
                <header class="runtime-resource-header">
                  <div>
                    <span class="empty-state-kicker">{{ activeResourceView }}</span>
                    <h3>{{ runtimeResourceTitle }}</h3>
                    <p>{{ runtimeResourceSubtitle }}</p>
                  </div>
                  <div class="runtime-resource-actions">
                    <strong>{{ formatMetric(runtimeResourceCount) }}</strong>
                    <button type="button" :disabled="runtimeResourceLoading" @click="loadRuntimeResource(activeResourceView)">
                      {{ runtimeResourceLoading ? 'Loading' : 'Refresh' }}
                    </button>
                  </div>
                </header>

                <p v-if="runtimeResourceError" class="runtime-resource-error">{{ runtimeResourceError }}</p>

                <div v-if="activeResourceView === 'sessions'" class="runtime-session-resource-list">
                  <button
                    v-for="item in filteredHistorySessions"
                    :key="item.sessionKey"
                    class="runtime-resource-row"
                    type="button"
                    :disabled="busy"
                    @click="switchSession(item.sessionKey)"
                  >
                    <span>
                      <strong>{{ item.title }}</strong>
                      <small>{{ item.sessionKey }}</small>
                    </span>
                    <em>{{ formatRelativeHistoryTime(item.updatedAt) }}</em>
                  </button>
                  <div v-if="!filteredHistorySessions.length" class="empty-history">暂无服务端或本地会话记录。</div>
                </div>

                <div v-else-if="activeResourceView === 'agents'" class="runtime-run-list">
                  <button
                    v-for="run in runtimeRunItems"
                    :key="run.requestId || run.sessionKey"
                    class="runtime-resource-row"
                    type="button"
                    @click="replayRunTrace(run.requestId)"
                  >
                    <span>
                      <strong>{{ run.lastStep || run.status || 'Run' }}</strong>
                      <small>{{ run.requestId || '-' }}</small>
                    </span>
                    <em>{{ run.status || '-' }}</em>
                  </button>
                  <div v-if="!runtimeRunItems.length" class="empty-history">暂无持久化运行 trace。</div>
                </div>

                <div v-else-if="activeResourceView === 'skills'" class="runtime-skill-grid">
                  <article v-for="skill in runtimeSkillItems" :key="skill.skillId" class="runtime-resource-card">
                    <div>
                      <span>{{ skill.sourceType || 'SKILL' }}</span>
                      <strong>{{ skill.name || skill.skillId }}</strong>
                      <p>{{ skill.description || 'No description' }}</p>
                    </div>
                    <footer>
                      <small>{{ skill.executorType || skill.preferredMode || '-' }}</small>
                      <em>{{ skill.enabled === false ? 'Disabled' : 'Enabled' }}</em>
                    </footer>
                  </article>
                  <div v-if="!runtimeSkillItems.length" class="empty-history">未发现当前账号可见的本地 Skills。</div>
                </div>

                <div v-else-if="activeResourceView === 'tools'" class="runtime-tool-grid">
                  <article v-for="tool in runtimeToolItems" :key="tool.name" class="runtime-resource-card">
                    <div>
                      <span>{{ tool.riskLevel || 'read' }}</span>
                      <strong>{{ tool.name }}</strong>
                      <p>{{ tool.description || tool.toolset }}</p>
                    </div>
                    <footer>
                      <small>{{ tool.toolset }}</small>
                      <em>{{ tool.allow === false ? 'Denied' : 'Allowed' }}</em>
                    </footer>
                  </article>
                  <div v-if="!runtimeToolItems.length" class="empty-history">未发现可用工具 Provider。</div>
                </div>

                <div v-else-if="activeResourceView === 'tasks'" class="runtime-task-list">
                  <article v-for="task in runtimeTaskItems" :key="task.taskId" class="runtime-task-row">
                    <div>
                      <span>{{ task.enabled === false || task.enabled === 0 ? 'Paused' : 'Active' }}</span>
                      <strong>{{ task.name }}</strong>
                      <small>{{ task.scheduleType }} · {{ task.scheduleExpression }}</small>
                    </div>
                    <div>
                      <small>Next {{ formatDateTimeLabel(task.nextRunAt) }}</small>
                      <em>{{ task.lastStatus || '-' }}</em>
                    </div>
                  </article>
                  <div v-if="!runtimeTaskItems.length" class="empty-history">当前账号暂无定时任务。</div>
                </div>

                <div v-else class="runtime-usage-grid">
                  <article v-for="card in runtimeUsageCards" :key="card.label" class="runtime-usage-card">
                    <span>{{ card.label }}</span>
                    <strong>{{ card.value }}</strong>
                    <small>{{ card.detail }}</small>
                  </article>
                  <div class="runtime-usage-recent">
                    <div v-for="(record, index) in runtimeUsageSummary.recent || []" :key="index" class="runtime-resource-row">
                      <span>
                        <strong>{{ record.model || record.providerId || 'usage' }}</strong>
                        <small>{{ record.requestId || record.sessionKey || '-' }}</small>
                      </span>
                      <em>{{ record.totalTokens || 0 }} tokens</em>
                    </div>
                  </div>
                </div>
              </section>

              <section v-else ref="chatLog" class="stitch-chat runtime-chat-log" aria-live="polite">
                <div v-if="messages.length === 0" class="command-run-preview">
                  <article class="runtime-empty-brief">
                    <div>
                      <span class="empty-state-kicker">Ready</span>
                      <h3>把目标交给 SpringClaw</h3>
                      <p>输入任务后，Agent 会自动选择模型、工具、Skill 和确认路径；需要写入或执行时会先请求确认。</p>
                    </div>
                    <div class="command-step-list" aria-label="Task execution preview">
                      <template v-if="visibleRunSteps.length">
                        <div
                          v-for="(step, index) in visibleRunSteps.slice(0, 4)"
                          :key="step.label"
                          class="command-step-row"
                          :class="`is-${step.status}`"
                        >
                          <span class="step-index">{{ step.status === 'completed' ? '✓' : index + 1 }}</span>
                          <div>
                            <strong>{{ step.label }}</strong>
                            <small>{{ step.detail }}</small>
                          </div>
                          <em>{{ step.duration }}</em>
                        </div>
                      </template>
                      <div v-else class="empty-history">
                        真实 trace 会在发送任务后出现。当前没有 mock 步骤。
                      </div>
                    </div>
                  </article>
                </div>

                <AgentMessage v-for="message in messages" :key="message.id" :message="message" />

                <article v-if="busy && !messages.some((item) => item.role === 'agent' && item.content.trim())" class="message agent loading-message">
                  <div class="message-meta"><span>SpringClaw Agent</span><span>Streaming</span></div>
                  <div class="bubble">
                    <span class="thinking" aria-hidden="true"><i></i><i></i><i></i></span>
                    {{ streamStatus || '正在连接模型、组织上下文或等待首字...' }}
                  </div>
                </article>
              </section>

              <footer id="composer" class="stitch-composer runtime-composer">
                <div class="quick-prompt-strip" aria-label="Quick prompts">
                  <button
                    v-for="mode in modeActions"
                    :key="mode.label"
                    class="mode-chip-button"
                    type="button"
                    :disabled="busy"
                    @click="usePrompt(mode.prompt)"
                  >
                    {{ mode.label }}
                  </button>
                </div>
                <div class="composer-headline">
                  <label for="agent-input">Ask SpringClaw</label>
                  <span v-if="busy">{{ responseTiming || streamStatus || '生成中' }}</span>
                  <span v-else>{{ modelStatusDetail }}</span>
                </div>
                <textarea
                  id="agent-input"
                  v-model="input"
                  placeholder="Type your message..."
                  @keydown.enter.exact.prevent="send"
                />
                <div class="composer-tools">
                  <div class="composer-left-actions" aria-label="Input helpers">
                    <button class="composer-icon-button" type="button" title="Use authorized local files" @click="startAttachFlow">Files</button>
                    <button class="composer-icon-button" type="button" title="Code context" @click="activateRuntimeNav('skills')">Code</button>
                    <button class="composer-icon-button" type="button" title="Tool routing" @click="activateRuntimeNav('tools')">Tools</button>
                  </div>
                  <div class="composer-actions">
                    <button class="btn-subtle light retry-button" type="button" :disabled="!canRetryLastPrompt" @click="retryLastPrompt">
                      重试
                    </button>
                    <button v-if="busy" class="btn-subtle stop-button" type="button" :disabled="stoppingStream" @click="stopStreaming">
                      <span v-if="stoppingStream" class="button-spinner" aria-hidden="true"></span>
                      {{ stoppingStream ? '停止中' : '停止' }}
                    </button>
                    <button v-else class="btn-primary send-button" type="button" :disabled="!input.trim()" @click="send">
                      <span aria-hidden="true"></span>
                      发送
                    </button>
                  </div>
                </div>

                <div v-if="agentDecision" class="agent-decision-pill">
                  <span>Auto Decision</span>
                  <strong>{{ decisionLabel }}</strong>
                  <small>{{ agentDecision.reason }}</small>
                </div>
                <div v-if="pendingAction" class="action-required-card">
                  <div>
                    <span class="risk-badge">{{ pendingAction.riskLevel }}</span>
                    <h3>{{ pendingAction.title }}</h3>
                    <p>{{ pendingAction.summary }}</p>
                    <small>确认前不会执行。proposal: {{ pendingAction.proposalId.slice(0, 8) }}</small>
                  </div>
                  <div class="action-required-buttons">
                    <button class="btn-subtle light" type="button" :disabled="busy" @click="cancelPendingAction">取消</button>
                    <button class="btn-primary" type="button" :disabled="busy" @click="confirmPendingAction">确认执行</button>
                  </div>
                </div>
                <p v-if="actionStatus" class="stream-status">{{ actionStatus }}</p>
                <p v-if="streamMeta" class="stream-status">
                  {{ streamMeta.responseMode || responseMode }} · {{ streamMeta.executionMode || 'routing' }} · {{ streamMeta.intent || 'general' }}
                </p>
                <div v-if="traceEvents.length" class="run-trace-strip" aria-label="Agent execution flow">
                  <span class="trace-live-dot" aria-hidden="true"></span>
                  <strong>{{ latestTrace?.stepName }}</strong>
                  <small>{{ latestTrace?.detail || traceStatusLabel(latestTrace?.status) }}</small>
                </div>
              </footer>
            </div>
          </section>

          <aside class="studio-sidebar runtime-inspector" aria-label="Run Inspector">
            <div class="sidebar-drawer runtime-inspector-drawer">
              <div class="inspector-tabs" role="tablist" aria-label="Inspector tabs">
                <button
                  v-for="tab in inspectorTabs"
                  :key="tab.value"
                  type="button"
                  role="tab"
                  :aria-selected="activeInspectorTab === tab.value"
                  :class="{ active: activeInspectorTab === tab.value }"
                  @click="activeInspectorTab = tab.value"
                >
                  {{ tab.label }}
                </button>
              </div>

              <section v-if="activeInspectorTab === 'trace'" class="inspector-panel run-timeline-card">
                <div class="run-facts-card">
                  <div class="context-row"><span>Request ID</span><strong>{{ currentRequestId }}</strong></div>
                  <div class="context-row"><span>Question</span><strong>{{ resolvedQuestionLabel }}</strong></div>
                  <div class="context-row"><span>Status</span><strong>{{ runStatus }}</strong></div>
                  <div class="context-row"><span>Start Time</span><strong>{{ taskCreatedLabel }}</strong></div>
                  <div class="context-row"><span>Duration</span><strong>{{ runDurationLabel }}</strong></div>
                  <div class="context-row"><span>Model</span><strong>{{ runtimeModelDisplay }}</strong></div>
                  <div class="context-row"><span>Quality</span><strong>{{ agentQuality ? `${agentQuality.overallScore} · ${qualityLevelLabel}` : '-' }}</strong></div>
                </div>
                <div v-if="agentQuality" class="quality-score-card" :class="`is-${agentQuality.level}`">
                  <div class="quality-score-head">
                    <span>Agent Quality</span>
                    <strong>{{ agentQuality.overallScore }}</strong>
                    <em>{{ qualityLevelLabel }}</em>
                  </div>
                  <div class="quality-metric-list">
                    <div v-for="metric in qualityMetricRows" :key="metric.key" class="quality-metric-row">
                      <span>{{ metric.label }}</span>
                      <div class="quality-meter" aria-hidden="true">
                        <i :style="{ width: `${clampScore(metric.score)}%` }"></i>
                      </div>
                      <strong>{{ clampScore(metric.score) }}</strong>
                    </div>
                  </div>
                  <p>{{ agentQuality.reason || verificationEvent?.summary || '等待评分原因。' }}</p>
                </div>
                <div class="panel-title-row sub-panel-title">
                  <div>
                    <p class="eyebrow">Steps</p>
                  </div>
                  <span class="trace-count">{{ visibleRunSteps.length }}</span>
                </div>
                <div class="run-timeline">
                  <template v-if="visibleRunSteps.length">
                    <div
                      v-for="step in visibleRunSteps"
                      :key="`${step.label}-${step.status}`"
                      class="trace-row"
                      :class="`is-${step.status}`"
                    >
                      <span class="trace-dot" aria-hidden="true"></span>
                      <div>
                        <strong>{{ step.label }}</strong>
                        <small>{{ step.detail }}</small>
                      </div>
                      <em>{{ step.tools || step.duration }}</em>
                    </div>
                  </template>
                  <div v-else class="empty-history">
                    暂无真实 trace。发送任务后会显示 decision、tool call、verification 和 final。
                  </div>
                </div>
                <div class="current-step-card">
                  <div class="panel-title-row">
                    <strong>Current Step</strong>
                    <span>{{ currentStep?.duration || runDurationLabel }}</span>
                  </div>
                  <p>{{ currentStep?.label || '等待任务' }}</p>
                  <pre><code>{{ executionPayload }}</code></pre>
                </div>
              </section>

              <section v-else-if="activeInspectorTab === 'tools'" class="inspector-panel">
                <div v-if="agentDecision" class="decision-card compact-decision-card">
                  <div class="context-row"><span>Intent</span><strong>{{ agentDecision.intent }}</strong></div>
                  <div class="context-row"><span>Path</span><strong>{{ agentDecision.executionPath }}</strong></div>
                  <div class="context-row"><span>Risk</span><strong>{{ agentDecision.riskLevel }}</strong></div>
                  <div class="context-row"><span>Confirm</span><strong>{{ agentDecision.requiresConfirmation ? '需要' : '不需要' }}</strong></div>
                </div>
                <div class="tool-call-list">
                  <article v-for="tool in visibleToolCalls" :key="`${tool.name}-${tool.status}`" class="tool-call-card" :class="`risk-${tool.risk}`">
                    <span>{{ tool.status }}</span>
                    <strong>{{ tool.name }}</strong>
                    <small>{{ tool.toolset }} · risk: {{ tool.risk }} · duration: {{ tool.duration }}</small>
                    <p v-if="tool.summary">{{ tool.summary }}</p>
                  </article>
                  <div v-if="!visibleToolCalls.length" class="empty-history">
                    暂无真实 tool/skill call。本页不再展示 mock 调用。
                  </div>
                </div>
                <div v-if="pendingAction || visibleToolCalls.some((tool) => tool.status === 'proposal')" class="tool-proposal-card">
                  <strong>Confirmation Proposal</strong>
                  <p>{{ pendingAction?.summary || '写入文件、执行脚本、创建任务等副作用动作会先生成确认卡片。' }}</p>
                </div>
              </section>

              <section v-else-if="activeInspectorTab === 'memory'" class="inspector-panel">
                <div class="memory-summary-card">
                  <div class="context-row"><span>Short Session</span><strong>{{ messages.length }} messages</strong></div>
                  <div class="context-row"><span>Vector Store</span><strong>Redis</strong></div>
                  <div class="context-row"><span>Fallback</span><strong>Workspace policy</strong></div>
                </div>
                <article v-for="memory in memoryContexts" :key="memory.source" class="memory-card">
                  <div>
                    <strong>{{ memory.source }}</strong>
                    <small>{{ memory.detail }}</small>
                  </div>
                  <span>{{ memory.score }}</span>
                </article>
                <p v-if="!memoryContexts.length" class="empty-history">暂无真实 memory chunks。后端返回召回片段后会显示来源和相关度。</p>
              </section>

              <section v-else class="inspector-panel">
                <div class="panel-title-row">
                  <div>
                    <p class="eyebrow">Logs</p>
                    <h2>请求日志</h2>
                  </div>
                  <button class="btn-subtle light small-button" type="button" :disabled="modelStatusLoading || !auth.profile" @click="refreshModelStatus">
                    {{ modelStatusLoading ? '检测中' : '刷新' }}
                  </button>
                </div>
                <div class="log-filter-row">
                  <label for="request-id-filter">requestId</label>
                  <input id="request-id-filter" :value="currentRequestId" readonly />
                </div>
                <div class="runtime-log-viewer" aria-label="Request logs">
                  <div v-for="log in visibleLogs" :key="`${log.at}-${log.message}`" class="log-row" :class="`is-${log.level}`">
                    <span>{{ log.at }}</span>
                    <strong>{{ log.level }}</strong>
                    <code>{{ log.message }}</code>
                  </div>
                  <div v-if="!visibleLogs.length" class="empty-history">
                    暂无真实日志。等待本次请求产生 trace。
                  </div>
                </div>
                <div class="model-health-card compact-health" :class="modelHealthClass">
                  <div class="context-row"><span>Provider</span><strong>{{ modelStatus?.activeProvider || '-' }}</strong></div>
                  <div class="context-row"><span>Model</span><strong>{{ modelStatus?.activeModel || '-' }}</strong></div>
                  <div class="context-row"><span>Checked</span><strong>{{ modelCheckedAtLabel }}</strong></div>
                </div>
                <LoginPanel v-if="!auth.profile" />
              </section>
            </div>
          </aside>
        </div>
      </section>
    </main>
  </div>
</template>
