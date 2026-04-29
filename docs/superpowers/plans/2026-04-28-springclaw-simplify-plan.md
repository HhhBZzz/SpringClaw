# SpringClaw 简化与 Skills 系统重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一 Skills 系统到 package-based 路径，收敛配置，全局重命名 openclaw → springclaw，拆分 ChatServiceImpl，加固安全。

**Architecture:** 分 5 个 Phase，每 Phase 完成后可独立编译通过、测试通过。Phase 1-2 联合作业（skills 系统 + 配置收敛互相依赖），Phase 3 重命名是纯机械操作，Phase 4 拆分核心类，Phase 5 安全卫生收尾。

**Tech Stack:** Java 17, Spring Boot 3.5.7, Maven, MyBatis-Plus, SnakeYAML

---

## Phase 1: Skills 系统统一 + 配置收敛

### Task 1: 简化 SKILL.md frontmatter 并统一所有 skill 包元数据

**Files:**
- Modify: `skills/packages/repo_inspector/SKILL.md`
- Modify: `skills/packages/boss_authorized_collector/SKILL.md`
- Modify: `skills/packages/boss_job_dataset/SKILL.md`
- Modify: `skills/packages/web_crawler/SKILL.md`
- Modify: `skills/packages/runtime_probe/SKILL.md`
- Modify: `skills/packages/clawhub-summarize/SKILL.md`
- Modify: `skills/packages/code-analysis/SKILL.md`
- Modify: `skills/packages/log-diagnostics/SKILL.md`
- Modify: `skills/packages/web-crawl/SKILL.md`
- Modify: `skills/templates/package_skill/SKILL.md`

- [ ] **Step 1: 统一 SKILL.md 的 frontmatter 结构为扁平格式**

将嵌套的 `metadata.openclaw.springclaw.*` 改为扁平 frontmatter 顶级字段。每个 SKILL.md 格式如下：

```markdown
---
name: 项目分析技能
displayName: Repo Inspector
description: 扫描当前工作区，定位实现文件、配置文件和关键代码片段
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: workspace
tier: core
inputHint: 传入 goal，自然语言描述你想分析的功能或类名
priority: 10
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 项目分析
  - 找实现
  - 定位代码
  - 找文件
triggerExamples:
  - 用 repo_inspector 帮我找 Spring AI 相关文件
---

# 行为指令内容...
```

- [ ] **Step 2: 更新 repo_inspector/SKILL.md**

```yaml
---
name: 项目分析技能
displayName: Repo Inspector
description: 扫描当前工作区，定位实现文件、配置文件和关键代码片段
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: workspace
tier: core
inputHint: 传入 goal，自然语言描述你想分析的功能或类名
priority: 10
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 项目分析
  - 找实现
  - 定位代码
  - 找文件
  - 工作区
triggerExamples:
  - 用 repo_inspector 帮我找 Spring AI 相关文件
  - 分析 ChatServiceImpl 的核心实现在哪
---
```

- [ ] **Step 3: 更新 boss_authorized_collector/SKILL.md**

```yaml
---
name: BOSS授权采集器
displayName: Boss Authorized Collector
description: 使用授权 headers/cookie 采集 BOSS 职位列表页，并导出职位数据集
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: research
tier: core
inputHint: 传入 goal，并带上 config JSON 路径，或直接带本地 HTML 文件路径
priority: 16
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - BOSS授权采集
  - 职位列表采集
  - 解析职位列表HTML
  - 导出职位数据
triggerExamples:
  - 用 boss_authorized_collector 运行 data/boss/boss_collect_config.json
---
```

- [ ] **Step 4: 更新 boss_job_dataset/SKILL.md**

```yaml
---
name: BOSS职位数据集研究
displayName: Boss Job Dataset
description: 搜索、研究、分析 BOSS直聘 职位数据集，统计数据并生成分析报告
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: research
tier: core
inputHint: 传入研究目标 goal 和可选的 params JSON
priority: 18
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - BOSS数据集
  - 职位研究
  - 职位分析报告
  - 薪资数据
  - 论文支撑数据
triggerExamples:
  - 帮我分析 BOSS 职位数据集，统计薪资分布
---
```

- [ ] **Step 5: 更新 web_crawler/SKILL.md**

```yaml
---
name: 网页爬虫
displayName: Web Crawler
description: 抓取网页正文内容，提取结构化文本，支持 HTTP/HTTPS 链接
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: web
tier: utility
inputHint: 传入要抓取的 URL goal
priority: 30
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 网页
  - 页面
  - 链接
  - 网址
  - 抓取
  - 爬取
  - 提取
triggerExamples:
  - 帮我抓取 https://example.com 的正文内容
---
```

- [ ] **Step 6: 更新 runtime_probe/SKILL.md**

```yaml
---
name: 运行时诊断
displayName: Runtime Probe
description: 排查端口占用、JVM 进程、启动失败等运行时问题
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: diagnostics
tier: core
inputHint: 传入诊断目标 goal
priority: 8
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 端口
  - 进程
  - JVM
  - 启动失败
  - 诊断
  - runtime
triggerExamples:
  - 帮我诊断端口 8080 被哪个进程占用
---
```

