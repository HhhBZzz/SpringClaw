<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { gsap } from 'gsap';

const homeRoot = ref<HTMLElement | null>(null);

const proofMetrics = [
  { label: '本地优先', value: '数据在你掌控中运行', detail: '项目文件、上下文和工具权限先确认再执行。' },
  { label: '透明可控', value: '每一步可追踪', detail: '路径判断、工具调用、确认动作和日志串成证据。' },
  { label: '工程原生', value: '与现有工具协作', detail: 'Skills、Git、文件和后台观测按真实项目方式工作。' }
] as const;

const credibilitySignals = [
  { label: '本地优先', body: '数据在你掌控中运行' },
  { label: '多轮上下文', body: '目标、计划和结果不断线' },
  { label: '安全执行', body: '副作用动作先确认' },
  { label: '全栈可观测', body: '模型、任务和后台状态可追踪' }
] as const;

const previewMessages = [
  { role: '你', time: '10:21', body: '实现用户订单取消时的撤销逻辑，包含数据清理和状态通知。' },
  { role: 'Agent', time: '10:21', body: '我会分析订单状态流转，识别规则边界，设计批处理任务，并添加回滚与清理逻辑。' }
] as const;

const previewSidebar = [
  { group: '工作区', items: ['概览', '项目', '记忆', 'Skills', '任务'] },
  { group: '执行', items: ['Agent 对话', '执行历史', '计划'] },
  { group: '观测', items: ['运行日志', '指标', '告警'] }
] as const;

const executionPlan = [
  '分析订单状态流转与状态清除表',
  '设计超时规则与批处理任务',
  '实现状态更新与数据清理',
  '添加通知并记录审计日志'
] as const;

const executionTimeline = [
  { time: '10:21', title: '解析目标', body: '识别到订单超时取消需求', done: true },
  { time: '10:21', title: '读取上下文', body: '订单服务 / 状态机 / 通知策略', done: true },
  { time: '10:22', title: '生成执行计划', body: '4 个步骤', done: true },
  { time: '10:23', title: '确认动作', body: '关键操作需要你确认', done: false }
] as const;

const confirmationItems = [
  { title: '更新数据库记录', body: '将超时订单状态更新为已取消，影响行数约 342。' }
] as const;

const observabilityStats = [
  { label: 'CPU', value: '18%' },
  { label: '内存', value: '42%' },
  { label: 'Agent 延迟', value: '352ms' },
  { label: '任务成功率', value: '98.7%' }
] as const;

const workflowSteps = [
  {
    title: '理解与澄清',
    body: '保留上下文、澄清目标与约束，形成可执行的任务边界。'
  },
  {
    title: '规划与推理',
    body: '拆解任务，判断执行路径，生成可验证的执行计划。'
  },
  {
    title: '执行与确认',
    body: '执行前确认副作用，写入受控、过程可回看。'
  },
  {
    title: '观测与迭代',
    body: '追踪后台观测与反馈，持续优化，稳定交付。'
  }
] as const;

const capabilityCards = [
  {
    title: '对话不丢上下文',
    body: '把目标、草稿、执行计划和结果放在一条主线里，避免多轮对话断裂。',
    metric: 'Context first',
    size: 'featured'
  },
  {
    title: 'Skills 可组合',
    body: '办公文件、项目分析、浏览器和自动化能力按 skill 方式接入，不把入口堆给用户。',
    metric: 'Composable skills',
    size: 'tall'
  },
  {
    title: '副作用先确认',
    body: '文件写入、命令执行和数据变更先生成确认单，再进入可追踪执行。',
    metric: 'Guarded actions',
    size: 'compact'
  },
  {
    title: '后台观测可用',
    body: '模型、Token、会话、任务和运行资源集中观察，产品状态不再靠猜。',
    metric: 'Runtime ops',
    size: 'wide'
  }
] as const;

const systemMap = [
  { title: '对话输入', body: '目标和上下文先统一' },
  { title: '路径判断', body: '选择问答、Skill 或工具链' },
  { title: '工具确认', body: '副作用动作先给确认单' },
  { title: '结果沉淀', body: '结果、日志和记忆可回看' }
] as const;

const trustSignals = ['登录态明确', '权限边界清楚', '调试默认收起', '移动端可读', 'GSAP 减动兼容'] as const;

