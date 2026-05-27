<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import LoginPanel from '../components/LoginPanel.vue';
import { getAdminDashboard, getAuditLogs } from '../services/api';
import { useAuthStore } from '../stores/auth';
import type { ActiveSession, AdminDashboard, AuditLogPage, AuditRecord, RecentUserActivity } from '../types';

const auth = useAuthStore();
const loadError = ref('');
const adminLoading = ref(false);
const adminError = ref('');
const dashboard = ref<AdminDashboard | null>(null);
const auditLogs = ref<AuditLogPage | null>(null);
const roleLabel = computed(() => auth.roleCode && auth.roleCode !== '-' ? auth.roleCode : '未登录');
const isAdmin = computed(() => auth.roleCode === 'ADMIN');

const adminCards = [
  {
    title: '模型通道',
    value: 'DeepSeek V4 Pro',
    body: '当前前端只展示主力模型入口，具体切换仍走后端模型注册层和审计链路。',
    hash: '#runtime-details',
    action: '查看配置'
  },
  {
    title: 'Skill Runtime',
    value: '统一执行',
    body: 'Python skills、Markdown skills 和任务调度入口都收口到后端统一运行时。',
    hash: '#runtime-details',
    action: '查看运行时'
  },
  {
    title: '任务底座',
    value: 'XXL-Job dispatcher',
    body: '定时任务由调度底座扫描到期任务，再分发给 skill 或 Agent 主链路。',
    hash: '#task-status',
    action: '看调度状态'
  },
  {
    title: '本地文件边界',
    value: '授权根目录',
    body: 'Agent 可读取项目与授权目录，敏感配置只报告位置，不直接泄露值。',
    hash: '#file-boundary',
    action: '看权限状态'
  }
] as const;

const runtimeFacts = [
  { label: '模型入口', value: 'DeepSeek V4 Pro', detail: '前端不再放不可用的模型切换按钮，模型选择由后端 provider 注册层控制。' },
  { label: '对话链路', value: 'Agent 默认', detail: '默认走模型主导流式输出；快速、深度、工具优先作为显式模式。' },
  { label: 'Skill 运行时', value: '统一 Executor', detail: '页面只展示真实可运行 skill，具体执行交给后端 SkillRuntimeService。' },
  { label: '数据观测', value: 'Admin API', detail: 'Token、审计和运行时数量来自 /api/admin/**，不是静态假指标。' }
] as const;

const taskFacts = [
  'XXL-Job 只负责定时触发 dispatcher，不为每个用户任务动态新增 handler。',
  'ScheduledTask 决定执行 skill 还是完整 Agent prompt，执行结果进入任务日志。',
  '失败任务不会阻塞其他任务；错误信息会落到 ScheduledTaskExecution。'
] as const;

const fileBoundaryFacts = [
  '当前项目源码可由工作区工具读取，用于架构审查、冗余分析和代码定位。',
  '电脑其他目录需要先配置授权根目录，再由 local-files / workspace-review 类能力读取。',
  '敏感配置按位置和键名汇报，不在前端直接展示真实密钥值。'
] as const;

const auditItems = [
  '登录、注册与退出统一走 /api/auth',
  '管理接口继续由 /api/admin/** 提供',
  '旧 static/admin 与 static/agent 页面已移除',
  '后台页面由 Vue 路由 /admin 承载'
] as const;