- [ ] **Step 7: 更新 clawhub-summarize/SKILL.md 为统一格式**

```yaml
---
name: 内容摘要
displayName: Summarize
description: 使用 summarize CLI 对网页、PDF、图片、音频、YouTube 内容生成摘要
version: 1.0.0
type: prompt
category: content
tier: utility
priority: 50
enabled: true
agentVisible: true
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 摘要
  - 总结
  - summarize
  - 概括
  - 归纳
triggerExamples:
  - 帮我总结 https://example.com 的内容
---
```

- [ ] **Step 8: 更新 code-analysis/SKILL.md**

```yaml
---
name: 代码分析
displayName: Code Analysis
description: 分析代码结构、定位功能实现、梳理调用链，适合理解“某个功能在哪实现”
version: 1.0.0
type: prompt
category: workspace
tier: utility
priority: 55
enabled: true
agentVisible: true
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 代码分析
  - 分析代码
  - 定位功能
  - 梳理逻辑
  - 调用链
triggerExamples:
  - 帮我分析这个项目的认证流程是怎么实现的
---
```

- [ ] **Step 9: 更新 log-diagnostics/SKILL.md**

```yaml
---
name: 日志诊断
displayName: Log Diagnostics
description: 分析系统日志、错误堆栈、异常信息，定位运行时问题根因
version: 1.0.0
type: prompt
category: diagnostics
tier: utility
priority: 58
enabled: true
agentVisible: true
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 日志
  - 报错
  - 错误
  - 异常
  - 堆栈
  - 超时
  - 启动失败
triggerExamples:
  - 帮我分析这段日志里的异常是什么原因
---
```

- [ ] **Step 10: 更新 web-crawl/SKILL.md**

```yaml
---
name: 网页抓取
displayName: Web Crawl
description: 抓取指定网页的正文内容，提取结构化文本，分析页面信息
version: 1.0.0
type: prompt
category: web
tier: utility
priority: 60
enabled: true
agentVisible: true
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 网页
  - 抓取
  - 爬取
  - 读取网页
  - 打开链接
  - 提取正文
triggerExamples:
  - 帮我抓取并分析 https://example.com 的内容
---
```

- [ ] **Step 11: 更新 templates/package_skill/SKILL.md**

```yaml
---
name: 新技能模板
displayName: Example Skill
description: 这是一个技能模板，用于创建新的 skill package
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: general
tier: utility
inputHint: 传入自然语言 goal 执行脚本技能
priority: 100
enabled: false
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 示例技能
  - 模板
triggerExamples:
  - 用 example_skill 做某件事
---
```

- [ ] **Step 12: 删除旧的 skills 根目录文件（已由 git status 标记为 deleted）**

```bash
cd /Users/hanbingzheng/springclaw && git add skills/boss_authorized_collector.py skills/boss_authorized_collector.skill.json skills/boss_job_dataset.py skills/boss_job_dataset.skill.json skills/repo_inspector.py skills/repo_inspector.skill.json skills/runtime_probe.py skills/runtime_probe.skill.json skills/templates/example_skill.py skills/templates/example_skill.skill.json skills/web_crawler.py skills/web_crawler.skill.json
```

- [ ] **Step 13: 提交**

```bash
git add skills/packages/ skills/templates/ skills/boss_authorized_collector.py skills/boss_authorized_collector.skill.json skills/boss_job_dataset.py skills/boss_job_dataset.skill.json skills/repo_inspector.py skills/repo_inspector.skill.json skills/runtime_probe.py skills/runtime_probe.skill.json skills/templates/example_skill.py skills/templates/example_skill.skill.json skills/web_crawler.py skills/web_crawler.skill.json
git commit -m "refactor: unify SKILL.md frontmatter to flat structure, remove old skill files"
```

---

### Task 2: 更新 SkillBundleSupport 解析器适配新 frontmatter

**Files:**
- Modify: `src/main/java/com/openclaw/service/skill/bundle/SkillBundleSupport.java`

- [ ] **Step 1: 修改 parseBundle 方法，改为从扁平 frontmatter 读取字段**

当前 SkillBundleSupport.parseBundle() 使用嵌套路径 `metadata → openclaw → springclaw → field`。将其改为直接从 top-level frontmatter 读取：