function useHomePageMotion() {
  let mm: gsap.MatchMedia | undefined;

  onMounted(() => {
    const root = homeRoot.value;
    if (!root) return;
    mm = gsap.matchMedia(root);
    mm.add({
      desktop: '(min-width: 900px)',
      reduceMotion: '(prefers-reduced-motion: reduce)'
    }, (context) => {
      const { desktop, reduceMotion } = context.conditions || {};
      if (reduceMotion) {
        gsap.set('.home-motion', { clearProps: 'all' });
        return;
      }
      gsap.set('.home-motion', { willChange: 'transform, opacity' });
      gsap.from('.home-motion', {
        autoAlpha: 0,
        y: desktop ? 22 : 12,
        duration: 0.42,
        stagger: 0.055,
        ease: 'power3.out'
      });
      gsap.to('.preview-status-dot', {
        autoAlpha: 0.72,
        scale: 1.18,
        duration: 0.8,
        repeat: -1,
        yoyo: true,
        ease: 'power1.inOut'
      });
      gsap.from('.preview-observe-card, .preview-confirmation-panel', {
        autoAlpha: 0,
        x: desktop ? 18 : 0,
        y: desktop ? 0 : 12,
        duration: 0.5,
        stagger: 0.08,
        ease: 'power3.out',
        delay: 0.18
      });
      return () => {
        gsap.killTweensOf('.home-motion, .preview-status-dot, .preview-observe-card, .preview-confirmation-panel');
        gsap.set('.home-motion', { clearProps: 'willChange' });
      };
    });
  });

  onUnmounted(() => {
    gsap.killTweensOf('.home-motion, .preview-status-dot, .preview-observe-card, .preview-confirmation-panel');
    mm?.revert();
  });
}

useHomePageMotion();
</script>

