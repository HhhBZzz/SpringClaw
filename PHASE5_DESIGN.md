# Phase 5: 统一 AgentLoop 重构设计（修订版）

> 修订说明：原版低估了"看着相同的代码其实行为不同"的风险。
> 修订版基于逐行代码审查，标注了每个改动的真实风险和前置条件。

---

## 1. 现状：5 条分支 + 4 个引擎

```
executeStream(request)
  │
  ├─ [A] requiresConfirmation?     → streamActionRequired()           终止
  ├─ [B] general + simplified?     → streamBasicModelAnswer()         反应式流，绕过引擎
  ├─ [C] agent + simplified + tool → streamModelLedAnswer()           反应式流+工具，绕过引擎
  ├─ [D] agentRuntime.supports()?  → streamAgentRuntimeAnswer()       阻塞调用，特殊 SSE 事件
  └─ [E] else                      → runAgentExecution()
                                        ├─ if engineSelector != null:   新路径
                                        │     └─ EngineSelector 选引擎
                                        └─ else:                        遗留路径
                                              ├─ shouldUseAgentRuntime → AgentRuntimeEngine
                                              ├─ simplified → SimplifiedOparEngine
                                              └─ opar → OparLoopEngine
```

4 个引擎通过 EngineSelector 按优先级选择：

| 优先级 | 引擎 | 行数 | supports() 条件 |
|--------|------|------|-----------------|
| 1 | BasicStreamEngine | 137 | general intent + 模型可用 |
| 2 | AgentRuntimeEngine | 932 | 非 general + 非确认 + 非危险 + 非 OPAR |
| 3 | OparLoopEngine | 543 | executionMode=opar 或自动升级 |
| 10 | SimplifiedOparEngine | 503 | 始终 true（兜底） |

---

## 2. 四类重复及其真实差异

### 2.1 本地兜底方法（OparLoopEngine vs SimplifiedOparEngine）

**表面看**：4 个方法签名和实现完全相同。

**实际差异**：

| 差异点 | OparLoopEngine | SimplifiedOparEngine |
|--------|---------------|---------------------|
| `localFallbackEnabled` 守卫 | 每个方法都有 `if (!localFallbackEnabled) return null` | **没有此守卫**，始终执行 |
| `localFallbackFirst` 门控 | 前置检查被 `if (localFallbackFirst)` 包裹 | **没有此门控**，始终执行前置检查 |
| 前置检查顺序 | contextAware → controlPlane → priorityStructured | contextAware → controlPlane → priorityStructured |
| 后置兜底 | tryLocalFallbackResult（模型禁用时、降级时） | tryLocalFallbackResult（空回答时、异常时） |

**结论**：方法体相同可以提取，但编排逻辑（何时调用、是否守卫）不能统一。

### 2.2 SSE 流式生命周期（3 个 streaming 方法）

**表面看**：`finished` 守卫、`Disposable` 管理、锁释放逻辑重复。

**实际差异**：

| 差异点 | streamBasicModelAnswer | streamModelLedAnswer | streamAgentRuntimeAnswer |
|--------|----------------------|---------------------|------------------------|
| 数据源 | 反应式 Flux | 反应式 Flux | **阻塞调用**，非反应式 |
| ToolContext | 不需要 | 需要 open/close scope | 不需要 |
| 空回答降级 | streamBlockingFallback | streamBlockingFallback | 不会空（返回结构体） |
| 特殊 SSE 事件 | 无 | 无 | **emitAndRecordRun 发 tool_call/verification** |
| usage source | "stream-basic-answer" | "stream-model-led" | 不记录（在 engine 内记录） |

**结论**：只有 streamBasicModelAnswer 和 streamModelLedAnswer 有结构共性，但共性仅限于 ~20 行 finished 守卫。streamAgentRuntimeAnswer 结构完全不同，不能纳入模板。

### 2.3 runAgentExecution 双轨路径

**现状**：

```java
if (engineSelector != null) { ... 新路径 ... }
else { ... 遗留路径 ... }
```