```java
public static Optional<SkillBundleDefinition> parseBundle(Path bundlePath) {
    Path normalizedBundlePath = bundlePath.toAbsolutePath().normalize();
    Path skillPath = resolveSkillPath(normalizedBundlePath);
    if (skillPath == null || !Files.isRegularFile(skillPath)) {
        return Optional.empty();
    }
    try {
        String markdown = Files.readString(skillPath, StandardCharsets.UTF_8);
        ParsedSkillMarkdown parsed = parseMarkdown(markdown);
        Map<String, Object> fm = parsed.frontmatter();
        
        String slug = normalizedBundlePath.getFileName() == null 
            ? "skill" : normalizedBundlePath.getFileName().toString();
        String skillId = firstNonBlank(getString(fm, "skillId", "name"), slug);
        String name = firstNonBlank(getString(fm, "displayName", "name"), skillId);
        String description = firstNonBlank(getString(fm, "description"), name + " skill");
        
        String executorType = normalizeExecutorType(firstNonBlank(
            getString(fm, "type", "executorType"),
            guessExecutorTypeFromEntrypoint(getString(fm, "entrypoint")),
            "prompt"
        ));
        
        String entrypoint = firstNonBlank(getString(fm, "entrypoint"), null);
        Path entrypointPath = null;
        if (StringUtils.hasText(entrypoint)) {
            entrypointPath = normalizedBundlePath.resolve(entrypoint).normalize();
            if (!entrypointPath.startsWith(normalizedBundlePath) || !Files.isRegularFile(entrypointPath)) {
                return Optional.empty();
            }
        }
        if (requiresEntrypoint(executorType) && entrypointPath == null) {
            return Optional.empty();
        }
        
        String preferredMode = normalizeMode(firstNonBlank(
            getString(fm, "preferredMode"), "simplified"));
        String contextPolicy = firstNonBlank(
            getString(fm, "contextPolicy"), "session-only");
        boolean agentVisible = getBoolean(fm, true, "agentVisible");
        boolean enabled = getBoolean(fm, true, "enabled");
        int priority = getInteger(fm,
            isPromptExecutor(executorType) ? DEFAULT_MARKDOWN_PRIORITY : DEFAULT_SCRIPT_PRIORITY,
            "priority");
        String category = firstNonBlank(getString(fm, "category"), "general");
        String tier = firstNonBlank(getString(fm, "tier"), "utility");
        String inputHint = firstNonBlank(
            getString(fm, "inputHint"),
            isPromptExecutor(executorType) ? "根据 skill 说明执行对应任务。" : "传入自然语言 goal 执行脚本技能。"
        );
        
        List<String> triggerKeywords = toStringList(fm.get("triggerKeywords"));
        if (triggerKeywords.isEmpty()) {
            triggerKeywords = deriveDefaultKeywords(skillId, name);
        }
        List<String> triggerExamples = toStringList(fm.get("triggerExamples"));
        List<String> toolPacks = normalizeToolPacks(toStringList(fm.get("toolPacks")));
        
        String sourceType = switch (executorType) {
            case "builtin" -> "BUILTIN";
            case "python", "node" -> "SCRIPT";
            default -> "MARKDOWN";
        };
        String sourceRef = skillPath.toString();
        String executorRef = entrypointPath == null ? skillPath.toString() : entrypointPath.toString();
        
        return Optional.of(new SkillBundleDefinition(
            skillId, slug, name, description,
            sourceType, sourceRef,
            parsed.body().isBlank() ? markdown.strip() : parsed.body().trim(),
            triggerKeywords, triggerExamples, toolPacks,
            preferredMode, contextPolicy,
            executorType, executorRef,
            enabled, priority, agentVisible,
            category, tier, inputHint,
            normalizedBundlePath, skillPath, entrypointPath
        ));
    } catch (IOException ex) {
        return Optional.empty();
    }
}
```

- [ ] **Step 2: 简化 getString/getBoolean/getInteger 方法，去掉路径参数改为直接从 Map 读取**

删除 `getValue(Map<String, Object> root, String... path)` 等深层路径遍历方法，改为简单的 `Map.get(key)` + 类型转换：

```java
private static String getString(Map<String, Object> map, String... keys) {
    for (String key : keys) {
        Object value = map.get(key);
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) return text;
        }
    }
    return null;
}

private static boolean getBoolean(Map<String, Object> map, boolean defaultValue, String... keys) {
    for (String key : keys) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value != null) {
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) return Boolean.parseBoolean(text);
        }
    }
    return defaultValue;
}

private static int getInteger(Map<String, Object> map, int defaultValue, String... keys) {
    for (String key : keys) {
        Object value = map.get(key);
        if (value instanceof Number num) return num.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value).trim()); }
            catch (NumberFormatException ignored) { }
        }
    }
    return defaultValue;
}
```

- [ ] **Step 3: 删除旧的嵌套路径方法 ensureMap 和 getValue（不再需要的深层路径遍历）**

```java
// 删除以下方法：
// - ensureMap(Map<String, Object> root, String... path)
// - getValue(Map<String, Object> root, String... path)
```

- [ ] **Step 4: 更新 renderMarkdown 方法，不再写入 metadata 嵌套**

```java
public static String renderMarkdown(Map<String, Object> frontmatter, String body) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setSplitLines(false);
    Yaml yaml = new Yaml(options);
    String dumped = yaml.dump(frontmatter == null ? Map.of() : frontmatter).trim();
    String safeBody = body == null ? "" : body.strip();
    if (!StringUtils.hasText(dumped)) {
        return safeBody + "\n";
    }
    if (!StringUtils.hasText(safeBody)) {
        return "---\n" + dumped + "\n---\n";
    }
    return "---\n" + dumped + "\n---\n\n" + safeBody + "\n";
}
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/openclaw/service/skill/bundle/SkillBundleSupport.java
git commit -m "refactor: simplify SkillBundleSupport to read flat frontmatter structure"
```

