# Agent 范式级切换地基 — PR2 实现计划(ChatRequest.paradigm 透传 + 路由尊重)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `AgentParadigm`(PR1 已就绪)从 HTTP 请求透传到 `EngineSelector.select(ctx, paradigm)` 调用点,并在请求显式指定 paradigm 时让 `ChatRoutingPolicyService` 跳过意图自动路由,使范式选择端到端生效。

**Architecture:** paradigm 作为请求级元数据进 `ChatRequest`(record 末尾字段)→ 跨 async 经 `AsyncChatRequestMessage`(RabbitMQ 序列化)→ 进 `ChatContext`(第 18 字段)→ `ChatContextFactory.createPlanning` 把 paradigm 传给 `ChatRoutingPolicyService.decide`(新增 7 参重载,paradigm 非空时短路返回中性 decision + paradigm→executionMode 映射,让引擎 `supports()` 能匹配)→ `ChatServiceImpl` 三处 `select(context)` 改 `select(context, context.paradigm())`。paradigm=null 时全链路走原逻辑,**完全向后兼容**。

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-07-21-agent-paradigm-switching-foundation-design.md`(§4.3,§9 PR2 切片)。PR1(`AgentParadigm` + `AgentEngine.paradigm()` + `EngineSelector.select(ctx, paradigm)`)已在 main。

**分支:** `feat/agent-paradigm-pr2`(已从 main `1c0d35ee` 创建)。每 Task 末 commit。

---

## 范式 → executionMode 映射(关键设计)

引擎 `supports()` 依赖 `executionMode`/`responseMode`。paradigm 非空时,`decide` 短路按 paradigm 映射 executionMode,保证 `select(ctx, paradigm)` 能选到引擎:

| paradigm | executionMode | 选到的引擎(supports 命中) |
|----------|---------------|---------------------------|
| `SINGLE_TURN` | `simplified` | SimplifiedOparEngine(兜底 `return true`)优先 basic-stream/model-led |
| `OPAR` | `opar` | OparLoopEngine(`"opar".equals(executionMode)`) |
| `AUTONOMOUS_LOOP` | `opar` | AutonomousLoopEngine(需 `opar` + decision riskLevel=write/side_effect/dangerous;只读任务无匹配→明确报错,符合 spec) |

映射规则:`SINGLE_TURN → "simplified"`,其余(`OPAR`/`AUTONOMOUS_LOOP`)→ `"opar"`。占位范式(REACT 等)不会进到这里——`select(ctx, paradigm)` 已在 PR1 拦截(`isImplemented()` false 抛"未实现"),decide 的 paradigm 短路只处理已实现范式。

---

## File Structure

- **Modify:** `src/main/java/com/springclaw/dto/chat/ChatRequest.java` — record 加 `paradigm` 字段(末尾)
- **Modify:** `src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java` — record 加 `paradigm` 字段(跨 RabbitMQ)
- **Modify:** `src/main/java/com/springclaw/controller/ChatController.java` — `normalizeRequest`/`sendAsync` 透传 paradigm
- **Modify:** `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java` — 重建 ChatRequest 时透传 paradigm
- **Modify:** `src/main/java/com/springclaw/service/chat/impl/ChatContext.java` — record 加 `paradigm` 字段(第 18)+ 3 兼容构造补 null
- **Modify:** `src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java` — `decide` 7 参重载 + paradigm 短路 + `paradigmToExecutionMode`
- **Modify:** `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java` — `buildCanonical`/`buildLegacy` 的 `new ChatContext` 传 paradigm + `createPlanning` 链透传 paradigm 到 decide
- **Modify:** `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java` — 3 处 `select(context)` → `select(context, context.paradigm())`
- **Modify:** 次要入口 `WebhookRouterService.java`、`TaskExecutionService.java` — `new ChatRequest(...)` 补 `null`(paradigm 不适用)

---

## Task 1: `ChatRequest` + `AsyncChatRequestMessage` 加 paradigm 字段 + 透传

**Files:**
- Modify: `src/main/java/com/springclaw/dto/chat/ChatRequest.java`
- Modify: `src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java`
- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java`
- Modify: `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`
- Modify: `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
- Test: `src/test/java/com/springclaw/dto/chat/ChatRequestTest.java`(新建,若无)

- [ ] **Step 1: Write the failing test**

新建(或追加到现有)`src/test/java/com/springclaw/dto/chat/ChatRequestTest.java`:

```java
package com.springclaw.dto.chat;