const metricCards = computed(() => {
  const tokenStats = dashboard.value?.tokenStats;
  const auditStats = dashboard.value?.auditStats || {};
  return [
    { label: '活跃 Token', value: formatNumber(tokenStats?.activeTokenCount), detail: `Redis ${formatNumber(tokenStats?.redisTokenCount)} / Local ${formatNumber(tokenStats?.localTokenCount)}` },
    { label: '活跃用户', value: formatNumber(tokenStats?.activeUserCount), detail: `即将过期 ${formatNumber(tokenStats?.expiringSoonCount)}` },
    { label: '用户数', value: formatNumber(dashboard.value?.userCount), detail: `DB ${dashboard.value?.dbEnabled ? '已启用' : '未启用'}` },
    { label: '运行时 Skills', value: formatNumber(dashboard.value?.runtimeSkillCount ?? dashboard.value?.runtimeSkills?.count ?? 0), detail: renderSourceCounts(dashboard.value?.runtimeSkills?.sourceCounts) },
    { label: '审计事件', value: formatNumber(readNumber(auditStats, 'total') ?? auditLogs.value?.total), detail: `最近 ${auditLogs.value?.records?.length ?? 0} 条已加载` },
    { label: '会话 TTL', value: tokenStats?.tokenTtlSeconds ? `${Math.round(tokenStats.tokenTtlSeconds / 3600)}h` : '-', detail: tokenStats?.redisBacked ? 'Token 存储 Redis' : 'Token 本地兜底' }
  ];
});

const activeSessions = computed<ActiveSession[]>(() => dashboard.value?.activeSessions || []);
const recentUsers = computed<RecentUserActivity[]>(() => dashboard.value?.recentUsers || []);
const auditRecords = computed<AuditRecord[]>(() => auditLogs.value?.records || []);

onMounted(async () => {
  try {
    await auth.loadMe();
    loadError.value = auth.error;
    if (isAdmin.value) {
      await loadAdminData();
    }
  } catch (error) {
    console.error('Failed to load admin identity', error);
    loadError.value = '登录状态加载失败，请确认后端服务正常。';
  }
});

watch(() => auth.roleCode, async (role) => {
  if (role === 'ADMIN' && !dashboard.value && !adminLoading.value) {
    await loadAdminData();
  }
});

async function loadAdminData() {
  if (!isAdmin.value) return;
  adminLoading.value = true;
  adminError.value = '';
  try {
    const [dashboardPayload, auditPayload] = await Promise.all([
      getAdminDashboard(),
      getAuditLogs({ page: 1, size: 12 })
    ]);
    dashboard.value = dashboardPayload;
    auditLogs.value = auditPayload;
  } catch (error) {
    console.error('Failed to load admin dashboard', error);
    adminError.value = error instanceof Error ? error.message : '后台数据加载失败。';
  } finally {
    adminLoading.value = false;
  }
}

function formatNumber(value: unknown) {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return new Intl.NumberFormat('zh-CN').format(value);
}

function formatTime(value: unknown) {
  if (typeof value === 'number' && value > 0) {
    return new Date(value).toLocaleString('zh-CN');
  }
  if (typeof value === 'string' && value.trim()) {
    return value.replace('T', ' ').slice(0, 19);
  }
  return '-';
}

function preview(value: unknown, max = 86) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  if (!text) return '-';
  return text.length > max ? `${text.slice(0, max)}...` : text;
}

function readNumber(source: Record<string, unknown>, key: string) {
  const value = source[key];
  return typeof value === 'number' ? value : undefined;
}

function renderSourceCounts(sourceCounts?: Record<string, number>) {
  if (!sourceCounts || Object.keys(sourceCounts).length === 0) return '等待运行时数据';
  return Object.entries(sourceCounts).map(([key, value]) => `${key} ${value}`).join(' / ');
}
</script>