<template>
  <div ref="homeRoot" class="site-shell mature-home taste-home">
    <RouterLink class="skip-link" :to="{ path: '/', hash: '#main-content' }">跳到主要内容</RouterLink>

    <header class="clean-nav home-nav" aria-label="主导航">
      <RouterLink class="site-brand" to="/" aria-label="SpringClaw 首页">
        <span class="runtime-brand-glyph home-brand-glyph" aria-hidden="true"><i></i><i></i><i></i></span>
        <span>
          <strong>SpringClaw</strong>
          <small>Agent Workspace</small>
        </span>
      </RouterLink>

      <nav class="nav-links" aria-label="页面导航">
        <RouterLink :to="{ path: '/', hash: '#workflow' }">工作流</RouterLink>
        <RouterLink :to="{ path: '/', hash: '#capabilities' }">能力</RouterLink>
        <RouterLink :to="{ path: '/', hash: '#trust' }">体验承诺</RouterLink>
        <RouterLink to="/agent">进入工作台</RouterLink>
      </nav>
    </header>

    <main id="main-content">
      <section class="home-hero-shell" aria-label="SpringClaw Agent Workspace">
        <div class="home-hero-backdrop" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <div class="home-hero-copy">
          <p class="home-hero-kicker home-motion">AI Agent workspace for engineers</p>
          <h1 class="home-motion" aria-label="把复杂项目交给一个可靠 Agent">
            <span>把复杂项目交给</span>
            <span>一个可靠 Agent</span>
          </h1>
          <p class="home-hero-lede home-motion">
            保留上下文、判断执行路径、确认副作用，<span>再把 Skills、记忆和后台观测</span><span>接成完整产品。</span>
          </p>
          <div class="home-actions home-motion">
            <RouterLink class="btn-primary" to="/agent">打开 Agent 工作台</RouterLink>
            <RouterLink class="btn-subtle light" to="/admin">进入后台</RouterLink>
          </div>
          <div class="home-launch-ribbon home-motion" aria-label="产品可信理由">
            <span v-for="signal in credibilitySignals" :key="signal.label">
              <strong>{{ signal.label }}</strong>
              <small>{{ signal.body }}</small>
            </span>
          </div>
        </div>

        <aside class="home-product-preview home-hero-stage home-motion" aria-label="产品工作台预览">
          <div class="preview-topbar home-workbench-shell">
            <div>
              <strong>SpringClaw Workspace</strong>
              <span>acme/payment-service</span>
            </div>
            <em><i class="preview-status-dot" aria-hidden="true"></i>Agent 在线</em>
          </div>
          <div class="preview-layout">
            <nav aria-label="预览导航">
              <template v-for="group in previewSidebar" :key="group.group">
                <small>{{ group.group }}</small>
                <span
                  v-for="item in group.items"
                  :key="`${group.group}-${item}`"
                  :class="{ active: item === 'Agent 对话' }"
                >
                  {{ item }}
                </span>
              </template>
            </nav>
            <section class="preview-main-stage" aria-label="Agent 对话">
              <header>
                <div>
                  <strong>Agent 对话</strong>
                  <span>默认线程</span>
                </div>
                <button type="button">新建线程</button>
              </header>
              <div class="preview-chat">
                <article v-for="message in previewMessages" :key="`${message.role}-${message.body}`">
                  <span>{{ message.role }} · {{ message.time }}</span>
                  <p>{{ message.body }}</p>
                </article>
              </div>
              <div class="preview-plan-card" aria-label="执行计划">
                <div>
                  <strong>执行计划</strong>
                  <span>正在执行 · 步骤 3/4</span>
                </div>
                <ol>
                  <li v-for="(item, index) in executionPlan" :key="item" :class="{ active: index === 2 }">
                    <span>{{ index + 1 }}</span>
                    <p>{{ item }}</p>
                  </li>
                </ol>
              </div>
              <label class="preview-composer">
                <span>输入你的目标或问题...</span>
                <button type="button">发送</button>
              </label>
            </section>
            <aside class="preview-side-rail home-execution-rail" aria-label="执行时间线">
              <div class="preview-tabs" aria-label="预览标签">
                <span class="active">执行时间线</span>
                <span>文件变更</span>
                <span>运行日志</span>
              </div>
              <ol class="preview-timeline">
                <li v-for="item in executionTimeline" :key="`${item.time}-${item.title}`" :class="{ done: item.done }">
                  <time>{{ item.time }}</time>
                  <div>
                    <strong>{{ item.title }}</strong>
                    <p>{{ item.body }}</p>
                  </div>
                </li>
              </ol>
              <div class="preview-confirmation-panel" aria-label="确认动作">
                <header>
                  <strong>需要你的确认</strong>
                  <span>{{ confirmationItems.length }} 项待确认</span>
                </header>
                <article v-for="item in confirmationItems" :key="item.title">
                  <div>
                    <strong>{{ item.title }}</strong>
                    <p>{{ item.body }}</p>
                  </div>
                  <button type="button">确认</button>
                </article>
                <footer>
                  <button type="button">全部确认</button>
                  <button type="button">取消全部</button>
                </footer>
              </div>
            </aside>
          </div>
          <div class="preview-run-summary">
            <span>main</span>
            <strong>执行中 · 00:01:23</strong>
            <span>上下文窗口 82%</span>
          </div>
          <div class="preview-observe-card" aria-label="观测指标">
            <strong>观测</strong>
            <span v-for="item in observabilityStats" :key="item.label">
              <small>{{ item.label }}</small>
              <em>{{ item.value }}</em>
              <i aria-hidden="true"></i>
            </span>
          </div>
        </aside>
      </section>

      <section class="home-proof-section home-motion" aria-label="产品关键指标">
        <div class="home-proof-strip">
          <article v-for="item in proofMetrics" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
            <small>{{ item.detail }}</small>
          </article>
        </div>
      </section>

      <section id="workflow" class="home-workflow-band">
        <div class="home-section-head home-motion">
          <h2>让复杂，变成可推理、可验证、可交付</h2>
          <p>用户只看主流程，复杂性留在系统里：理解目标、规划路径、确认执行、观测迭代。</p>
        </div>
        <div class="home-workflow-grid home-process-strip">
          <article v-for="(step, index) in workflowSteps" :key="step.title" class="home-workflow-card home-motion">
            <span aria-hidden="true"></span>
            <h3>{{ step.title }}</h3>
            <p>{{ step.body }}</p>
          </article>
        </div>
      </section>

      <section id="capabilities" class="home-capability-section">
        <div class="home-section-head home-motion">
          <h2>让能力变成清楚的产品界面</h2>
          <p>不是堆入口，而是让对话、Skills、确认单和后台观测各自有清楚状态和下一步动作。</p>
        </div>
        <div class="home-capability-grid home-bento-grid">
          <article
            v-for="capability in capabilityCards"
            :key="capability.title"
            class="home-capability-card home-bento-card home-motion"
            :class="capability.size"
          >
            <span>{{ capability.metric }}</span>
            <h3>{{ capability.title }}</h3>
            <p>{{ capability.body }}</p>
          </article>
        </div>
      </section>

      <section id="trust" class="home-trust-band home-motion">
        <div>
          <h2>边界清楚，Agent 才能被放心使用</h2>
          <p>登录态、权限、副作用确认、调试信息和移动端可读性都放进产品体验，而不是留给用户猜。</p>
        </div>
        <div class="home-trust-list" aria-label="体验承诺">
          <span v-for="item in trustSignals" :key="item">{{ item }}</span>
        </div>
        <div class="home-system-map" aria-label="Agent 执行路径">
          <span v-for="(item, index) in systemMap" :key="item.title">
            <small>0{{ index + 1 }}</small>
            <strong>{{ item.title }}</strong>
            <em>{{ item.body }}</em>
          </span>
        </div>
        <RouterLink class="btn-primary" to="/agent">查看当前工作台</RouterLink>
      </section>
    </main>
  </div>
</template>