---

### Task 3: 统一 SkillRegistryService，删除旧的三分路路径

**Files:**
- Modify: `src/main/java/com/openclaw/service/skill/impl/SkillRegistryService.java`
- Modify: `src/main/java/com/openclaw/service/chat/impl/ChatServiceImpl.java`（更新对 SkillRegistryService 的调用）
- Delete: `src/main/java/com/openclaw/service/skill/impl/BuiltinSkillCatalogService.java`
- Modify: `src/main/java/com/openclaw/service/skill/script/ScriptSkillCatalogService.java`（简化，去掉重复逻辑）
- Modify: `src/main/java/com/openclaw/service/skill/markdown/MarkdownSkillCatalogService.java`（简化）
- Modify: `src/main/java/com/openclaw/service/skill/script/ScriptSkillExecutorService.java`（去掉嵌套回退 @Value）

- [ ] **Step 1: 重写 SkillRegistryService — 只保留 package-based 路径**

```java
package com.openclaw.service.skill.impl;

import com.openclaw.service.skill.SkillDefinition;
import com.openclaw.service.skill.bundle.SkillPackageCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 skill 注册中心。
 * 所有 skill 定义均来自 skills/packages/ 下的 SKILL.md 文件。
 */
@Service
public class SkillRegistryService {

    private final SkillPackageCatalogService packageCatalogService;

    public SkillRegistryService(SkillPackageCatalogService packageCatalogService) {
        this.packageCatalogService = packageCatalogService;
    }

    /** 列出所有 skill 定义，按 priority + skillId 排序 */
    public List<SkillDefinition> listAllDefinitions() {
        return packageCatalogService.listBundles().stream()
                .map(bundle -> bundle.toRuntimeDefinition())
                .sorted(Comparator.comparingInt(SkillDefinition::priority)
                        .thenComparing(def -> def.skillId().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** 列出所有已启用且对 Agent 可见的 skill */
    public List<SkillDefinition> listAgentVisibleDefinitions(Set<String> allowedToolPacks) {
        return listAllDefinitions().stream()
                .filter(SkillDefinition::enabled)
                .filter(SkillDefinition::agentVisible)
                .filter(definition -> definition.matchesAllowedToolPacks(allowedToolPacks))
                .toList();
    }

    /** 列出核心（priority <= 30）skill */
    public List<SkillDefinition> listCoreDefinitions(Set<String> allowedToolPacks) {
        return listAgentVisibleDefinitions(allowedToolPacks).stream()
                .filter(definition -> definition.priority() <= 30)
                .toList();
    }

    /** 按 keyword 匹配最相关的 N 个 skill */
    public List<SkillDefinition> matchAgentVisibleDefinitions(String question, Set<String> allowedToolPacks, int limit) {
        if (!StringUtils.hasText(question) || limit <= 0) {
            return List.of();
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return listAgentVisibleDefinitions(allowedToolPacks).stream()
                .map(definition -> java.util.Map.entry(definition, score(definition, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<java.util.Map.Entry<SkillDefinition, Integer>>comparingInt(java.util.Map.Entry::getValue).reversed()
                        .thenComparing(entry -> entry.getKey().priority()))
                .limit(limit)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /** 匹配最佳 skill */
    public Optional<SkillDefinition> matchBestAgentVisibleDefinition(String question, Set<String> allowedToolPacks) {
        return matchAgentVisibleDefinitions(question, allowedToolPacks, 1).stream().findFirst();
    }

    /** keyword 评分：触发词命中 +3，名称 token 命中 +1 */
    private int score(SkillDefinition definition, String normalizedQuestion) {
        int score = 0;
        if (definition.triggerKeywords() != null) {
            for (String keyword : definition.triggerKeywords()) {
                if (StringUtils.hasText(keyword) && normalizedQuestion.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                    score += 3;
                }
            }
        }
        for (String token : deriveNameTokens(definition)) {
            if (normalizedQuestion.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> deriveNameTokens(SkillDefinition definition) {
        List<String> tokens = new java.util.ArrayList<>();
        addNameTokens(tokens, definition.skillId());
        addNameTokens(tokens, definition.name());
        return tokens;
    }

    private void addNameTokens(List<String> target, String raw) {
        if (!StringUtils.hasText(raw)) return;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() >= 2) target.add(normalized);
        for (String token : normalized.split("[\\s_\\-/]+")) {
            if (token.length() >= 3) target.add(token);
        }
    }
}
```

- [ ] **Step 2: 删除 BuiltinSkillCatalogService**

```bash
rm src/main/java/com/openclaw/service/skill/impl/BuiltinSkillCatalogService.java
```