<template>
  <div class="agent-studio admin-studio">
    <header class="clean-nav studio-nav" aria-label="Admin 后台导航">
      <RouterLink class="site-brand" to="/">
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>
          <strong>SpringClaw</strong>
          <small>Admin Console</small>
        </span>
      </RouterLink>

      <nav class="nav-links" aria-label="后台导航">
        <RouterLink to="/">首页</RouterLink>
        <RouterLink to="/agent">Agent</RouterLink>
        <RouterLink :to="{ path: '/admin', hash: '#admin-status' }">运行状态</RouterLink>
        <RouterLink :to="{ path: '/admin', hash: '#token-status' }">Token</RouterLink>
        <RouterLink :to="{ path: '/admin', hash: '#audit-logs' }">审计</RouterLink>
      </nav>
    </header>

    <main class="admin-layout">
      <section class="admin-hero" aria-labelledby="admin-title">
        <p class="eyebrow">Admin Console</p>
        <h1 id="admin-title">后台看运行事实，不看装饰指标。</h1>
        <p>
          这里是 SpringClaw 的管理驾驶舱：Token、用户、会话、审计、Skill 运行时和调度状态都从后端接口读取，
          前端只负责把真实状态讲清楚。
        </p>
        <div class="quiet-actions admin-actions">
          <RouterLink class="btn-primary" to="/agent">进入 Agent Console</RouterLink>
          <button class="btn-subtle light" type="button" :disabled="adminLoading || !isAdmin" @click="loadAdminData">
            {{ adminLoading ? '刷新中' : '刷新后台数据' }}
          </button>
        </div>
        <p v-if="adminError" class="status danger admin-inline-status">{{ adminError }}</p>
      </section>

      <aside class="admin-login">
        <LoginPanel />
      </aside>

      <section id="admin-status" class="admin-grid" aria-label="后台能力概览">
        <RouterLink v-for="item in adminCards" :key="item.title" class="product-card product-link admin-card" :to="{ path: '/admin', hash: item.hash }">
          <span>{{ item.title }}</span>
          <h2>{{ item.value }}</h2>
          <p>{{ item.body }}</p>
          <strong class="card-action">{{ item.action }}</strong>
        </RouterLink>
      </section>

      <section v-if="isAdmin" class="admin-metric-grid" aria-label="实时后台指标">
        <article v-for="item in metricCards" :key="item.label" class="soft-card admin-metric-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <p>{{ item.detail }}</p>
        </article>
      </section>

      <section v-else class="soft-card admin-audit-card" aria-label="管理员权限提示">
        <div>
          <p class="eyebrow">Permission</p>
          <h2>需要 ADMIN 角色才能查看后台数据。</h2>
          <p>当前角色：{{ roleLabel }}。请先使用管理员账号登录，后端 <code>/api/admin/**</code> 也会强制校验管理员权限。</p>
        </div>
      </section>

      <section id="runtime-details" class="soft-card admin-data-card" aria-label="运行时配置说明">
        <div class="admin-section-head">
          <div>
            <p class="eyebrow">Runtime Details</p>
            <h2>运行时配置与真实入口</h2>
            <p>这些卡片不是跳转占位，而是后台当前能解释、能追踪、能继续排障的事实。</p>
          </div>
        </div>
        <div class="admin-fact-grid">
          <article v-for="item in runtimeFacts" :key="item.label" class="admin-fact-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
            <p>{{ item.detail }}</p>
          </article>
        </div>
      </section>

      <section id="task-status" class="soft-card admin-data-card" aria-label="定时任务调度状态">
        <div class="admin-section-head">
          <div>
            <p class="eyebrow">Task Runtime</p>
            <h2>定时任务底座</h2>
            <p>这里说明任务调度的真实边界；后续如果补任务列表接口，入口会继续落在这个区域，不再跳空页。</p>
          </div>
        </div>
        <div class="capability-list admin-capability-list">
          <span v-for="item in taskFacts" :key="item">{{ item }}</span>
        </div>
      </section>

      <section id="file-boundary" class="soft-card admin-data-card" aria-label="本地文件读取边界">
        <div class="admin-section-head">
          <div>
            <p class="eyebrow">Local File Boundary</p>
            <h2>本地文件能力边界</h2>
            <p>Agent 能看见什么，不靠前端话术硬说，而是由后端授权根目录和工具策略共同限制。</p>
          </div>
        </div>
        <div class="capability-list admin-capability-list">
          <span v-for="item in fileBoundaryFacts" :key="item">{{ item }}</span>
        </div>
      </section>

      <section id="token-status" class="soft-card admin-data-card" aria-label="Token 和会话统计">
        <div class="admin-section-head">
          <div>
            <p class="eyebrow">Token Runtime</p>
            <h2>Token 记录与活跃会话</h2>
            <p>这里来自 `/api/admin/manage/dashboard`，展示当前登录 token 的存储位置、过期时间和所属用户。</p>
          </div>
          <button class="btn-subtle light small-button" type="button" :disabled="adminLoading || !isAdmin" @click="loadAdminData">刷新</button>
        </div>
        <div v-if="isAdmin" class="admin-table-wrap">
          <table class="admin-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>角色</th>
                <th>Token</th>
                <th>存储</th>
                <th>过期时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="session in activeSessions" :key="`${session.username}-${session.tokenPreview}`">
                <td>{{ session.username }}</td>
                <td><span class="status ok">{{ session.roleCode }}</span></td>
                <td><code>{{ session.tokenPreview }}</code></td>
                <td>{{ session.storage }}</td>
                <td>{{ formatTime(session.expireAt) }}</td>
              </tr>
              <tr v-if="activeSessions.length === 0">
                <td colspan="5">暂无活跃 token。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="admin-empty-state">
          <strong>当前不是管理员视图</strong>
          <p>Token 表格入口已经保留，不会再跳空；登录 ADMIN 后这里会自动展示活跃 token 和会话。</p>
        </div>
      </section>

      <section v-if="isAdmin" class="soft-card admin-data-card" aria-label="用户活动统计">
        <div class="admin-section-head">
          <div>
            <p class="eyebrow">User Activity</p>
            <h2>最近用户与会话活动</h2>
            <p>按最近审计事件聚合，方便看谁在使用 Agent、来自什么渠道。</p>
          </div>
        </div>
        <div class="admin-table-wrap">
          <table class="admin-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>最近事件数</th>
                <th>渠道</th>
                <th>最近时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="user in recentUsers" :key="user.userId">
                <td>{{ user.userId }}</td>
                <td>{{ formatNumber(user.recentEvents) }}</td>
                <td>{{ user.channels.join(' / ') || '-' }}</td>
                <td>{{ formatTime(user.lastSeenAt) }}</td>
              </tr>
              <tr v-if="recentUsers.length === 0">
                <td colspan="4">暂无用户活动。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section id="audit-logs" class="soft-card admin-data-card" aria-label="最近审计日志">
        <div class="admin-section-head">
          <div>
            <p class="eyebrow">Audit Logs</p>
            <h2>最近审计记录</h2>
            <p>总数 {{ formatNumber(auditLogs?.total) }}，展示最近 {{ auditRecords.length }} 条消息事件。</p>
          </div>
        </div>
        <div v-if="isAdmin" class="admin-table-wrap">
          <table class="admin-table audit-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>用户</th>
                <th>角色</th>
                <th>事件</th>
                <th>会话</th>
                <th>内容</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="record in auditRecords" :key="`${record.id || record.requestId}-${record.createTime}`">
                <td>{{ formatTime(record.createTime) }}</td>
                <td>{{ record.userId || '-' }}</td>
                <td>{{ record.role || '-' }}</td>
                <td>{{ record.eventType || '-' }}</td>
                <td><code>{{ preview(record.sessionKey, 34) }}</code></td>
                <td>{{ preview(record.content) }}</td>
              </tr>
              <tr v-if="auditRecords.length === 0">
                <td colspan="6">暂无审计记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="admin-empty-state">
          <strong>审计数据需要 ADMIN 权限</strong>
          <p>这个区块现在始终存在，普通用户点击“审计”不会再进入空白位置。</p>
        </div>
      </section>

      <section class="soft-card admin-audit-card" aria-label="当前收口策略">
        <div>
          <p class="eyebrow">Frontend Policy</p>
          <h2>前端单源策略</h2>
          <p>以后页面代码只放在 <code>frontend/src</code>，后端不再新增 <code>src/main/resources/static</code> 下的产品页面。</p>
        </div>
        <div class="capability-list">
          <span v-for="item in auditItems" :key="item">{{ item }}</span>
        </div>
      </section>

      <section class="soft-card admin-audit-card" aria-label="登录状态">
        <div>
          <p class="eyebrow">Current user</p>
          <h2>{{ auth.username || '未登录' }}</h2>
          <p>角色：{{ roleLabel }}。如果后端返回 401 或 403，先在右侧登录，再访问管理接口。</p>
          <p v-if="loadError" class="status danger">{{ loadError }}</p>
        </div>
      </section>
    </main>
  </div>
</template>