import com.springclaw.service.agent.AgentParadigm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {

    @Test
    void canonicalConstructorCarriesParadigm() {
        ChatRequest req = new ChatRequest("s1", "u1", "hi", "api", "agent", AgentParadigm.OPAR);
        assertThat(req.paradigm()).isEqualTo(AgentParadigm.OPAR);
    }

    @Test
    void fourArgCompatConstructorDefaultsParadigmToNull() {
        ChatRequest req = new ChatRequest("s1", "u1", "hi", "api");
        assertThat(req.paradigm()).isNull();
    }
}
```

(若 `ChatRequest` 现有 5 参 canonical 是 `(sessionKey, userId, message, channel, responseMode)`,上面用 6 参;以实际 record 头为准。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ChatRequestTest`
Expected: 编译失败 —— `ChatRequest` 没有 `paradigm` 字段/6 参构造。

- [ ] **Step 3: `ChatRequest` 加 paradigm 字段**

修改 `ChatRequest.java`:顶部加 `import com.springclaw.service.agent.AgentParadigm;`;record 头末尾加 `AgentParadigm paradigm`;4 参兼容构造转调补 `null`。

把 record 头(当前 `public record ChatRequest(String sessionKey, String userId, @NotBlank String message, String channel, String responseMode)`)改为末尾追加 `AgentParadigm paradigm`:

```java
public record ChatRequest(
        @NotBlank(...) String sessionKey,
        String userId,
        @NotBlank(...) String message,
        String channel,
        String responseMode,
        AgentParadigm paradigm) {
```

4 参兼容构造(当前 `this(sessionKey, userId, message, channel, null)`)改为:

```java
    public ChatRequest(String sessionKey, String userId, String message, String channel) {
        this(sessionKey, userId, message, channel, null, null);
    }
```

- [ ] **Step 4: 修复所有 `new ChatRequest(` 构造点(5 处,补 paradigm 实参)**

编译会报错(5 处 5 参构造缺第 6 参)。逐一修复:

1. **`ChatController.normalizeRequest`(`:257` 附近)** —— 重建时透传原 request 的 paradigm:
   把 `new ChatRequest(sessionKey, userId, message, channel, responseMode)` 改为 `new ChatRequest(sessionKey, userId, message, channel, responseMode, request.paradigm())`。
2. **`ChatMessageConsumer.consume`(`:50-56`)** —— 从 `AsyncChatRequestMessage` 重建,补 `message.paradigm()`:
   `new ChatRequest(sessionKey, userId, message, channel, responseMode, msg.paradigm())`。
3. **`WebhookRouterService.java:130`** —— 次要入口,补 `null`:`new ChatRequest(..., null)`。
4. **`TaskExecutionService.java:251`** —— 次要入口,补 `null`:`new ChatRequest(..., null)`。
5. **测试 / 其他 5 参构造** —— 若有,补 `null`(或对应 paradigm)。

(用 `mvn -q test-compile` 快速定位所有报错点。)

- [ ] **Step 5: `AsyncChatRequestMessage` 加 paradigm 字段(跨 RabbitMQ)**

修改 `AsyncChatRequestMessage.java`:加 `import com.springclaw.service.agent.AgentParadigm;`;record 头末尾加 `AgentParadigm paradigm`;兼容构造补 `null`。

record 头末尾加字段(当前 7 字段 `requestId/sessionKey/userId/message/channel/createdAt/responseMode`):

```java
public record AsyncChatRequestMessage(String requestId, String sessionKey, String userId,
                                      String message, String channel, long createdAt,
                                      String responseMode, AgentParadigm paradigm) {
```

兼容构造(当前 6 参或无参兜底)末尾补 `null`。

- [ ] **Step 6: `ChatController.sendAsync` 构造 AsyncChatRequestMessage 时补 paradigm**

