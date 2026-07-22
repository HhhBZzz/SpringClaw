# Agent 范式级切换地基 — PR1 实现计划(枚举 + 引擎 paradigm() + 选择器)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 agent 范式提升为一等概念——新增 `AgentParadigm` 枚举,给 `AgentEngine` 接口加 `paradigm()` 声明归属,`EngineSelector` 支持按请求指定的范式过滤选择(占位范式返回明确"未实现"错误),完全向后兼容。

**Architecture:** 最小扩展现有 `AgentEngine` 抽象(不新建上层)。`AgentParadigm` 7 值:前 3(SINGLE_TURN/OPAR/AUTONOMOUS_LOOP)复用现有 6 引擎显式化,后 4(REACT/PLAN_EXECUTE/REFLECTION/MULTI_AGENT)占位。`EngineSelector.select(ctx, paradigm)` 重载:带 paradigm 时在同范式引擎里按 priority+legacyRank+supports 选,占位范式抛"未实现",null 走原逻辑。

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5 + Mockito + AssertJ(项目既有测试栈,纯单测无 Spring context)。

**Spec:** `docs/superpowers/specs/2026-07-21-agent-paradigm-switching-foundation-design.md`(§4.1/4.2/4.3,§9 PR1 切片)

**分支:** 在新分支 `feat/agent-paradigm-pr1` 上执行,每个 Task 末 commit。

---

## 背景:6 引擎的范式归属(spec §4.2 + 补 AgentRuntimeEngine)

spec §4.2 列了 5 个引擎,漏了 `AgentRuntimeEngine`(agent-runtime)。其 `run()` 是 PLAN→EXECUTE→REFLECT→EVALUATE→retry 循环(Plan-Act-Reflect 语义),`supports()` 用 `isOparContext` 排除 opar/deep 语境接非 general 多步任务——与 OparLoopEngine 同属 OPAR。最终映射:

| 引擎类 | name() | paradigm() |
|--------|--------|------------|
| `BasicStreamEngine` | basic-stream | `SINGLE_TURN` |
| `ModelLedStreamEngine` | model-led-stream | `SINGLE_TURN` |
| `SimplifiedOparEngine` | simplified | `SINGLE_TURN` |
| `AgentRuntimeEngine` | agent-runtime | `OPAR`(spec §4.2 漏列,补) |
| `OparLoopEngine` | opar-loop | `OPAR` |
| `AutonomousLoopEngine` | autonomous-loop | `AUTONOMOUS_LOOP` |

---

## File Structure

- **Create:** `src/main/java/com/springclaw/service/agent/AgentParadigm.java` — 范式枚举(7 值 + `isImplemented()`)
- **Modify:** `src/main/java/com/springclaw/service/agent/AgentEngine.java` — 接口加 `paradigm()` 方法
- **Modify:** 6 个引擎类(见上表)— 各加 `@Override paradigm()` 声明
- **Modify:** `src/main/java/com/springclaw/service/agent/EngineSelector.java` — 重载 `select(ctx, paradigm)` + 占位范式错误
- **Create:** `src/test/java/com/springclaw/service/agent/AgentParadigmTest.java` — 枚举语义单测
- **Modify:** `src/test/java/com/springclaw/service/chat/impl/EngineSelectorTest.java` — paradigm 选择测试 + stub 引擎 helper

---

## Task 1: `AgentParadigm` 枚举

**Files:**
- Create: `src/main/java/com/springclaw/service/agent/AgentParadigm.java`
- Test: `src/test/java/com/springclaw/service/agent/AgentParadigmTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/springclaw/service/agent/AgentParadigmTest.java`:

```java
package com.springclaw.service.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentParadigmTest {

    @Test
    void definesAllSevenParadigms() {
        assertThat(AgentParadigm.values())
                .containsExactlyInAnyOrder(
                        AgentParadigm.SINGLE_TURN,
                        AgentParadigm.OPAR,
                        AgentParadigm.AUTONOMOUS_LOOP,
                        AgentParadigm.REACT,
                        AgentParadigm.PLAN_EXECUTE,
                        AgentParadigm.REFLECTION,
                        AgentParadigm.MULTI_AGENT
                );
    }

    @Test
    void firstThreeAreImplementedRestArePlaceholders() {
        assertThat(AgentParadigm.SINGLE_TURN.isImplemented()).isTrue();
        assertThat(AgentParadigm.OPAR.isImplemented()).isTrue();
        assertThat(AgentParadigm.AUTONOMOUS_LOOP.isImplemented()).isTrue();

        assertThat(AgentParadigm.REACT.isImplemented()).isFalse();
        assertThat(AgentParadigm.PLAN_EXECUTE.isImplemented()).isFalse();
        assertThat(AgentParadigm.REFLECTION.isImplemented()).isFalse();
        assertThat(AgentParadigm.MULTI_AGENT.isImplemented()).isFalse();
    }

    @Test
    void eachParadigmHasDescription() {
        for (AgentParadigm paradigm : AgentParadigm.values()) {
            assertThat(paradigm.description()).isNotBlank();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AgentParadigmTest`
Expected: 编译失败 —— `AgentParadigm` 类不存在。

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/springclaw/service/agent/AgentParadigm.java`:

```java
package com.springclaw.service.agent;

/**
 * Agent 思考范式——用户可在每次请求显式选择的一等概念。
 * <p>
 * 地基阶段:前 3 个范式复用现有引擎显式化;后 4 个为占位范式,
 * 选择时返回明确的"范式未实现,待增量"降级,不静默走错引擎。
 * </p>
 *
 * @see AgentEngine#paradigm()
 */
public enum AgentParadigm {
    SINGLE_TURN("单轮 Function-Calling"),
    OPAR("Observe-Plan-Act-Reflect"),
    AUTONOMOUS_LOOP("自主多步循环"),
    REACT("Thought-Action-Observation"),
    PLAN_EXECUTE("先规划再执行"),
    REFLECTION("反思改进"),
    MULTI_AGENT("多智能体");

    private final String description;