- [ ] **Step 3: 简化 ScriptSkillCatalogService**

删除无参构造函数和 @Value 嵌套回退。只保留 Autowired 构造函数：

```java
@Service
public class ScriptSkillCatalogService {

    private final boolean enabled;
    private final SkillPackageCatalogService packageCatalogService;
    private final Set<String> allowedSkills;

    @Autowired
    public ScriptSkillCatalogService(
            @Value("${openclaw.skills.enabled:true}") boolean enabled,
            @Value("${openclaw.skills.allowed:*}") String allowedSkills,
            SkillPackageCatalogService packageCatalogService) {
        this.enabled = enabled;
        this.packageCatalogService = packageCatalogService;
        this.allowedSkills = parseAllowedSkills(allowedSkills);
    }
    // ... 其余方法不变
}
```

删除旧的 `public ScriptSkillCatalogService(boolean enabled, String root, String allowedSkills, ObjectMapper objectMapper)` 构造函数。

- [ ] **Step 4: 简化 MarkdownSkillCatalogService**

同样删除多余的构造函数嵌套，只保留 Autowired 注入：

```java
@Service
public class MarkdownSkillCatalogService {
    // ...
    @Autowired
    public MarkdownSkillCatalogService(
            @Value("${openclaw.skills.markdown-enabled:true}") boolean enabled,
            @Value("${openclaw.skills.root:${user.dir}/skills/packages}") String root,
            SkillPackageCatalogService packageCatalogService) {
        this.enabled = enabled;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.packageCatalogService = packageCatalogService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
    // ...
}
```

删除 `MarkdownSkillCatalogService(boolean enabled, String root, ObjectMapper objectMapper)` 构造函数。

- [ ] **Step 5: 简化 ScriptSkillExecutorService @Value 注解**

将嵌套回退去掉：

```java
public ScriptSkillExecutorService(
        @Value("${openclaw.skills.enabled:true}") boolean enabled,
        ScriptSkillCatalogService scriptSkillCatalogService,
        @Value("${openclaw.skills.python:python3}") String pythonCommand,
        @Value("${openclaw.skills.timeout-seconds:8}") int timeoutSeconds,
        @Value("${openclaw.skills.max-output-chars:3000}") int maxOutputChars,
        ObjectMapper objectMapper) {
    // ...
}
```

- [ ] **Step 6: 运行测试验证**

```bash
cd /Users/hanbingzheng/springclaw && mvn test -Dtest="com.openclaw.service.skill.**" 2>&1 | tail -30
```

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/openclaw/service/skill/
git rm src/main/java/com/openclaw/service/skill/impl/BuiltinSkillCatalogService.java
git commit -m "refactor: unify SkillRegistryService to single package-based path, remove BuiltinSkillCatalogService"
```

---

### Task 4: 配置收敛 — application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 删除 `openclaw.tools.script.*` 配置段（与 skills.* 重叠）**

删除以下行（在 application.yml 中）：
```yaml
# 删除整个 script 块下的旧配置
# openclaw.tools.script:
#   enabled: true
#   root: ...
#   allowed-skills: ...
#   python-command: ...
#   timeout-seconds: ...
#   max-output-chars: ...
```

- [ ] **Step 2: 删除 `openclaw.skill.*` 的 trigger keywords（已迁移到 SKILL.md）**

删除 `application.yml` 中 `openclaw.skill.*` 下的所有 trigger keywords 配置。

- [ ] **Step 3: 规范化 `openclaw.skills.*` 配置**

```yaml
openclaw:
  skills:
    enabled: ${SPRINGCLAW_SKILLS_ENABLED:true}
    root: ${SPRINGCLAW_SKILLS_ROOT:${user.dir}/skills/packages}
    allowed: ${SPRINGCLAW_SKILLS_ALLOWED:*}
    python: ${SPRINGCLAW_SKILLS_PYTHON:python3}
    timeout-seconds: ${SPRINGCLAW_SKILLS_TIMEOUT_SECONDS:8}
    max-output-chars: ${SPRINGCLAW_SKILLS_MAX_OUTPUT_CHARS:3000}
    markdown-enabled: ${SPRINGCLAW_MARKDOWN_SKILL_ENABLED:true}
```

- [ ] **Step 4: 删除 `${OPENCLAW_*}` 环境变量引用（为 Phase 3 重命名做准备）**

暂时保留环境变量引用不变，等 Phase 3 统一替换。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/application.yml
git commit -m "refactor: consolidate skills config, remove duplicate script config keys"
```

---

### Task 5: 清理引用 BuiltinSkillCatalogService 的代码

**Files:**
- Modify: `src/main/java/com/openclaw/service/skill/impl/SkillServiceImpl.java`
- Modify: `src/main/java/com/openclaw/service/chat/BuiltinSkillExecutionService.java`
- Modify: `src/main/java/com/openclaw/service/chat/LocalSkillModelControlSupport.java`
- Modify: `src/main/java/com/openclaw/service/chat/impl/ChatRoutingPolicyService.java`
- Modify: `src/main/java/com/openclaw/service/chat/impl/ChatServiceImpl.java`