**问题**：遗留路径存在是因为测试构造函数传入 `engineSelector = null`。

具体受影响的测试：

| 测试文件 | 构造方式 | engineSelector |
|----------|----------|----------------|
| ChatServiceImplModeTest.build() | 18 参构造，显式传 null | **null** |
| ChatServiceImplModeTest.buildWithRuntime() | 18 参构造，显式传 null | **null** |
| ChatServiceImplPersistenceTest | 11 参兼容构造 | **null**（内部传 null） |

**结论**：删除遗留路径前，必须先让所有测试构造函数传入可用的 EngineSelector。

### 2.4 流式分支绕过引擎体系

分支 B 和 C 直接在 ChatServiceImpl 中实现流式输出，不走 EngineSelector。

**能否安全纳入引擎体系**：

| 分支 | 能否变成引擎 | 风险 |
|------|-------------|------|
| B (BasicStream) | 可以，给 BasicStreamEngine 加 stream() | 低——逻辑已经高度相似 |
| C (ModelLed) | 可以，新建 ModelLedStreamEngine | 中——需要处理 ToolContext 生命周期 |
| D (AgentRuntime) | **不应纳入通用流式模板** | 高——emitAndRecordRun 发送特殊 SSE 事件，通用模板会丢失 |

---

## 3. 修订后的实施方案

### Step 1: 提取本地兜底的单个方法（低风险，~20 分钟）

**做什么**：

新建 `LocalExecutionSupport` 服务，只包含 4 个独立的本地兜底方法（不含编排逻辑）。每个引擎保留自己的编排代码，但方法体委托给共享服务。

```java
@Component
public class LocalExecutionSupport {

    private final LocalSkillFallbackService fallbackService;

    // 各引擎传入自己的 enabled 标志
    public LocalSkillResult tryControlPlane(String question, boolean enabled) {
        if (!enabled) return null;
        try { return fallbackService.tryHandleControlPlane(question).orElse(null); }
        catch (Exception ex) { log.warn(...); return null; }
    }

    public LocalSkillResult tryFallback(String question, boolean enabled) { ... }
    public LocalSkillResult tryPriorityStructured(String question, boolean enabled) { ... }
}
```

**不做什么**：

- 不统一编排逻辑（"先试 A 再试 B 再试 C"的顺序和条件）
- 不统一 `localFallbackFirst` / `localFallbackEnabled` 的语义
- contextAwareSupport 已经共享，不重复提取

**OparLoopEngine 改造**：
```java
// Before:
private LocalSkillResult tryControlPlaneLocalResult(String question) {
    if (!localFallbackEnabled) return null;
    try { return localSkillFallbackService.tryHandleControlPlane(question).orElse(null); }
    catch (Exception ex) { ... }
}

// After:
private LocalSkillResult tryControlPlaneLocalResult(String question) {
    return localExecutionSupport.tryControlPlane(question, localFallbackEnabled);
}
```

**SimplifiedOparEngine 改造**：
```java
// Before:
private LocalSkillResult tryControlPlaneLocalResult(String question) {
    try { return localSkillFallbackService.tryHandleControlPlane(question).orElse(null); }
    catch (Exception ex) { ... }
}

// After:
private LocalSkillResult tryControlPlaneLocalResult(String question) {
    return localExecutionSupport.tryControlPlane(question, true); // 无守卫，始终 enabled
}
```

**行为变化**：零。每个引擎的守卫逻辑和编排逻辑完全保留。

**验证**：编译通过 + 278 测试通过。

---

### Step 2: 统一 runAgentExecution 路径（中风险，~30 分钟）

**前置条件**：所有测试构造函数必须传入可用的 EngineSelector。

**改测试构造函数**：

ModeTest.build() 改动：
```java
// Before (line 306-308):
null,                       // agentRuntimeEngine
null,                       // engineSelector

// After:
agentRuntimeEngine,         // 已有 mock 字段
buildEngineSelector()       // 新建 helper 方法
```