    AgentParadigm(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * 地基阶段是否已有引擎实现。占位范式(REACT/PLAN_EXECUTE/REFLECTION/MULTI_AGENT)
     * 选择时由 EngineSelector 抛出明确的"未实现"错误,避免静默回退到别的范式。
     */
    public boolean isImplemented() {
        return this == SINGLE_TURN || this == OPAR || this == AUTONOMOUS_LOOP;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AgentParadigmTest`
Expected: PASS(3 tests, 0 failures)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/service/agent/AgentParadigm.java \
        src/test/java/com/springclaw/service/agent/AgentParadigmTest.java
git commit -m "feat(agent): 新增 AgentParadigm 范式枚举(7 值,前 3 实现 + 4 占位)"
```

---

## Task 2: `AgentEngine` 接口加 `paradigm()` + 6 引擎声明归属

接口加方法后,所有实现类必须同时实现才能编译,因此本 Task 一次性改接口 + 6 引擎。

**Files:**
- Modify: `src/main/java/com/springclaw/service/agent/AgentEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java`
- Modify: `src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/EngineSelectorTest.java`(加 paradigm 断言)

- [ ] **Step 1: Write the failing test**

在 `EngineSelectorTest.java` 已有的 `runtimeEngine()` / `oparLoopEngine()` helper 之后,新增一个验证 OPAR 两引擎 paradigm 声明的测试(这两个 helper 已实例化 real 引擎):

```java
import com.springclaw.service.agent.AgentParadigm;
// ...(文件顶部已有的 import 之后加这一行)
```

在 `EngineSelectorTest` 类内新增测试方法:

```java
@Test
void runtimeAndOparEnginesDeclareOparParadigm() {
    assertThat(runtimeEngine().paradigm()).isEqualTo(AgentParadigm.OPAR);
    assertThat(oparLoopEngine().paradigm()).isEqualTo(AgentParadigm.OPAR);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=EngineSelectorTest`
Expected: 编译失败 —— `AgentEngine` 没有 `paradigm()` 方法。

- [ ] **Step 3: 接口加 `paradigm()` 方法**

在 `AgentEngine.java` 的 `name()` 方法之后、`priority()` 之前,插入:

```java
    /**
     * 此引擎归属的 Agent 范式。
     * <p>
     * EngineSelector 在请求显式指定 paradigm 时按此过滤选择;
     * 不指定时此值仅用于 trace/timeline 范式标注。
     * </p>
     *
     * @see AgentParadigm
     */
    AgentParadigm paradigm();
```

即把:
```java
    /** 引擎名称（用于日志和 trace） */
    String name();

    /**
     * 优先级，数字越小越优先。
```
改为:
```java
    /** 引擎名称（用于日志和 trace） */
    String name();

    /**
     * 此引擎归属的 Agent 范式。
     * <p>
     * EngineSelector 在请求显式指定 paradigm 时按此过滤选择;
     * 不指定时此值仅用于 trace/timeline 范式标注。
     * </p>
     *
     * @see AgentParadigm
     */
    AgentParadigm paradigm();

    /**
     * 优先级，数字越小越优先。
```

- [ ] **Step 4: 6 引擎各声明 `paradigm()`**

每个引擎类加 `import com.springclaw.service.agent.AgentParadigm;`(若该类在 `service.agent` 包则不需 import,如 `AgentRuntimeEngine`),并在 `name()` 方法后、`priority()` 方法前插入 `paradigm()`。

**`BasicStreamEngine.java`**(在 `com.springclaw.service.chat.impl` 包,需 import):
```java
    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.SINGLE_TURN;
    }
```

**`ModelLedStreamEngine.java`**:
```java
    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.SINGLE_TURN;
    }
```

**`SimplifiedOparEngine.java`**:
```java
    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.SINGLE_TURN;
    }
```

**`AgentRuntimeEngine.java`**(已在 `com.springclaw.service.agent` 包,无需 import):
```java
    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.OPAR;
    }
```

**`OparLoopEngine.java`**:
```java
    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.OPAR;
    }
```

**`AutonomousLoopEngine.java`**:
```java
    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.AUTONOMOUS_LOOP;
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=EngineSelectorTest`
Expected: PASS(原有 3 个测试 + 新增 `runtimeAndOparEnginesDeclareOparParadigm`,0 failures)。

注:`AgentRuntimeEngine` / `OparLoopEngine` 的 OPAR 声明由测试直接验证;其余 4 引擎(BasicStream/ModelLedStream/SimplifiedOpar→SINGLE_TURN,AutonomousLoop→AUTONOMOUS_LOOP)的声明正确性由编译(接口契约)+ 代码审查保证,PR2 路由集成测试将端到端验证。

- [ ] **Step 6: Run full test suite to verify no regression**

Run(注意:shell 有空的 MYSQL_* env,必须显式传全套):
```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,1007+ tests,0 failures。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/service/agent/AgentEngine.java \
        src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java \
        src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java \
        src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java \
        src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java \
        src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java \
        src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java \
        src/test/java/com/springclaw/service/chat/impl/EngineSelectorTest.java
git commit -m "feat(agent): AgentEngine 加 paradigm(),6 引擎声明范式归属(补 AgentRuntimeEngine=OPAR)"
```

---

## Task 3: `EngineSelector` 按 paradigm 过滤选择 + 占位范式错误

**Files:**
- Modify: `src/main/java/com/springclaw/service/agent/EngineSelector.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/EngineSelectorTest.java`

- [ ] **Step 1: Write the failing tests**

在 `EngineSelectorTest.java` 类内新增 stub 引擎 helper + 4 个选择测试。

stub helper(放在类末尾,其他 private helper 附近):
```java
    /** 构造一个可自由指定 name/priority/paradigm/supports 的 stub 引擎,用于选择逻辑单测。 */
    private AgentEngine stub(String name, int priority, AgentParadigm paradigm, boolean supports) {
        return new AgentEngine() {
            @Override public String name() { return name; }
            @Override public AgentParadigm paradigm() { return paradigm; }
            @Override public int priority() { return priority; }
            @Override public boolean supports(ChatContext ctx) { return supports; }
            @Override
            public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
                throw new UnsupportedOperationException();
            }
        };
    }
```

4 个测试方法:
```java
    @Test
    void selectByParadigmReturnsFirstSupportingEngineOfThatParadigm() {
        AgentEngine singleTurnA = stub("simplified", 60, AgentParadigm.SINGLE_TURN, true);
        AgentEngine singleTurnB = stub("basic-stream", 10, AgentParadigm.SINGLE_TURN, true);
        AgentEngine opar = stub("opar-loop", 40, AgentParadigm.OPAR, true);
        EngineSelector selector = new EngineSelector(List.of(singleTurnA, singleTurnB, opar));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        AgentEngine selected = selector.select(ctx, AgentParadigm.SINGLE_TURN);

        // 同 SINGLE_TURN 范式里按 priority+legacyRank,basic-stream(p=10) 先于 simplified(p=60)
        assertThat(selected.name()).isEqualTo("basic-stream");
    }

    @Test
    void selectByParadigmThrowsWhenNoEngineOfThatParadigmSupportsContext() {
        AgentEngine singleTurnUnsupported = stub("simplified", 60, AgentParadigm.SINGLE_TURN, false);
        EngineSelector selector = new EngineSelector(List.of(singleTurnUnsupported));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        assertThatThrownBy(() -> selector.select(ctx, AgentParadigm.SINGLE_TURN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SINGLE_TURN");
    }

    @Test
    void selectByPlaceholderParadigmThrowsNotImplemented() {
        AgentEngine singleTurn = stub("simplified", 60, AgentParadigm.SINGLE_TURN, true);
        EngineSelector selector = new EngineSelector(List.of(singleTurn));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        assertThatThrownBy(() -> selector.select(ctx, AgentParadigm.REACT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未实现");
    }

    @Test
    void selectByNullParadigmDelegatesToDefaultRouting() {
        AgentEngine singleTurn = stub("simplified", 60, AgentParadigm.SINGLE_TURN, true);
        AgentEngine opar = stub("opar-loop", 40, AgentParadigm.OPAR, true);
        EngineSelector selector = new EngineSelector(List.of(singleTurn, opar));
        ChatContext ctx = context("simplified", "any", workspaceDecision());

        AgentEngine selected = selector.select(ctx, null);

        // null paradigm 走原逻辑:按 priority+legacyRank 选第一个 supports()(opar-loop p=40 先于 simplified p=60)
        assertThat(selected.name()).isEqualTo("opar-loop");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=EngineSelectorTest`
Expected: 编译失败 —— `EngineSelector.select(ChatContext, AgentParadigm)` 方法不存在。

- [ ] **Step 3: Implement `select(ctx, paradigm)`**

在 `EngineSelector.java` 的 `select(ChatContext ctx)` 方法之后,新增重载:

```java
    /**
     * 按请求显式指定的范式选择引擎。
     * <ul>
     *   <li>{@code paradigm == null}:走 {@link #select(ChatContext)} 默认路由(向后兼容)。</li>
     *   <li>占位范式({@link AgentParadigm#isImplemented()} false):抛"范式未实现"。</li>
     *   <li>已实现范式:在 {@code engine.paradigm() == paradigm} 且 {@code supports(ctx)} 的引擎里
     *       按 (priority, legacyRank) 选第一个;无匹配抛"无可用引擎"(不回退到别的范式)。</li>
     * </ul>
     *
     * @param ctx      聊天上下文
     * @param paradigm 请求指定的范式,null 表示走默认路由
     * @return 匹配的引擎
     * @throws IllegalStateException 占位范式未实现,或无该范式的引擎支持当前请求
     */
    public AgentEngine select(ChatContext ctx, AgentParadigm paradigm) {
        if (paradigm == null) {
            return select(ctx);
        }
        if (!paradigm.isImplemented()) {
            throw new IllegalStateException(
                    "范式 " + paradigm + "(" + paradigm.description() + ")尚未实现,待增量支持。"
            );
        }
        for (AgentEngine engine : engines) {
            if (engine.paradigm() == paradigm && engine.supports(ctx)) {
                log.debug("范式选择: {} (priority={}) paradigm={} for requestId={}",
                        engine.name(), engine.priority(), paradigm, ctx.requestId());
                return engine;
            }
        }
        throw new IllegalStateException(
                "没有 paradigm=" + paradigm + " 的引擎支持当前请求。requestId="
                        + (ctx == null ? "null" : ctx.requestId())
        );
    }
```

`engines` 字段已按 (priority, legacyRank) 排序(构造函数里),所以遍历顺序即优先级顺序,直接取第一个 paradigm 匹配 + supports 的即可。`import com.springclaw.service.agent.AgentParadigm;` —— 注意 `AgentParadigm` 与 `EngineSelector` 同包(`com.springclaw.service.agent`),无需 import。

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=EngineSelectorTest`
Expected: PASS(原有 + Task2 新增 + 本 Task 4 个,0 failures)。

- [ ] **Step 5: Run full test suite to verify no regression**

Run:
```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,0 failures。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/agent/EngineSelector.java \
        src/test/java/com/springclaw/service/chat/impl/EngineSelectorTest.java
git commit -m "feat(agent): EngineSelector 支持 paradigm 过滤选择 + 占位范式未实现错误"
```

---

## Self-Review

**1. Spec coverage(spec §4.1/4.2/4.3 PR1 范围):**
- §4.1 `AgentParadigm` 枚举 7 值 + 前 3 实现/后 4 占位区分 → Task 1 ✓
- §4.2 `AgentEngine.paradigm()` + 现有引擎映射(含补漏的 AgentRuntimeEngine=OPAR)→ Task 2 ✓
- §4.3 带范式选(同范式按 priority 选 / 无匹配明确错误 / 不静默走错)+ 不带范式向后兼容 → Task 3 ✓
- §7 验收"现有 6 引擎各正确声明 paradigm()"→ Task 2(OPAR 2 个测试直证 + 4 个编译/审查,PR2 端到端)
- §7 验收"选占位范式返回明确未实现"→ Task 3 `selectByPlaceholderParadigmThrowsNotImplemented` ✓
- §7 验收"不带 paradigm 现有路由不变"→ Task 3 `selectByNullParadigmDelegatesToDefaultRouting` + 全量绿 ✓

PR2/3/4 范围(ChatRequest.paradigm 透传 / RunState 记 paradigm + timeline / 前端选择器)不在本 PR,符合 spec §9 切片。

**2. Placeholder scan:** 无 TBD/TODO;所有代码块完整;6 引擎 paradigm() 代码逐个给出;测试 stub helper 完整可编译;命令带全套 env。✓

**3. Type consistency:** `AgentParadigm` 枚举名、`paradigm()` 方法名、`isImplemented()` 方法名、`select(ChatContext, AgentParadigm)` 签名在各 Task 一致。stub helper 实现了 `AgentEngine` 全部方法(name/priority/supports/execute + 新 paradigm())。✓

---

## 风险与注意

- **碰引擎核心**:每 Task 末跑全量测试(带全套 env)。Task 2 改接口会瞬时破坏所有引擎实现编译,必须接口 + 6 引擎同 Task 完成。
- **AgentRuntimeEngine=OPAR 是 spec gap 的补丁**:spec §4.2 漏列,本 PR 据其 Plan-Act-Reflect 语义归 OPAR。若 review 认为应归 AUTONOMOUS_LOOP,改 Task 2 该引擎一行即可。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传 `MYSQL_USER/MYSQL_PASSWORD` 等(见 [[mysql-3306-native-conflict]] 记忆),否则 @SpringBootTest 用空密码连 DB 报 Access denied。