- [ ] **Step 1: 查找所有引用 BuiltinSkillCatalogService 的地方并移除**

```bash
cd /Users/hanbingzheng/springclaw && grep -rn "BuiltinSkillCatalogService" src/main/java/
```

- [ ] **Step 2: 更新所有引用，改为用 SkillRegistryService**

所有原来调用 `builtinSkillCatalogService.matchXxx()` 的地方，改为 `skillRegistryService.matchXxx()`。

- [ ] **Step 3: 更新 SkillServiceImpl**

```java
// 删除 BuiltinSkillCatalogService 字段和注入
// 只保留 SkillRegistryService + SkillPackageCatalogService
```

- [ ] **Step 4: 运行全量测试**

```bash
cd /Users/hanbingzheng/springclaw && mvn test 2>&1 | tail -30
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/
git commit -m "refactor: replace BuiltinSkillCatalogService references with SkillRegistryService"
```

---

### Task 6: 更新所有 SKILL.md 相关的 Java 类中 frontmatter 读取路径

**Files:**
- Modify: `src/main/java/com/openclaw/service/skill/markdown/MarkdownSkillCatalogService.java`

- [ ] **Step 1: 更新 importFromUrl 方法中的 frontmatter 写入逻辑**

MarkdownSkillCatalogService.importFromUrl() 中创建 frontmatter 的部分，从嵌套路径 `metadata → openclaw → springclaw → ...` 改为扁平结构：

```java
// 旧代码：
Map<String, Object> metadata = SkillBundleSupport.ensureMap(frontmatter, "metadata");
Map<String, Object> openclaw = SkillBundleSupport.ensureMap(metadata, "openclaw");
Map<String, Object> springclaw = SkillBundleSupport.ensureMap(openclaw, "springclaw");
// ...

// 新代码：直接在 frontmatter 顶层设置字段
frontmatter.put("skillId", slug);
frontmatter.put("type", "prompt");
frontmatter.put("toolPacks", chooseToolPacks(request.toolPacks(), frontmatter.get("toolPacks")));
frontmatter.put("preferredMode", ...);
frontmatter.put("contextPolicy", ...);
frontmatter.put("agentVisible", ...);
frontmatter.put("priority", ...);
frontmatter.put("triggerKeywords", ...);
```

- [ ] **Step 2: 更新 chooseToolPacks/chooseTriggerKeywords 辅助方法**

这些辅助方法参数中的 `Object existingValue` 改为直接从扁平 frontmatter get。

- [ ] **Step 3: 运行测试**

```bash
cd /Users/hanbingzheng/springclaw && mvn test -Dtest="com.openclaw.service.skill.markdown.MarkdownSkillCatalogServiceTest" 2>&1 | tail -20
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/openclaw/service/skill/markdown/MarkdownSkillCatalogService.java
git commit -m "refactor: flatten frontmatter write logic in MarkdownSkillCatalogService"
```

---

### Task 7: Phase 1 验证 — 全量编译和测试

- [ ] **Step 1: 编译验证**

```bash
cd /Users/hanbingzheng/springclaw && mvn clean package -DskipTests 2>&1 | tail -20
```

- [ ] **Step 2: 全量测试**

```bash
cd /Users/hanbingzheng/springclaw && mvn test 2>&1 | tail -40
```