新增 helper 方法：
```java
private EngineSelector buildEngineSelector() {
    // 用 mock 引擎构建 EngineSelector
    // BasicStreamEngine + agentRuntimeEngine(mock) + oparLoopEngine(mock) + simplifiedOparEngine(mock)
    List<AgentEngine> engines = List.of(
        mockBasicStreamEngine, agentRuntimeEngine, oparLoopEngine, simplifiedOparEngine
    );
    return new EngineSelector(engines);
}
```

PersistenceTest 改动：从 11 参兼容构造改为 18 参构造，传入 mock EngineSelector。

**改 runAgentExecution()**：
```java
// Before (双轨):
if (engineSelector != null) {
    AgentEngine engine = engineSelector.select(context);
    ...
}
if (shouldUseAgentRuntime(context)) { ... }
return useSimplifiedMode(...) ? simplifiedOparEngine.run(...) : oparLoopEngine.runLoop(...);

// After (单轨):
AgentEngine engine = engineSelector.select(context);
if (engine instanceof AgentRuntimeEngine runtimeEngine) {
    AgentRun run = runtimeEngine.run(context);
    if (sseEventBridge != null) sseEventBridge.recordRunTrace(context, run);
    return run.executionResult();
}
return engine.execute(context, metaGuardExecutor::fallbackAnswer);
```

**行为变化**：零。EngineSelector 的优先级排序保证选中同一个引擎。

**风险**：EngineSelector 构造时如果引擎列表为空会抛异常。需要确认 mock 引擎正确注入。

**验证**：编译通过 + 278 测试通过 + ModeTest 各分支断言不变。

---

### Step 3: 清理 ChatServiceImpl 直接引擎引用（低风险，~15 分钟）

**前置条件**：Step 2 完成。

**做什么**：

Step 2 之后，`runAgentExecution()` 不再直接调用 `oparLoopEngine` 和 `simplifiedOparEngine`。但 ChatServiceImpl 中仍有两处直接引用：

1. `streamAgentRuntimeAnswer()` 直接调用 `agentRuntimeEngine.run()` — **保留**，因为这里需要特殊的 SSE 事件发送
2. `resolveFinalAnswer()` 调用 `oparLoopEngine.tryLocalFallbackResult()` — **改为**委托给 `LocalExecutionSupport`

```java
// Before (resolveFinalAnswer):
LocalSkillResult localResult = oparLoopEngine.tryLocalFallbackResult(context.assembled().question());
if (localResult != null) {
    return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
}

// After:
LocalSkillResult localResult = localExecutionSupport.tryFallback(context.assembled().question(), true);
if (localResult != null) {
    return localExecutionSupport.narrate(context.systemPrompt(), context.assembled(), localResult);
}
```

注意：`narrateLocalExecution()` 方法也需要从 OparLoopEngine 迁移到 LocalExecutionSupport（它依赖 LocalExecutionNarrator，已经是共享组件）。

改完后，ChatServiceImpl 构造函数不再需要直接注入 `OparLoopEngine` 和 `SimplifiedOparEngine`（仅通过 EngineSelector 间接使用）。但 `AgentRuntimeEngine` 仍需保留（streamAgentRuntimeAnswer 直接使用）。

**行为变化**：零。

**验证**：编译通过 + 278 测试通过。

---

### Step 4: 分支 B 纳入引擎体系（中风险，~45 分钟）

**做什么**：

给 BasicStreamEngine 添加流式执行能力，让分支 B 通过引擎选择器调度。

**扩展 AgentEngine 接口**：
```java
public interface AgentEngine {
    // ... 现有方法不变 ...

    /** 是否支持流式执行（默认不支持） */
    default boolean supportsStreaming() { return false; }

    /** 流式执行（默认抛异常，仅流式引擎覆写） */
    default void stream(ChatContext context,
                        SseEmitter emitter,
                        String lockToken,
                        AtomicBoolean lockReleased,
                        AtomicReference<Disposable> disposableRef) {
        throw new UnsupportedOperationException(name() + " does not support streaming");
    }
}
```

