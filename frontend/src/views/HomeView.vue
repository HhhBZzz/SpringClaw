<script setup lang="ts">
const promptModes = ['智能问答', 'Skill 执行', '任务编排', '项目洞察'] as const;

const flows = [
  {
    title: '描述目标',
    body: '不用先选择工具，也不用理解底层服务。把你想完成的事情直接写出来。'
  },
  {
    title: '规划路径',
    body: 'Agent 根据上下文判断该走问答、脚本 skill、定时任务，还是系统内部能力。'
  },
  {
    title: '沉淀结果',
    body: '每次执行都有清晰输出；任务日志、会话上下文和后续投递可以继续串起来。'
  }
] as const;

const capabilities = [
  '对话式项目工作台',
  '本地源码审查与文件边界',
  '授权电脑文件搜索读取',
  'Python Skill 执行单元',
  'XXL-Job 任务调度底座',
  '会话上下文与权限控制',
  '面向 RAG 的记忆扩展空间'
] as const;

const productPillars = [
  {
    title: 'Agent 工作台',
    body: '面向真实任务输入，不再是普通聊天框。源码审查、Skill 执行、任务草稿都从这里进入主链路。',
    to: '/agent',
    action: '打开工作台'
  },
  {
    title: '项目审查',
    body: '读取当前项目工作区，分析架构、冗余、风险和可维护性；敏感配置只报告位置，不展示值。',
    to: '/agent',
    action: '发起审查'
  },
  {
    title: '本地文件',
    body: '通过授权根目录浏览电脑文件，适合找论文、简历、资料和其他项目，不做无边界全盘读取。',
    to: '/agent',
    action: '查看边界'
  },
  {
    title: 'Skill 运行时',
    body: '把爬虫、办公文件、项目分析这类能力放进 skill 包，让 Java 主链路保持轻，扩展能力放到外部单元。',
    to: '/agent',
    action: '查看 Skills'
  },
  {
    title: '后台驾驶舱',
    body: '管理员可以看 Token、用户、会话、审计、运行时 Skill 和调度状态，不再只是一张静态页面。',
    to: '/admin',
    action: '进入后台'
  },
  {
    title: 'DeepSeek V4 Pro',
    body: '模型入口收束到 DeepSeek V4 Pro，减少无效切换，让前端表达和后端真实配置保持一致。',
    to: '/agent',
    action: '检查模型'
  }
] as const;
</script>

<template>
  <div class="site-shell calm-site">
    <RouterLink class="skip-link" :to="{ path: '/', hash: '#main-content' }">跳到主要内容</RouterLink>

    <header class="clean-nav" aria-label="主导航">
      <RouterLink class="site-brand" to="/" aria-label="SpringClaw 首页">
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>
          <strong>SpringClaw</strong>
          <small>Agent Workspace</small>
        </span>
      </RouterLink>

      <nav class="nav-links" aria-label="页面导航">
        <RouterLink :to="{ path: '/', hash: '#how-it-works' }">工作流</RouterLink>
        <RouterLink :to="{ path: '/', hash: '#capabilities' }">能力边界</RouterLink>
        <RouterLink :to="{ path: '/', hash: '#product-system' }">产品结构</RouterLink>
        <RouterLink to="/agent">进入工作台</RouterLink>
      </nav>
    </header>

    <main id="main-content">
      <section class="stitch-hero" aria-label="SpringClaw Agent 产品入口">
        <p class="eyebrow">SpringClaw Agent Platform</p>
        <h1>把本地项目、Skills 和定时任务收进一个 Agent 工作台。</h1>
        <p class="hero-lede">
          SpringClaw 不是聊天壳，而是一个本地可运行、可审计、可扩展的 Agent 底座。你描述目标，系统负责进入问答、脚本 Skill、项目文件或定时任务链路。
        </p>

        <RouterLink class="hero-prompt-card" to="/agent" aria-label="进入 Agent 工作台">
          <div class="prompt-textarea-mock">
            <span>审查当前项目结构，列出冗余代码、可合并模块和下一步重构优先级。</span>
          </div>
          <div class="prompt-toolbar">
            <div class="mode-chips" aria-label="能力模式">
              <span v-for="mode in promptModes" :key="mode">{{ mode }}</span>
            </div>
            <span class="send-orb" aria-hidden="true"></span>
          </div>
        </RouterLink>

        <div class="quiet-actions">
          <RouterLink class="btn-primary" to="/agent">开始使用</RouterLink>
          <RouterLink class="btn-subtle light" to="/admin">查看后台</RouterLink>
        </div>
      </section>

      <section id="product-system" class="calm-section product-system">
        <div class="section-kicker">
          <p class="eyebrow">Product System</p>
          <h2>所有像按钮的东西，都应该真的能带你到下一步。</h2>
          <p>这里把产品能力拆成可进入的真实入口：工作台负责执行，后台负责观测，Skill Runtime 负责扩展。</p>
        </div>
        <div class="product-grid">
          <RouterLink v-for="item in productPillars" :key="item.title" class="product-card product-link" :to="item.to">
            <h3>{{ item.title }}</h3>
            <p>{{ item.body }}</p>
            <span class="card-action">{{ item.action }}</span>
          </RouterLink>
        </div>
      </section>

      <section id="how-it-works" class="calm-section">
        <div class="section-kicker">
          <p class="eyebrow">Workflow</p>
          <h2>从一句话到一次可追踪的执行。</h2>
          <p>页面只保留关键路径：输入目标、规划路径、执行归档。少一点噪音，Agent 的价值会更清楚。</p>
        </div>
        <div class="quiet-grid three">
          <article v-for="(item, index) in flows" :key="item.title" class="quiet-card">
            <span class="step-number">0{{ index + 1 }}</span>
            <h3>{{ item.title }}</h3>
            <p>{{ item.body }}</p>
          </article>
        </div>
      </section>

      <section id="capabilities" class="calm-section capability-strip">
        <div>
          <p class="eyebrow">Capabilities</p>
          <h2>不是演示页，而是能继续长大的 Agent 底座。</h2>
          <p>前端只呈现真实能力：能点开的入口、能解释的执行链路、能审计的后台数据，以及后续可扩展的 Skill 体系。</p>
        </div>
        <div class="capability-list">
          <span v-for="item in capabilities" :key="item">{{ item }}</span>
        </div>
      </section>
    </main>
  </div>
</template>