- [ ] **Step 3: 如有测试失败，修复后重新运行**

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "chore: complete Phase 1 - skills system unification and config cleanup"
```

---

## Phase 2: 全局重命名 openclaw → springclaw

### Task 8: 重命名 Java 包 com.openclaw → com.springclaw

- [ ] **Step 1: 使用 mv 命令批量移动 Java 源文件目录**

```bash
cd /Users/hanbingzheng/springclaw
# 主代码
mv src/main/java/com/openclaw src/main/java/com/springclaw
# 测试代码
mv src/test/java/com/openclaw src/test/java/com/springclaw
```

- [ ] **Step 2: 使用 sed 批量替换 Java 文件中的包名和 import**

```bash
cd /Users/hanbingzheng/springclaw
# 替换所有 Java 文件中的包引用
find src/ -name "*.java" -exec sed -i '' 's/package com\.openclaw/package com.springclaw/g' {} +
find src/ -name "*.java" -exec sed -i '' 's/import com\.openclaw/import com.springclaw/g' {} +
find src/ -name "*.java" -exec sed -i '' 's/com\.openclaw\./com.springclaw./g' {} +
```

- [ ] **Step 3: 运行编译确认**

```bash
mvn clean compile -DskipTests 2>&1 | tail -20
```

---

### Task 9: 重命名 Java 类名 OpenClaw* → SpringClaw*

- [ ] **Step 1: 查找所有含 OpenClaw 的 Java 文件**

```bash
cd /Users/hanbingzheng/springclaw && find src/ -name "*OpenClaw*" -type f
```

- [ ] **Step 2: 重命名主类文件**

```bash
mv src/main/java/com/springclaw/OpenClawJavaApplication.java src/main/java/com/springclaw/SpringClawApplication.java
# 可能的其他 OpenClaw 类文件
```

- [ ] **Step 3: 替换所有 Java 文件中的文本引用**

```bash
find src/ -name "*.java" -exec sed -i '' 's/OpenClawJavaApplication/SpringClawApplication/g' {} +
find src/ -name "*.java" -exec sed -i '' 's/OpenClawAiProperties/SpringClawAiProperties/g' {} +
find src/ -name "*.java" -exec sed -i '' 's/OpenClawEmbeddingProperties/SpringClawEmbeddingProperties/g' {} +
find src/ -name "*.java" -exec sed -i '' 's/OpenClaw/SpringClaw/g' {} +
```

---

### Task 10: 重命名配置键和 Maven 元数据

- [ ] **Step 1: 重命名 pom.xml**

```xml
<!-- pom.xml: -->
<groupId>com.springclaw</groupId>
<artifactId>springclaw</artifactId>
<name>springclaw</name>
<description>SpringClaw - Enterprise AI Agent Backend</description>
<!-- mainClass: com.springclaw.SpringClawApplication -->
```

- [ ] **Step 2: 批量替换配置键 openclaw.* → springclaw.***

```bash
# application.yml 中配置键替换
sed -i '' 's/openclaw\./springclaw./g' src/main/resources/application.yml
sed -i '' 's/openclaw:/springclaw:/g' src/main/resources/application.yml
```

- [ ] **Step 3: 替换环境变量前缀 OPENCLAW_ → SPRINGCLAW_**

```bash
sed -i '' 's/OPENCLAW_/SPRINGCLAW_/g' src/main/resources/application.yml
```

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "refactor: rename openclaw to springclaw - packages, classes, config keys, env vars"
```

---

### Task 11: 重命名其他文件引用

- [ ] **Step 1: 替换 .http 文件、Dockerfile、docker-compose.yml 中的引用**

```bash
cd /Users/hanbingzheng/springclaw
# .http 文件
sed -i '' 's/openclaw/springclaw/g' http/openclaw-api.http
mv http/openclaw-api.http http/springclaw-api.http
# Dockerfile
sed -i '' 's/openclaw/springclaw/g' Dockerfile
# docker-compose.yml
sed -i '' 's/openclaw/springclaw/g' docker-compose.yml
# README.md
sed -i '' 's/OpenClaw/SpringClaw/g' README.md
# CLAUDE.md
sed -i '' 's/OpenClaw/SpringClaw/g' CLAUDE.md
sed -i '' 's/openclaw/springclaw/g' CLAUDE.md
```

- [ ] **Step 2: 替换 Spring Boot 自动配置注册文件**

```bash
sed -i '' 's/com\.openclaw/com.springclaw/g' src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

- [ ] **Step 3: 编译验证**

```bash
mvn clean package -DskipTests 2>&1 | tail -20
```

---

## Phase 3: ChatServiceImpl 拆分

### Task 12: 抽取 ChatContextFactory

**Files:**
- Create: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`

- [ ] **Step 1: 创建 ChatContextFactory**

```java
package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * 聊天请求上下文工厂。
 * 负责构建 ChatContext：会话初始化、路由决策、skill 匹配、system prompt 组装。
 */
@Component
public class ChatContextFactory {

    private final AiProviderService aiProviderService;
    private final SoulPromptService soulPromptService;
    private final AgentSessionService agentSessionService;
    private final AuthService authService;
    private final SkillService skillService;
    private final SkillRegistryService skillRegistryService;
    private final ContextAssembler contextAssembler;
    private final ChatRoutingStateService chatRoutingStateService;
    private final ChatRoutingPolicyService chatRoutingPolicyService;
    private final String configuredAgentMode;
    private final boolean routingAutoUpgradeEnabled;

    public ChatContextFactory(/* 构造注入 */) {
        // 保持与 ChatServiceImpl 当前逻辑一致
    }

    public ChatServiceImpl.ChatContext build(ChatRequest request, boolean persistSession) {
        String channel = StringUtils.hasText(request.channel()) ? request.channel() : "api";
        AgentSession session = persistSession
                ? agentSessionService.getOrCreate(request.sessionKey(), channel, request.userId())
                : buildEphemeralSession(request.sessionKey(), channel, request.userId());
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String roleCode = authService.resolveRoleByUserId(request.userId());
        var allowedToolPacks = skillService.resolveAllowedToolPacks(channel, request.userId());
        String effectiveDefaultMode = chatRoutingStateService.resolveDefaultMode(configuredAgentMode);
        boolean effectiveAutoUpgrade = chatRoutingStateService.resolveAutoUpgrade(routingAutoUpgradeEnabled);
        ChatRoutingPolicyService.RoutingDecision routingDecision = chatRoutingPolicyService.decide(
                request.message(), roleCode, effectiveDefaultMode, effectiveAutoUpgrade, allowedToolPacks);
        if (routingDecision == null) {
            routingDecision = new ChatRoutingPolicyService.RoutingDecision(
                    request.message(), effectiveDefaultMode, false, false, "路由策略未返回结果");
        }
        List<SkillDefinition> matchedSkills = skillRegistryService.matchAgentVisibleDefinitions(
                routingDecision.effectiveQuestion(), allowedToolPacks, 2);
        String systemPrompt = soulPromptService.buildSystemPrompt(channel, request.userId(), matchedSkills);
        AssembledContext assembled = contextAssembler.assemble(
                session.getSessionKey(), channel, request.userId(), routingDecision.effectiveQuestion());
        AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
        return new ChatServiceImpl.ChatContext(
                session, channel, request.userId(), roleCode,
                request.message(), routingDecision.effectiveQuestion(),
                requestId, systemPrompt, assembled, activeClient,
                routingDecision.executionMode(), routingDecision.reason());
    }

    private AgentSession buildEphemeralSession(String sessionKey, String channel, String userId) {
        AgentSession session = new AgentSession();
        session.setId(0L);
        session.setSessionKey(sessionKey);
        session.setChannel(channel);
        session.setUserId(userId);
        session.setStatus("ACTIVE");
        return session;
    }
}
```