`ChatController.java:150-158` 构造 `AsyncChatRequestMessage` 时,末尾补 `normalizedRequest.paradigm()`:

```java
new AsyncChatRequestMessage(requestId, sessionKey, userId, message, channel, createdAt,
        responseMode, normalizedRequest.paradigm())
```

- [ ] **Step 7: Run test + 全量编译验证**

Run: `mvn test -Dtest=ChatRequestTest`(应 PASS:2 tests)
Run 全量(带全套 env):
```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,0 failures(paradigm=null 默认,所有现有行为不变)。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/springclaw/dto/chat/ChatRequest.java \
        src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java \
        src/main/java/com/springclaw/controller/ChatController.java \
        src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java \
        src/main/java/com/springclaw/service/webhook/WebhookRouterService.java \
        src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java \
        src/test/java/com/springclaw/dto/chat/ChatRequestTest.java
git commit -m "feat(chat): ChatRequest/AsyncChatRequestMessage 加 paradigm 字段 + 三模式透传"
```

---

## Task 2: `ChatRoutingPolicyService.decide` 加 paradigm 重载 + 短路

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatRoutingPolicyServiceTest.java`(已存在,追加)

- [ ] **Step 1: Write the failing tests**

在 `ChatRoutingPolicyServiceTest` 追加(确认该测试类存在;若名不同,以实际为准):

```java
import com.springclaw.service.agent.AgentParadigm;
// ...

@Test
void decideWithParadigmSkipsAutoUpgradeAndMapsExecutionMode() {
    ChatRoutingPolicyService service = newChatRoutingPolicyService(); // 用既有 helper
    String complexQuestion = "深度分析这个复杂的多步骤项目并执行写入操作"; // 命中 shouldAutoUpgrade 特征

    // 不带 paradigm:复杂任务会 autoUpgrade 到 opar
    ChatRoutingPolicyService.RoutingDecision auto = service.decide(
            complexQuestion, "USER", "simplified", true, Set.of(), null);
    assertThat(auto.executionMode()).isEqualTo("opar");
    assertThat(auto.autoUpgraded()).isTrue();

    // 带 OPAR paradigm:短路,autoUpgraded=false,executionMode=opar(映射),reason 标注 paradigm
    ChatRoutingPolicyService.RoutingDecision byParadigm = service.decide(
            complexQuestion, "USER", "simplified", true, Set.of(), null, AgentParadigm.OPAR);
    assertThat(byParadigm.executionMode()).isEqualTo("opar");
    assertThat(byParadigm.autoUpgraded()).isFalse();
    assertThat(byParadigm.manualOverride()).isTrue();
    assertThat(byParadigm.reason()).contains("OPAR");
}

@Test
void decideWithSingleTurnParadigmMapsToSimplified() {
    ChatRoutingPolicyService service = newChatRoutingPolicyService();
    ChatRoutingPolicyService.RoutingDecision d = service.decide(
            "复杂任务", "USER", "opar", true, Set.of(), null, AgentParadigm.SINGLE_TURN);
    assertThat(d.executionMode()).isEqualTo("simplified");
    assertThat(d.autoUpgraded()).isFalse();
}

