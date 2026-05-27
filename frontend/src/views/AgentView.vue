<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import AgentMessage from '../components/AgentMessage.vue';
import LoginPanel from '../components/LoginPanel.vue';
import { getChatHistory, getModelStatus, streamChat } from '../services/api';
import { useAuthStore } from '../stores/auth';
import type { ChatMessage, ChatResponseMode, ChatSessionSummary, ChatStreamMeta, ModelStatusResponse } from '../types';

const auth = useAuthStore();
const SESSION_KEY = 'springclaw.frontend.session';
const SESSIONS_KEY = 'springclaw.frontend.chat.sessions.v1';
const RESPONSE_MODE_KEY = 'springclaw.frontend.responseMode';
const SIDEBAR_PINNED_KEY = 'springclaw.frontend.sidebarPinned';
const input = ref('');
const sessionKey = ref(localStorage.getItem(SESSION_KEY) || makeSessionKey());
const messages = ref<ChatMessage[]>(readLocalMessages(sessionKey.value));
const historySessions = ref<ChatSessionSummary[]>(readHistorySessions());
const busy = ref(false);
const responseMode = ref<ChatResponseMode>(readResponseMode());
const sidebarPinned = ref(readSidebarPinned());
const sidebarHovered = ref(false);
const streamStatus = ref('');
const streamMeta = ref<ChatStreamMeta | null>(null);
const modelStatus = ref<ModelStatusResponse | null>(null);
const modelStatusLoading = ref(false);
const modelStatusError = ref('');
const historyStatus = ref('');
const historyLoading = ref(false);
const elapsedSeconds = ref(0);
const firstTokenMs = ref<number | null>(null);
const lastUserPrompt = ref('');
const stoppingStream = ref(false);
const chatLog = ref<HTMLElement | null>(null);
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