**改造 BasicStreamEngine**：
```java
@Override
public boolean supportsStreaming() { return true; }

@Override
public void stream(ChatContext context, SseEmitter emitter,
                   String lockToken, AtomicBoolean lockReleased,
                   AtomicReference<Disposable> disposableRef) {
    // 从 ChatServiceImpl.streamBasicModelAnswer() 迁移
    // 核心逻辑不变：反应式 Flux → sendToken → doOnComplete → persist
}
```

**改造 executeStream()**：
```java
// Before:
if (shouldUseBasicModelStreaming(context)) {
    streamBasicModelAnswer(context, ...);
    return;
}

// After: 不再需要这个 if 分支
// BasicStreamEngine.supports() = true 时，EngineSelector 会选中它
// 然后在统一路径中调用 engine.stream()
```

**统一流式分发**（在 executeStream 末尾）：
```java
AgentEngine engine = engineSelector.select(context);

if (engine.supportsStreaming()) {
    engine.stream(context, emitter, lockToken, lockReleased, disposableRef);
    return;
}

if (engine instanceof AgentRuntimeEngine runtimeEngine) {
    streamAgentRuntimeAnswer(context, lockToken, lockReleased, emitter);
    return;
}

// 阻塞引擎：执行后发送答案
ChatExecutionResult result = runAgentExecution(context);
// ... immediate answer or reflect answer ...
```

**行为变化**：零。BasicStreamEngine.supports() 的优先级和条件与原来的 `shouldUseBasicModelStreaming()` 等价。

**风险点**：BasicStreamEngine 原来不直接持有 SseEventBridge、ChatResultPersister 等依赖。需要增加构造函数参数或通过上下文传入。

**验证**：编译通过 + 278 测试通过 + 手动验证 "你好" 类通用问题仍走流式输出。

---

### Step 5: 分支 C 纳入引擎体系（中高风险，~60 分钟）

**做什么**：

新建 `ModelLedStreamEngine`，吸收 `streamModelLedAnswer()` 的逻辑。

```java
@Component
public class ModelLedStreamEngine implements AgentEngine {

    @Override public String name() { return "model-led-stream"; }
    @Override public int priority() { return 2; } // 在 BasicStream(1) 之后、AgentRuntime(2) 同级别

    @Override
    public boolean supports(ChatContext ctx) {
        // 等价于原 shouldUseModelLedStreaming() 的条件
        return ctx != null
            && "agent".equals(normalizeResponseMode(ctx.responseMode()))
            && !isGeneralDecision(ctx)
            && !isControlPlaneIntent(ctx)
            && !requiresBackendCapabilityExecution(ctx)
            && useSimplifiedMode(ctx.executionMode())
            && isModelCallEnabled(ctx);
    }

    @Override public boolean supportsStreaming() { return true; }

    @Override
    public void stream(ChatContext context, SseEmitter emitter, ...) {
        // 从 ChatServiceImpl.streamModelLedAnswer() 迁移
        // 关键：保留 ToolExecutionContext 的 open/close 生命周期
        ToolExecutionContext toolContext = new ToolExecutionContext(...);
        try (var scope = ToolExecutionContextHolder.open(toolContext)) {
            // 反应式 Flux + tools + sendToken
        }
    }
}
```

**改造 executeStream()**：

删除 `shouldUseModelLedStreaming()` 分支，由引擎选择器统一处理。

**行为变化**：零。

**风险点**：

1. **ToolContext 泄漏**：如果反应式流中途断开，ToolContext 的 scope 可能未关闭。原代码在 `doFinally` 中处理，迁移时必须保留。
2. **优先级冲突**：ModelLedStreamEngine 和 AgentRuntimeEngine 的 priority 都是 2。需要调整为 ModelLedStreamEngine = 2，AgentRuntimeEngine = 3（或更精确地设计 supports() 使它们互斥）。
3. **条件等价性**：`shouldUseModelLedStreaming()` 检查了 `modelLedStreamingEnabled` 配置标志。新引擎的 `supports()` 也需要检查这个标志，但它需要通过 `@Value` 注入。