@Test
void decideWithNullParadigmFallsBackToAutoRouting() {
    ChatRoutingPolicyService service = newChatRoutingPolicyService();
    // null paradigm 走原逻辑(与 6 参 decide 等价)
    ChatRoutingPolicyService.RoutingDecision d = service.decide(
            "简单问题", "USER", "simplified", true, Set.of(), null, null);
    ChatRoutingPolicyService.RoutingDecision baseline = service.decide(
            "简单问题", "USER", "simplified", true, Set.of(), null);
    assertThat(d).isEqualTo(baseline);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ChatRoutingPolicyServiceTest`
Expected: 编译失败 —— `decide(..., AgentParadigm)` 7 参重载不存在。

- [ ] **Step 3: 加 7 参 decide 重载 + paradigm 短路 + paradigmToExecutionMode**

在 `ChatRoutingPolicyService.java` 顶部加 `import com.springclaw.service.agent.AgentParadigm;`。

把现有 6 参 `decide`(:55)的方法签名保留,在其**之后**新增 7 参重载(6 参转调 7 参传 `null` paradigm):

```java
    public RoutingDecision decide(String question,
                                  String roleCode,
                                  String defaultMode,
                                  boolean autoUpgradeEnabled,
                                  Set<String> allowedToolPacks,
                                  String responseMode) {
        return decide(question, roleCode, defaultMode, autoUpgradeEnabled, allowedToolPacks, responseMode, null);
    }

    public RoutingDecision decide(String question,
                                  String roleCode,
                                  String defaultMode,
                                  boolean autoUpgradeEnabled,
                                  Set<String> allowedToolPacks,
                                  String responseMode,
                                  AgentParadigm paradigm) {
        String normalizedQuestion = StringUtils.hasText(question) ? question.trim() : "";
        String normalizedRole = normalizeRole(roleCode);
        String normalizedDefaultMode = normalizeMode(defaultMode);
        String normalizedResponseMode = normalizeResponseMode(responseMode);
        String intent = detectIntent(normalizedQuestion, allowedToolPacks);

        // paradigm 显式指定:跳过自动路由(shouldAutoUpgrade/前缀强制/responseMode 链路),
        // executionMode 按 paradigm 映射,让 EngineSelector.select(ctx, paradigm) 能选到对应引擎。
        if (paradigm != null) {
            PrefixMatch stripped = stripAnyModePrefix(normalizedQuestion);
            return new RoutingDecision(
                    stripped.content(),
                    paradigmToExecutionMode(paradigm),
                    true,
                    false,
                    "用户显式指定范式 " + paradigm + "(" + paradigm.description() + "),跳过自动路由。",
                    normalizedResponseMode,
                    intent
            );
        }

        if ("fast".equals(normalizedResponseMode)) {
            // ... 原有 fast 分支(从原 6 参 decide 整体搬过来,删掉旧的 6 参方法体留这个 7 参)
```

**注意**:原 6 参 `decide` 方法体(从 `String normalizedQuestion = ...` 到方法结束)整体搬到 7 参重载里,6 参重载变成纯转调(如上)。即:把原 6 参方法的方法体移进 7 参方法(在 paradigm 短路之后接原所有 `if fast/tool/deep/forceOpar/...` 逻辑),原 6 参签名改为一行转调。

在类末尾加私有映射方法:

```java
    /** 范式 → executionMode 映射,让引擎 supports() 能匹配 paradigm 选择。 */
    private static String paradigmToExecutionMode(AgentParadigm paradigm) {
        return paradigm == AgentParadigm.SINGLE_TURN ? "simplified" : "opar";
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ChatRoutingPolicyServiceTest`
Expected: PASS(原有测试 + 新增 3 个,0 failures)。原 6 参/5 参调用点行为不变(转调传 null paradigm)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java \
        src/test/java/com/springclaw/service/chat/impl/ChatRoutingPolicyServiceTest.java
git commit -m "feat(chat): ChatRoutingPolicyService.decide 支持 paradigm 短路 + executionMode 映射"
```

---

## Task 3: `ChatContext` 加 paradigm 字段 + `ChatContextFactory` 透传到 decide

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContext.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java`(已存在,追加)

- [ ] **Step 1: Write the failing test**

在 `ChatContextFactoryTest` 追加(确认类名/既有 helper):

```java
import com.springclaw.service.agent.AgentParadigm;
// ...

@Test
void buildPropagatesRequestParadigmIntoContext() {
    ChatRequest request = new ChatRequest("s1", "u1", "分析项目", "api", "agent", AgentParadigm.OPAR);
    // 用既有 helper 构造 factory(可能需要 contextSnapshotFactoryEnabled=false 走 buildLegacy)
    ChatContextFactory factory = newFactoryWithLegacyPath(); // 既有或新建 helper

    ChatContext ctx = factory.build(request, false, "run-test");

    assertThat(ctx.paradigm()).isEqualTo(AgentParadigm.OPAR);
}
```

(若既有 helper 难以构造,可简化为断言 `buildLegacy` 产出的 ChatContext 携带 paradigm;以实际测试基础设施为准。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ChatContextFactoryTest`
Expected: 编译失败 —— `ChatContext.paradigm()` 不存在。

- [ ] **Step 3: `ChatContext` 加 paradigm 字段(第 18)+ 3 兼容构造补 null**

`ChatContext.java` 顶部加 `import com.springclaw.service.agent.AgentParadigm;`;record 头末尾(在 `ContextSnapshot contextSnapshot` 之后)加 `AgentParadigm paradigm`:

```java
                          ContextInjection contextInjection,
                          ContextSnapshot contextSnapshot,
                          AgentParadigm paradigm) {
```

compact constructor(`public ChatContext { contextInjection = ... }`)不动。

3 个兼容构造(12 参 `:36`、15 参 `:57`、16 参 `:79`)末尾转调补 `null`。例如 12 参构造当前转调末尾是 `ContextInjection.empty(), null)`,改为 `ContextInjection.empty(), null, null)`;15 参/16 参同理,在转调的最后一个 `null` 后再加 `, null`。

- [ ] **Step 4: `ChatContextFactory` 的 `new ChatContext` 两处补 paradigm + `createPlanning` 链透传**

**(a)** `buildCanonical`(:214)和 `buildLegacy`(:271)的 `new ChatContext(...)` 末尾(在 `snapshot`/`null` 之后)补 `request.paradigm()`:

`buildCanonical`(:214-232)末尾把 `view.injection(), snapshot)` 改为 `view.injection(), snapshot, request.paradigm())`。

`buildLegacy`(:271-289)末尾把 `injection, null)` 改为 `injection, null, request.paradigm())`。

**(b)** `createPlanning` 链加 paradigm 参数透传到 decide:

`createPlanning`(:337)签名末尾加 `AgentParadigm paradigm`:
```java
    private CanonicalPlanning createPlanning(
            String sessionKey, String channel, String userId, String roleCode,
            String requestId, String routingQuestion, String responseMode,
            boolean buildPrompt, AgentParadigm paradigm) {
```
其内 `decide(...)` 调用(:360-367)末尾补 paradigm 实参(用 7 参重载):
```java
        ChatRoutingPolicyService.RoutingDecision routingDecision = chatRoutingPolicyService.decide(
                routingQuestion, roleCode, effectiveDefaultMode, effectiveAutoUpgrade,
                allowedToolPacks, responseMode, paradigm
        );
```

`createCanonicalPlanning`(:292)签名加 `AgentParadigm paradigm`,转调 `createPlanning` 传 `accepted... , buildPrompt, paradigm)`。其调用点(`buildCanonical` 内 :190 `createCanonicalPlanning(accepted, accepted.originalMessage(), true)`)改为传 `request.paradigm()`:
```java
            createCanonicalPlanning(accepted, accepted.originalMessage(), true, request.paradigm())
```
(:206 的另一处 `createCanonicalPlanning(accepted, snapshot.effectiveMessage(), false)` 同理补 `request.paradigm()`。)

`createLegacyPlanning`(:309)签名加 `AgentParadigm paradigm`,转调 `createPlanning` 传 paradigm。其调用点(`buildLegacy` :245 `createLegacyPlanning(sessionKey, ..., request.responseMode())`)末尾补 `request.paradigm()`。

- [ ] **Step 5: Run test + 全量验证**

Run: `mvn test -Dtest=ChatContextFactoryTest`(应 PASS)
Run 全量(带全套 env):
```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,0 failures。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ChatContext.java \
        src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
        src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java
git commit -m "feat(chat): ChatContext 加 paradigm 字段 + Factory 透传到 decide"
```

---

## Task 4: `ChatServiceImpl` 三处 `select` 接入 paradigm

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`(:244, :656, :696)
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java`(已存在,追加)

- [ ] **Step 1: Write the failing test**

在 `ChatServiceImplModeTest` 追加(确认既有 mock 基础设施;若难以构造完整 ChatServiceImpl,可用 EngineSelectorTest 的 stub 思路验证 select 调用,或简化为验证 ChatContext.paradigm 透传):

```java
import com.springclaw.service.agent.AgentParadigm;
// ...

@Test
void selectReceivesParadigmFromContextWhenSpecified() {
    // 用既有 ChatServiceImpl mock 基础设施构造 service + spy 的 EngineSelector
    // 断言:当 ChatRequest 带 paradigm=OPAR 时,engineSelector.select(ctx, OPAR) 被调用
    // (以实际测试基础设施为准;若 ChatServiceImplModeTest 已有 select 验证模式,沿用)
}
```

(若完整集成测试太重,本 Task 验证可降级为:确认 3 处 `select(context, context.paradigm())` 调用点语法正确 + 全量测试绿 + 已有的 `selectByNullParadigmDelegatesToDefaultRouting` 等价覆盖 null 路径。)

- [ ] **Step 2: Run test to verify it fails**(若写了新测试)

- [ ] **Step 3: 三处 select 改 paradigm 重载**

`ChatServiceImpl.java` 三处 `engineSelector.select(context)` 改为 `engineSelector.select(context, context.paradigm())`:

1. **`:244`**(`executeStream`,stream 路径)
2. **`:656`**(`executeInternal`,send + async 路径)
3. **`:696`**(`runAgentExecution`,stream 内非 Streamable 引擎二次选择)

(以实际行号为准;grep `engineSelector.select(context)` 定位所有调用点,逐一改。)

- [ ] **Step 4: Run full test suite**

Run(带全套 env):
```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,0 failures。`context.paradigm()` 为 null 时 `select(ctx, null)` 走原逻辑(PR1 保证),所有现有路由测试不变。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
        src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java
git commit -m "feat(chat): ChatServiceImpl 三处 select 接入 context.paradigm()"
```

---

## Self-Review

**1. Spec coverage(§4.3 PR2 范围):**
- `ChatRequest` 加可选 `paradigm` 字段 → Task 1 ✓
- `EngineSelector` 带 paradigm 时按范式选(无匹配明确错误)→ PR1 已实现,Task 4 接入调用点 ✓
- 不带 paradigm 走原 priority+supports 路由(向后兼容)→ Task 2 `decide` null 分支 + Task 4 `select(ctx, null)` 委托 ✓
- `ChatRoutingPolicyService` 显式 paradigm 时跳过意图自动路由 → Task 2 paradigm 短路 ✓
- SSE/异步路径透传 paradigm → Task 1 `AsyncChatRequestMessage` + `ChatMessageConsumer` ✓

PR3(RunState 记 paradigm + timeline)/ PR4(前端选择器)不在本 PR,符合 §9 切片。

**2. Placeholder scan:** Task 1/2 的代码块完整(基于已读源码)。Task 3/4 的测试若既有基础设施限制,给了降级验证策略(非 placeholder,是明确的备选方案)。所有命令带全套 env。✓

**3. Type consistency:** `AgentParadigm paradigm` 字段/参数名、`paradigm()` 访问器、`decide(..., AgentParadigm paradigm)` 7 参签名、`select(ctx, ctx.paradigm())`、`paradigmToExecutionMode` 跨 Task 一致。✓

---

## 风险与注意

- **碰路由核心**:`ChatRoutingPolicyService.decide` 是路由中枢。Task 2 的 7 参重载 + 6 参转调保证向后兼容(null paradigm 走原逻辑)。每 Task 末全量测试(带全套 env)。
- **AUTONOMOUS_LOOP 只读任务**:paradigm=AUTONOMOUS_LOOP 映射 executionMode=opar,但 AutonomousLoopEngine.supports 还要求 decision riskLevel=write/side_effect/dangerous。只读任务选 AUTONOMOUS_LOOP 会 `select(ctx, AUTONOMOUS_LOOP)` 无匹配 → 明确报错(符合 spec §4.3"无匹配返回明确错误")。这是预期行为,不是 bug。
- **Task 3 透传链较深**:`createCanonicalPlanning`/`createLegacyPlanning`/`createPlanning` 都要加 paradigm 参数。漏一个会编译错(快速暴露)。
- **Task 4 测试**:ChatServiceImpl 完整集成测试依赖重,若难以写 paradigm 透传的端到端测试,降级为"3 处 select 调用点正确 + 全量绿"验证(EngineSelectorTest 的 `selectByNullParadigmDelegatesToDefaultRouting` 已覆盖 null 路径)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
