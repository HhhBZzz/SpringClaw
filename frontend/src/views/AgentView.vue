<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue';
import AgentMessage from '../components/AgentMessage.vue';
import LoginPanel from '../components/LoginPanel.vue';
import { adminConsoleUrl, streamChat } from '../services/api';
import { useAuthStore } from '../stores/auth';
import type { ChatMessage } from '../types';

const auth = useAuthStore();
const input = ref('');
const sessionKey = ref(localStorage.getItem('springclaw.frontend.session') || makeSessionKey());
const messages = ref<ChatMessage[]>([]);
const busy = ref(false);
const model = ref('');
const streamStatus = ref('');
const chatLog = ref<HTMLElement | null>(null);

const promptModes = ['Agent', 'Skill', 'Task', 'Insight'] as const;
const prompts = [
  '帮我讲清楚当前项目的 Agent 执行链路。',
  '请审查当前项目源码，看看架构是否合理，有没有冗余垃圾代码和风险点。',
  '梳理现有 skills，并判断哪些适合继续收口。',
  '生成一个每天 9 点运行 web_crawler 的任务草稿。'
] as const;

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
  { label: 'Session', value: 'local persisted key' },
  { label: 'Delivery', value: 'answer / execution log ready' }
] as const;

const modelShortcuts = [
  {
    label: 'DeepSeek V4 Pro',
    description: '高质量推理与 Agent 编排主力模型',
    prompt: '切换到 deepseek-v4-pro'
  },
  {
    label: 'Qwen 3.5 Plus',
    description: '日常问答和长文本整理',
    prompt: '切换到 qwen3.5-plus'
  },
  {
    label: 'Qwen Coder Plus',
    description: '代码分析、重构和工程说明',
    prompt: '切换到 qwen3-coder-plus'
  }
] as const;

const sessionState = computed(() => {
  if (!auth.profile) return '需要登录';
  if (busy.value) return '执行中';
  return messages.value.length ? '会话进行中' : '等待输入';
});

function makeSessionKey() {
  return `agent:vue:${Date.now().toString(36)}:${Math.random().toString(36).slice(2, 8)}`;
}

function addMessage(role: ChatMessage['role'], content: string, usedModel = '') {
  const message = {
    id: crypto.randomUUID(),
    role,
    content,
    model: usedModel,
    createdAt: Date.now()
  };
  messages.value.push(message);
  return message;
}

async function scrollBottom() {
  await nextTick();
  if (chatLog.value) {
    chatLog.value.scrollTop = chatLog.value.scrollHeight;
  }
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
  localStorage.setItem('springclaw.frontend.session', sessionKey.value);
  addMessage('user', text);
  const agentMessage = addMessage('agent', '');
  await scrollBottom();
  try {
    await streamChat(
      {
        sessionKey: sessionKey.value,
        userId: auth.profile.username,
        message: text,
        channel: 'api'
      },
      {
        onStatus(status) {
          streamStatus.value = status;
        },
        onToken(token) {
          agentMessage.content += token;
          void scrollBottom();
        },
        onError(message) {
          agentMessage.content += agentMessage.content ? `\n${message}` : message;
        },
        onDone() {
          streamStatus.value = '已完成';
        }
      }
    );
    if (!agentMessage.content.trim()) {
      agentMessage.content = '没有返回内容。';
    }
  } catch (error) {
    agentMessage.content = `请求失败：${error instanceof Error ? error.message : '未知错误'}`;
  } finally {
    busy.value = false;
    streamStatus.value = '';
    await scrollBottom();
  }
}

function newSession() {
  sessionKey.value = makeSessionKey();
  localStorage.setItem('springclaw.frontend.session', sessionKey.value);
  messages.value = [];
  input.value = '';
}

function usePrompt(prompt: string) {
  input.value = prompt;
}

async function runShortcut(prompt: string) {
  if (busy.value) return;
  input.value = prompt;
  await send();
}

onMounted(async () => {
  await auth.loadMe();
});
</script>

<template>
  <div class="agent-studio">
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
        <a :href="adminConsoleUrl()">后台</a>
        <a href="#composer">输入</a>
      </nav>
    </header>

    <main class="studio-layout">
      <section class="studio-canvas" aria-label="Agent 对话区域">
        <div class="studio-heading">
          <p class="eyebrow">Interactive agent console</p>
          <h1>从一句话开始，把项目任务跑起来。</h1>
          <p>输入目标，SpringClaw 会带着用户身份、会话上下文和可用能力进入后端 Agent 主链路。</p>
        </div>

        <section ref="chatLog" class="stitch-chat" aria-live="polite">
          <div v-if="messages.length === 0" class="starter-panel">
            <div class="starter-mark" aria-hidden="true"><span></span></div>
            <h2>今天先处理哪件事？</h2>
            <p>可以让它解释项目、调用 skill、生成定时任务草稿，或者直接审查本地源码。</p>
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
            <div class="mode-chips" aria-label="执行模式">
              <span v-for="mode in promptModes" :key="mode">{{ mode }}</span>
            </div>
            <button class="btn-primary send-button" :disabled="busy || !input.trim()" @click="send">
              <span v-if="busy" class="button-spinner" aria-hidden="true"></span>
              {{ busy ? '流式输出中' : '发送' }}
            </button>
          </div>
          <p v-if="busy && streamStatus" class="stream-status">{{ streamStatus }}</p>
        </footer>
      </section>

      <aside class="studio-sidebar" aria-label="运行状态">
        <LoginPanel />

        <section class="soft-card">
          <div class="panel-title-row">
            <div>
              <p class="eyebrow">Session</p>
              <h2>{{ sessionState }}</h2>
            </div>
            <button class="btn-subtle light small-button" @click="newSession">新会话</button>
          </div>
          <input v-model="sessionKey" aria-label="Session key" class="session-input" />
        </section>

        <section class="soft-card context-card">
          <p class="eyebrow">Context</p>
          <div class="context-row"><span>User</span><strong>{{ auth.username || '-' }}</strong></div>
          <div class="context-row"><span>Role</span><strong>{{ auth.roleCode }}</strong></div>
          <div class="context-row"><span>Model</span><strong>{{ model || '待响应' }}</strong></div>
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
          <p class="eyebrow">Model Switch</p>
          <h2>模型切换</h2>
          <p>这里不是装饰按钮，会通过聊天控制指令进入后端模型注册层。</p>
          <button
            v-for="item in modelShortcuts"
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
          <a class="btn-subtle light admin-link-button" :href="adminConsoleUrl()">进入管理员后台</a>
        </section>

        <section class="soft-card summary-card">
          <p class="eyebrow">Runtime</p>
          <article v-for="item in capabilitySummary" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </article>
        </section>
      </aside>
    </main>
  </div>
</template>
