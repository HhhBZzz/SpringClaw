<script setup lang="ts">
import { adminConsoleUrl } from '../services/api';

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
  { title: 'Agent Console', body: '像 Codex / Claude Code 一样围绕目标输入、执行状态和结果沉淀组织界面。' },
  { title: 'Workspace Review', body: 'Agent 可以读取当前项目工作区并审查源码、架构、冗余和风险，敏感配置只报位置不泄露值。' },
  { title: 'Local Files', body: '通过授权根目录浏览本机文件，支持搜索简历、论文、文档和项目资料，不做全盘裸奔式读取。' },
  { title: 'Skill Runtime', body: '把爬虫、项目分析、探针这类能力放进 skill 包，降低 Java 主链路耦合。' },
  { title: 'Admin Cockpit', body: '管理员能看到模型、用户、任务、用量、审计和策略，不再只是一张静态页面。' },
  { title: 'DeepSeek V4 Pro', body: 'DeepSeek 通道收束到官方 V4 Pro 模型，普通聊天链路默认关闭 thinking 以保证兼容。' }
] as const;
</script>

<template>
  <div class="site-shell calm-site">
    <a class="skip-link" href="#main-content">跳到主要内容</a>

    <header class="clean-nav" aria-label="主导航">
      <RouterLink class="site-brand" to="/" aria-label="SpringClaw 首页">
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span>
          <strong>SpringClaw</strong>
          <small>Agent Workspace</small>
        </span>
      </RouterLink>

      <nav class="nav-links" aria-label="页面导航">
        <a href="#how-it-works">工作流</a>
        <a href="#capabilities">能力边界</a>
        <a href="#product-system">产品结构</a>
        <RouterLink to="/agent">进入工作台</RouterLink>
      </nav>
    </header>

    <main id="main-content">
      <section class="stitch-hero" aria-label="SpringClaw Agent 产品入口">
        <p class="eyebrow">Agent-native workspace</p>
        <h1>把想法交给 Agent，让执行路径自己展开。</h1>
        <p class="hero-lede">
          SpringClaw 把聊天、Skills、定时任务和执行日志收束成一个可解释的工作台。你只描述目标，系统负责选择问答、脚本执行或任务编排。
        </p>

        <RouterLink class="hero-prompt-card" to="/agent" aria-label="进入 Agent 工作台">
          <div class="prompt-textarea-mock">
            <span>帮我监控一个网页：每天早上 9 点抓取内容，提取变化，并把摘要写入任务日志。</span>
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
          <a class="btn-subtle light" :href="adminConsoleUrl()">查看后台</a>
        </div>
      </section>

      <section id="product-system" class="calm-section product-system">
        <div class="section-kicker">
          <p class="eyebrow">Product System</p>
          <h2>一个成熟 Agent 产品应该有前台、工作台和运维后台。</h2>
          <p>SpringClaw 现在按“官网入口、Agent Console、Admin Console、Skill Runtime”四块表达，不再把能力散落在不可点击的装饰文案里。</p>
        </div>
        <div class="product-grid">
          <article v-for="item in productPillars" :key="item.title" class="product-card">
            <h3>{{ item.title }}</h3>
            <p>{{ item.body }}</p>
          </article>
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
          <h2>不是聊天壳，而是能继续长大的 Agent 底座。</h2>
          <p>当前前端先呈现核心心智，后续可以自然接入任务管理、Skill 市场、执行日志和记忆检索。</p>
        </div>
        <div class="capability-list">
          <span v-for="item in capabilities" :key="item">{{ item }}</span>
        </div>
      </section>
    </main>
  </div>
</template>