- [ ] **Step 2: 从 ChatServiceImpl 中移除相关逻辑，改为委托给 ChatContextFactory**

- [ ] **Step 3: 运行测试**

```bash
mvn test -Dtest="com.springclaw.service.chat.**" 2>&1 | tail -20
```

- [ ] **Step 4: 提交**

---

### Task 13: 抽取 ChatResultPersister 和 MetaGuardExecutor

**Files:**
- Create: `src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java`
- Create: `src/main/java/com/springclaw/service/chat/impl/MetaGuardExecutor.java`

- [ ] **Step 1: 创建 ChatResultPersister** — 保存对话结果（事件流记录 + 记忆存储 + 审计记录）

- [ ] **Step 2: 创建 MetaGuardExecutor** — 元话术检测与重试逻辑

- [ ] **Step 3: 更新 ChatServiceImpl 使用这两个新组件**

- [ ] **Step 4: 运行测试并提交**

---

### Task 14: 抽取 StreamChatHandler

**Files:**
- Create: `src/main/java/com/springclaw/service/chat/impl/StreamChatHandler.java`

- [ ] **Step 1: 创建 StreamChatHandler** — SSE 流式输出逻辑

- [ ] **Step 2: 更新 ChatServiceImpl.stream() 方法委托给 StreamChatHandler**

- [ ] **Step 3: 全量测试并提交**

---

## Phase 4: 安全与代码卫生

### Task 15: 安全加固

**Files:**
- Modify: `src/main/java/com/springclaw/config/web/WebMvcConfig.java`
- Modify: `src/main/java/com/springclaw/common/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/springclaw/web/auth/TokenAuthenticationInterceptor.java`

- [ ] **Step 1: CORS 收窄**

```java
// WebMvcConfig.java - 改为配置化
@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedOrigins("${SPRINGCLAW_CORS_ORIGINS:http://localhost:3000}");
}
```

- [ ] **Step 2: 500 异常隐藏内部 detail**

```java
// GlobalExceptionHandler.java
@ExceptionHandler(Exception.class)
public ApiResponse<Void> handleUnknown(Exception ex) {
    log.error("系统异常", ex);  // 日志记录完整异常
    return ApiResponse.fail(500, "系统内部错误，请联系管理员");  // 不暴露 detail
}
```

- [ ] **Step 3: Token 只从 Header 读取**

```java
// TokenAuthenticationInterceptor.java: 删除 query parameter 读取逻辑
private String resolveToken(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (!StringUtils.hasText(authorization)) return "";
    String text = authorization.trim();
    if (text.regionMatches(true, 0, "Bearer ", 0, 7)) {
        return text.substring(7).trim();
    }
    return text;
}
```

- [ ] **Step 4: 提交**

---

### Task 16: 代码卫生

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `.gitignore`

- [ ] **Step 1: 关闭 MyBatis SQL 日志**

```yaml
# application.yml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl
```

- [ ] **Step 2: 打开 DB/Redis 健康检查**

```yaml
management:
  health:
    db:
      enabled: true
    redis:
      enabled: true
```

- [ ] **Step 3: 更新 .gitignore 添加 .DS_Store**

```gitignore
# macOS
.DS_Store
**/.DS_Store
```

- [ ] **Step 4: 删除所有 .DS_Store 文件**

```bash
find /Users/hanbingzheng/springclaw -name ".DS_Store" -delete
```

- [ ] **Step 5: 提交**

---

### Task 17: 最终验证

- [ ] **Step 1: 全量编译**

```bash
cd /Users/hanbingzheng/springclaw && mvn clean package -DskipTests
```

- [ ] **Step 2: 全量测试**

```bash
mvn test
```

- [ ] **Step 3: 确保所有测试通过**

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "chore: complete springclaw simplification - all phases done"
```