**验证**：编译通过 + 278 测试通过 + 手动验证带工具调用的流式输出正常。

---

## 4. 不做的事（及原因）

### 4.1 不统一 SSE 流式模板

原方案计划用 `StreamExecutionTemplate` 统一三个流式方法。

经分析：
- streamBasicModelAnswer 和 streamModelLedAnswer 的真正共性仅 ~20 行（finished 守卫），提取模板后模板本身的参数比省下的代码还多
- streamAgentRuntimeAnswer 结构完全不同（阻塞调用 + emitAndRecordRun），不能套用模板

**结论**：不建模板。Step 4/5 将流式逻辑迁移到引擎内部后，ChatServiceImpl 的流式方法自然消失，重复问题随之解决。

### 4.2 不统一本地兜底的编排逻辑

原方案计划用 `LocalExecutionSupport.tryPreFlightLocal()` 替代两个引擎的前置检查。

经分析：
- OparLoopEngine 有 `localFallbackEnabled` + `localFallbackFirst` 双重门控
- SimplifiedOparEngine 无任何门控
- 统一编排会改变其中一个引擎的行为

**结论**：只提取方法体（Step 1），不提取编排逻辑。

### 4.3 不将 AgentRuntime 纳入通用流式模板

原方案计划所有引擎统一实现 `stream()` 接口。

经分析：
- `streamAgentRuntimeAnswer()` 调用 `sseEventBridge.emitAndRecordRun()` 发送 `tool_call` / `skill_call` / `verification` 事件
- 这些事件是 AgentRuntime 独有的，其他引擎不发
- 通用模板会丢失这些事件，前端无法展示能力执行详情

**结论**：AgentRuntime 在 `executeStream()` 中保持特殊处理（`instanceof AgentRuntimeEngine` 分支）。

---

## 5. 实施顺序与依赖

```
Step 1 (LocalExecutionSupport 方法提取)     独立可做
  │
  ├── Step 2 (统一 runAgentExecution)        需改测试构造函数
  │     │
  │     └── Step 3 (清理直接引擎引用)         依赖 Step 1 + 2
  │
  └── Step 4 (BasicStream 纳入引擎)          独立可做
        │
        └── Step 5 (ModelLed 纳入引擎)       依赖 Step 4（共享 StreamableEngine 接口）
```

Step 1 和 Step 4 可以并行开发。

---

## 6. 预期收益（修订版 vs 原版）

| 指标 | 改之前 | 原版方案 | 修订版方案 |
|------|--------|----------|------------|
| ChatServiceImpl 行数 | 888 | ~550 | ~650 |
| 执行路径统一度 | 4 硬编码 + 引擎 | 全部引擎 | B 纳入引擎，D 保留特殊处理 |
| 本地兜底重复 | 4 方法 × 2 引擎 | 0 | 方法体统一，编排保留 |
| SSE 模板统一 | 3 方法重复 | 1 模板 | 不建模板，迁移到引擎后自然消除 |
| 遗留路径 | 双轨 | 单轨 | 单轨 |
| 行为变化风险 | — | 中（多处隐含差异） | **低（每步验证零行为变化）** |
| 可安全回滚 | — | 部分步骤耦合 | **每步独立可回滚** |

修订版比原版少减约 100 行代码，但每一步都是行为零变化、可独立回滚的安全改动。

---

## 7. 每步验证清单

| Step | 编译 | 278 测试 | 手动验证 | 回滚方式 |
|------|------|----------|----------|----------|
| 1 | ✓ | ✓ | — | git revert 1 文件 |
| 2 | ✓ | ✓ | chat() API 返回正常回答 | git revert 2-3 文件 |
| 3 | ✓ | ✓ | — | git revert 1 文件 |
| 4 | ✓ | ✓ | "你好" 走流式输出 | git revert 2-3 文件 |
| 5 | ✓ | ✓ | 带工具的问题走流式+工具 | git revert 2-3 文件 |