const responseModes: Array<{ value: ChatResponseMode; label: string; description: string }> = [
  { value: 'agent', label: 'Agent', description: '默认，模型主导并可调用工具' },
  { value: 'fast', label: '快速', description: '轻量回答，减少多轮规划' },
  { value: 'deep', label: '深度', description: '强制 OPAR 深度链路' },
  { value: 'tool', label: '工具优先', description: '适合明确执行 skill/tool' }
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

const sessionState = computed(() => {
  if (!auth.profile) return '需要登录';
  if (busy.value) return '执行中';
  return messages.value.length ? '会话进行中' : '等待输入';
});

const currentSessionSummary = computed(() => {
  return historySessions.value.find((item) => item.sessionKey === sessionKey.value);
});

const responseTiming = computed(() => {
  if (!busy.value) return '';
  if (firstTokenMs.value != null) {
    return `首字 ${formatMs(firstTokenMs.value)}，已运行 ${elapsedSeconds.value}s`;
  }
  return `等待首字 ${elapsedSeconds.value}s`;
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

watch(responseMode, (value) => {
  localStorage.setItem(RESPONSE_MODE_KEY, value);
});

watch(sidebarPinned, (value) => {
  localStorage.setItem(SIDEBAR_PINNED_KEY, value ? 'true' : 'false');
});

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

function newSession() {
  persistCurrentMessages(true);
  sessionKey.value = makeSessionKey();
  localStorage.setItem(SESSION_KEY, sessionKey.value);
  messages.value = [];
  input.value = '';
  historyStatus.value = '已创建新会话。';
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
  void loadModelStatus();
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
  if (messages.value.length) {
    upsertHistorySession();
  }
}

function upsertHistorySession() {
  const summary: ChatSessionSummary = {
    sessionKey: sessionKey.value,
    title: inferSessionTitle(messages.value),
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

function formatMs(value: number) {
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`;
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
    await Promise.all([loadServerHistory(), loadModelStatus()]);
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
  <div class="agent-studio" :class="{ 'sidebar-pinned': sidebarPinned, 'sidebar-preview': !sidebarPinned }">
    <header class="clean-nav studio-nav" aria-label="Agent 工作台导航">
      <RouterLink class="site-brand" to="/">
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>
          <strong>SpringClaw</strong>
          <small>Agent Console</small>
        </span>
      </RouterLink>

      <nav class="nav-links" aria-label="工作台导航">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/admin">后台</RouterLink>
        <RouterLink :to="{ path: '/agent', hash: '#composer' }">输入</RouterLink>
      </nav>
    </header>

    <main class="studio-layout">
      <section class="studio-canvas" aria-label="Agent 对话区域">
        <div class="studio-heading">
          <div class="studio-heading-copy">
            <p class="eyebrow">Interactive Agent Console</p>
            <h1>SpringClaw Agent 工作台</h1>
            <p>输入目标，系统会带着身份、会话上下文和可用能力进入后端主链路；页面只保留真实可触发的动作。</p>
            <div class="model-health-strip" :class="modelHealthClass" role="status" aria-live="polite">
              <span class="model-health-dot" aria-hidden="true"></span>
              <strong>{{ modelStatusLabel }}</strong>
              <small>{{ modelStatusDetail }}</small>
              <button class="inline-refresh" type="button" :disabled="modelStatusLoading || !auth.profile" @click="refreshModelStatus">
                {{ modelStatusLoading ? '检测中' : '刷新' }}
              </button>
            </div>
          </div>
          <div class="studio-status-panel" aria-label="当前会话状态">
            <span>
              <small>状态</small>
              <strong>{{ sessionState }}</strong>
            </span>
            <span>
              <small>模式</small>
              <strong>{{ activeResponseMode.label }}</strong>
            </span>
            <span>
              <small>消息</small>
              <strong>{{ messages.length }}</strong>
            </span>
            <span>
              <small>模型</small>
              <strong>{{ modelStatusLabel }}</strong>
            </span>
          </div>
        </div>

        <section ref="chatLog" class="stitch-chat" aria-live="polite">
          <div v-if="messages.length === 0" class="starter-panel">
            <div class="starter-mark" aria-hidden="true"><span></span></div>
            <h2>今天先处理哪件事？</h2>
            <p>选择一个动作，或直接描述目标。这里的按钮都会把任务写进输入框，不再是装饰标签。</p>
            <div class="suggestion-row">
              <button v-for="prompt in prompts" :key="prompt" class="suggestion-chip" @click="usePrompt(prompt)">
                {{ prompt }}
              </button>
            </div>
          </div>

          <AgentMessage v-for="message in messages" :key="message.id" :message="message" />

          <article v-if="busy && !messages.some((item) => item.role === 'agent')" class="message agent loading-message">
            <div class="message-meta"><span>SpringClaw Agent</span><span>执行中</span></div>
            <div class="bubble"><span class="thinking"><i></i><i></i><i></i></span> {{ streamStatus || '正在规划路径、组织上下文或调用能力...' }}</div>
          </article>
        </section>

        <footer id="composer" class="stitch-composer">
          <label for="agent-input">Ask SpringClaw</label>
          <textarea
            id="agent-input"
            v-model="input"
            placeholder="描述目标，例如：帮我分析 skill 注册层、执行层和定时任务之间的关系。"
            @keydown.enter.exact.prevent="send"
          />
          <div class="composer-tools">
            <div class="mode-chips" aria-label="常用动作">
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
            <div class="composer-actions">
              <button class="btn-subtle light retry-button" type="button" :disabled="!canRetryLastPrompt" @click="retryLastPrompt">
                重试上一条
              </button>
              <button v-if="busy" class="btn-subtle stop-button" type="button" :disabled="stoppingStream" @click="stopStreaming">
                <span v-if="stoppingStream" class="button-spinner" aria-hidden="true"></span>
                {{ stoppingStream ? '停止中' : '停止' }}
              </button>
              <button v-else class="btn-primary send-button" :disabled="!input.trim()" @click="send">
                发送
              </button>
            </div>
          </div>
          <div class="response-mode-toggle" aria-label="回答模式">
            <button
              v-for="mode in responseModes"
              :key="mode.value"
              type="button"
              :class="{ active: responseMode === mode.value }"
              :disabled="busy"
              @click="responseMode = mode.value"
            >
              <strong>{{ mode.label }}</strong>
              <small>{{ mode.description }}</small>
            </button>
          </div>
          <p v-if="busy && streamStatus" class="stream-status">{{ streamStatus }}</p>
          <p v-if="streamMeta" class="stream-status">
            {{ streamMeta.responseMode || responseMode }} · {{ streamMeta.executionMode || 'routing' }} · {{ streamMeta.intent || 'general' }}
          </p>
          <p v-if="responseTiming" class="stream-status">{{ responseTiming }}</p>
        </footer>
      </section>

      <aside
        class="studio-sidebar"
        :class="{ 'is-open': sidebarOpen, 'is-pinned': sidebarPinned }"
        aria-label="运行状态"
        :aria-expanded="sidebarOpen"
        @mouseenter="sidebarHovered = true"
        @mouseleave="closeSidebarPreview"
        @focusin="sidebarHovered = true"
        @focusout="handleSidebarFocusOut"
      >
        <button
          class="sidebar-rail"
          type="button"
          :aria-label="sidebarOpen ? '运行控制台已展开' : '展开运行控制台'"
          @click="sidebarHovered = true"
        >
          <span class="sidebar-rail-mark"></span>
          <span class="sidebar-rail-text">Control</span>
          <span class="sidebar-rail-pill">{{ sidebarPinned ? 'PIN' : 'HOVER' }}</span>
        </button>

        <div class="sidebar-drawer" :aria-hidden="!sidebarOpen" :inert="!sidebarOpen">
          <header class="sidebar-toolbar">
            <div>
              <p class="eyebrow">Control Deck</p>
              <h2>运行控制台</h2>
              <span>{{ sidebarModeLabel }} · {{ activeResponseMode.label }} mode · {{ modelStatusLabel }}</span>
            </div>
            <button
              class="pin-toggle"
              type="button"
              :class="{ active: sidebarPinned }"
              :aria-pressed="sidebarPinned"
              @click="toggleSidebarPinned"
            >
              <span class="pin-toggle-dot" aria-hidden="true"></span>
              {{ sidebarPinned ? '取消固定' : '固定' }}
            </button>
          </header>

          <LoginPanel />

          <section class="soft-card model-health-card" :class="modelHealthClass">
            <div class="panel-title-row">
              <div>
                <p class="eyebrow">Model Health</p>
                <h2>{{ modelStatus?.activeDisplay || modelStatusLabel }}</h2>
              </div>
              <button class="btn-subtle light small-button" :disabled="modelStatusLoading || !auth.profile" @click="refreshModelStatus">
                {{ modelStatusLoading ? '检测中' : '刷新' }}
              </button>
            </div>
            <div class="model-health-meter">
              <span class="model-health-dot" aria-hidden="true"></span>
              <div>
                <strong>{{ modelStatus?.available ? '在线' : (auth.profile ? '需要处理' : '未登录') }}</strong>
                <small>{{ modelStatusDetail }}</small>
              </div>
            </div>
            <div class="context-row"><span>Provider</span><strong>{{ modelStatus?.activeProvider || '-' }}</strong></div>
            <div class="context-row"><span>Model</span><strong>{{ modelStatus?.activeModel || '-' }}</strong></div>
            <div class="context-row"><span>Checked</span><strong>{{ modelCheckedAtLabel }}</strong></div>
            <p class="model-health-note">{{ modelStatus?.recommendation || '成熟 Agent 工具应把模型可用性前置展示，而不是等用户发消息后才暴露错误。' }}</p>
          </section>

          <section class="soft-card">
            <div class="panel-title-row">
              <div>
                <p class="eyebrow">Session</p>
                <h2>{{ sessionState }}</h2>
              </div>
              <button class="btn-subtle light small-button" @click="newSession">新会话</button>
            </div>
            <input v-model="sessionKey" aria-label="Session key" class="session-input" />
            <p v-if="currentSessionSummary" class="session-note">
              当前历史：{{ currentSessionSummary.messageCount }} 条，{{ formatHistoryTime(currentSessionSummary.updatedAt) }}
            </p>
          </section>

          <section class="soft-card history-card">
            <div class="panel-title-row">
              <div>
                <p class="eyebrow">History</p>
                <h2>历史会话</h2>
              </div>
              <button class="btn-subtle light small-button" :disabled="busy || historyLoading" @click="loadServerHistory">
                {{ historyLoading ? '同步中' : '同步' }}
              </button>
            </div>
            <p>最近会话会先存在浏览器本地；登录后会再尝试从后端 message_event 同步当前会话。</p>
            <div v-if="historySessions.length" class="history-list">
              <button
                v-for="item in historySessions"
                :key="item.sessionKey"
                class="history-item"
                :class="{ active: item.sessionKey === sessionKey }"
                :disabled="busy"
                @click="switchSession(item.sessionKey)"
              >
                <strong>{{ item.title }}</strong>
                <small>{{ item.messageCount }} 条 · {{ formatHistoryTime(item.updatedAt) }}</small>
              </button>
            </div>
            <p v-else class="empty-history">还没有历史。发送第一条消息后这里会自动出现。</p>
            <p v-if="historyStatus" class="history-status">{{ historyStatus }}</p>
            <button class="btn-subtle light admin-link-button" :disabled="busy || messages.length === 0" @click="clearCurrentHistory">
              清空当前会话显示
            </button>
          </section>

          <section class="soft-card context-card">
            <p class="eyebrow">Context</p>
            <div class="context-row"><span>User</span><strong>{{ auth.username || '-' }}</strong></div>
            <div class="context-row"><span>Role</span><strong>{{ auth.roleCode }}</strong></div>
            <div class="context-row"><span>Provider</span><strong>{{ modelStatus?.activeDisplay || modelStatusLabel }}</strong></div>
            <div class="context-row"><span>Messages</span><strong>{{ messages.length }}</strong></div>
          </section>

          <section class="soft-card model-switch-card">
            <p class="eyebrow">Workspace Review</p>
            <h2>本地文件能力</h2>
            <p>支持当前项目审查，也支持读取你配置授权的本机目录；敏感配置只报告位置，不展示值。</p>
            <button
              v-for="item in workspaceShortcuts"
              :key="item.label"
              class="model-option"
              :disabled="busy"
              @click="runShortcut(item.prompt)"
            >
              <span>
                <strong>{{ item.label }}</strong>
                <small>{{ item.description }}</small>
              </span>
              <i aria-hidden="true">↗</i>
            </button>
          </section>

          <section class="soft-card model-switch-card">
            <p class="eyebrow">Project Actions</p>
            <h2>项目操作</h2>
            <p>这里保留真实可执行动作，不再放和项目配置不一致的模型切换按钮。</p>
            <button
              v-for="item in operationShortcuts"
              :key="item.label"
              class="model-option"
              :disabled="busy"
              @click="runShortcut(item.prompt)"
            >
              <span>
                <strong>{{ item.label }}</strong>
                <small>{{ item.description }}</small>
              </span>
              <i aria-hidden="true">↗</i>
            </button>
            <RouterLink class="btn-subtle light admin-link-button" to="/admin">进入管理员后台</RouterLink>
          </section>

          <section class="soft-card summary-card">
            <p class="eyebrow">Runtime</p>
            <article v-for="item in capabilitySummary" :key="item.label">
              <span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </article>
          </section>
        </div>
      </aside>
    </main>
  </div>
</template>
