# Agent 范式级切换地基 — PR3 实现计划(RunState/RunEvent 记 paradigm + timeline 透出)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 paradigm 写进 `RunState`(accept 时记录,acceptance-immutable)+ 每个 `RunEvent` 携带 paradigm 标签 + 后端 trace API 透出 paradigm,使 timeline 能按范式语义化展示阶段(前端渲染交 codex)。

**Architecture:** `RunState`/`RunEvent`/`RunAcceptance` 在 `runtime.contract`/`runtime.lifecycle`(底层契约,纯 JDK 无业务依赖),而 `AgentParadigm` 在 `service.agent`(上层)。为避免底层反向依赖上层,**Task 1 先把 `AgentParadigm` 下沉到 `runtime.contract`**(范式是 run 契语的一部分,放底层后 service.agent 上层与 runtime.contract 都能用,全程类型安全)。然后 RunAcceptance/RunState/RunEvent 加 `AgentParadigm paradigm` 字段(nullable,webhook/task 路径为 null),`RunCoordinator.accept`/`copy`/`event()` 单点写入/透传,canonical 校验容错纳入 paradigm,timeline API 透出。paradigm 随 `state_json`/`event_json` JSON 存取,**无需 flyway 迁移**。

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-07-21-agent-paradigm-switching-foundation-design.md`(§4.5,§9 PR3 切片)。PR1/PR2 已在 main(`AgentParadigm`、`ChatRequest.paradigm`、`ChatContext.paradigm`、`decide` 短路、`select(ctx, paradigm)`)。

**分支:** `feat/agent-paradigm-pr3`(已从 main `65587aae` 创建)。每 Task 末 commit。

---

## 关键设计决策

1. **AgentParadigm 下沉 `service.agent` → `runtime.contract`**:RunState/RunEvent 是底层契约(纯 JDK),不能反向依赖 service.agent。下沉后全程用枚举(类型安全),避免边界 String/name 转换。
2. **paradigm 在 RunState/RunEvent/RunAcceptance 是 acceptance-immutable + nullable**:accept 时记录,后续不变;webhook/task 路径(无 ChatRequest)传 null。与 `roleCodeAtAcceptance` 同簇。
3. **无需 flyway 迁移**:paradigm 随 `state_json`/`event_json` JSON 序列化(枚举按 name),索引列无 paradigm。
4. **timeline 语义化分两半**:PR3 后端只透出 paradigm 标签(`AgentRunTraceEvent.paradigm` + replay Map + run row);前端按 paradigm 分组渲染(question/tool/answer vs observe/plan/act/reflect)属 codex 新工作。
5. **canonical 校验容错 nullable**:`AcceptedRunContextResolver.resolve` 在 `request.paradigm() != null` 时才比对(容错 null);`sameAcceptance` + `RunTransitionPolicy` 纳入 paradigm 不变性。

---

## File Structure

- **Move:** `AgentParadigm.java` `service.agent` → `runtime.contract`(Task 1)
- **Modify:** 所有 `import com.springclaw.service.agent.AgentParadigm` → `com.springclaw.runtime.contract.AgentParadigm`(Task 1,~15 文件)
- **Modify:** `src/main/java/com/springclaw/runtime/lifecycle/RunAcceptance.java` — 加 `paradigm` 字段(第 11)
- **Modify:** `src/main/java/com/springclaw/runtime/contract/RunState.java` — 加 `paradigm` 字段(第 28,末尾)
- **Modify:** `src/main/java/com/springclaw/runtime/contract/RunEvent.java` — `RunEvent` + `Draft` 加 `paradigm`,`persisted()` 透传
- **Modify:** `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java` — `accept`/`copy`/`event()` 写入/透传 paradigm
- **Modify:** `src/main/java/com/springclaw/controller/ChatController.java` — accept 调用传 `request.paradigm()`
- **Modify:** `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`、`service/task/executor/TaskExecutionService.java` — accept 调用传 `null`
- **Modify:** `src/main/java/com/springclaw/runtime/bridge/AcceptedRunContext.java` — 加 `paradigm()` delegate
- **Modify:** `src/main/java/com/springclaw/runtime/bridge/AcceptedRunContextResolver.java` — 容错比对 paradigm
- **Modify:** `src/main/java/com/springclaw/runtime/contract/RunTransitionPolicy.java` — paradigm 不变性校验
- **Modify:** `src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java` — `sameAcceptance` 加 paradigm
- **Modify:** `src/main/java/com/springclaw/service/agent/AgentRunTraceEvent.java` — 加 `paradigm` 字段
- **Modify:** `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java` — `toTraceEvent`/replay/row 透出 paradigm
- **Modify:** ~14+3 个测试文件 — `new RunState(`/`new RunEvent(`/`new RunAcceptance(` 构造点补 paradigm(编译驱动)

---

## Task 1: 把 `AgentParadigm` 下沉到 `runtime.contract`

**Files:**
- Move: `src/main/java/com/springclaw/service/agent/AgentParadigm.java` → `src/main/java/com/springclaw/runtime/contract/AgentParadigm.java`
- Modify: 所有 `import com.springclaw.service.agent.AgentParadigm` 的文件

- [ ] **Step 1: 移动文件 + 改 package**

把 `AgentParadigm.java` 从 `service/agent/` 移到 `runtime/contract/`。改 package 声明:
```java
package com.springclaw.runtime.contract;
```
(`git mv src/main/java/com/springclaw/service/agent/AgentParadigm.java src/main/java/com/springclaw/runtime/contract/AgentParadigm.java` 后改 package 行。其余内容不变。)

- [ ] **Step 2: 全局改 import**

`grep -rln "import com.springclaw.service.agent.AgentParadigm" src/` 找所有引用(~15 文件,含 PR1/PR2 改的 AgentEngine/EngineSelector/6 引擎/ChatRequest/ChatContext/ChatRoutingPolicyService + 测试)。把 `import com.springclaw.service.agent.AgentParadigm;` 改为 `import com.springclaw.runtime.contract.AgentParadigm;`。

注意:`AgentParadigm.java` 原在 `service.agent`,可能被同包类无 import 直接用(如 `AgentEngine`、`AgentRuntimeEngine` 在 `service.agent`,原来不 import)。移到 `contract` 后这些同包类要加 `import com.springclaw.runtime.contract.AgentParadigm;`。用 `mvn -q test-compile` 编译错误驱动定位所有需要加 import 的点。

- [ ] **Step 3: 移动测试 + 全量验证**

`AgentParadigmTest.java` 的 package 改为 `com.springclaw.runtime.contract`(移到对应 test 目录),import 调整。

Run 全量(带全套 env):
```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,0 failures(paradigm 全程行为不变,只是换了包)。

- [ ] **Step 4: Commit**

```bash
git add -A  # 含文件移动 + import 改 + 测试移动
git commit -m "refactor(agent): AgentParadigm 下沉到 runtime.contract(供 RunState/RunEvent 使用)"
```
(确认只 add AgentParadigm 相关改动;不 add `.claude/worktrees/`、`.tmp_interview_doc/`。)

---

## Task 2: `RunAcceptance` + `RunState` 加 paradigm(+ accept/copy/调用点/delegate)

**Files:**
- Modify: `RunAcceptance.java`、`RunState.java`、`RunCoordinator.java`(accept + copy)、`ChatController.java`、`WebhookRouterService.java`、`TaskExecutionService.java`、`AcceptedRunContext.java`
- Test: `RunCoordinatorTest.java` 等(加 paradigm 断言)+ 编译驱动的 `new RunState(`/`new RunAcceptance(` 补参

- [ ] **Step 1: Write the failing test**

在 `RunCoordinatorTest.java`(已有 `acceptance()` helper ~L300)加测试,验证 accept 记录 paradigm:

```java
@Test
void acceptRecordsParadigmFromAcceptance() {
    RunAcceptance acceptance = acceptanceWithParadigm(AgentParadigm.OPAR); // 新 helper 或改既有 acceptance()
    RunState state = coordinator.accept(acceptance);
    assertThat(state.paradigm()).isEqualTo(AgentParadigm.OPAR);
}

@Test
void acceptAllowsNullParadigmForWebhookAndTaskPaths() {
    RunAcceptance acceptance = acceptance(); // 既有 helper,paradigm=null
    RunState state = coordinator.accept(acceptance);
    assertThat(state.paradigm()).isNull();
}
```
(`acceptanceWithParadigm` 可基于既有 `acceptance()` helper 加 paradigm 参数;或直接改 `acceptance()` helper 带 paradigm。先读 `RunCoordinatorTest` 的 acceptance helper 确认。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=RunCoordinatorTest`
Expected: 编译失败 —— `RunAcceptance`/`RunState` 无 `paradigm` 字段。

- [ ] **Step 3: `RunAcceptance` 加 paradigm(第 11 字段,nullable)**

`RunAcceptance.java` record 头末尾加 `AgentParadigm paradigm`(在 `deadlineAt` 之后),import `com.springclaw.runtime.contract.AgentParadigm`(Task 1 后 contract 包)。compact constructor **不加校验**(允许 null,与其他 nullable acceptance 字段一致——实际 acceptance 字段都 requireText,但 paradigm 是枚举 nullable,直接保留 null,无需 require)。

- [ ] **Step 4: `RunState` 加 paradigm(第 28 字段,末尾,nullable)**

`RunState.java` record 头末尾加 `AgentParadigm paradigm`(在 `Failure failure` 之后)。compact constructor **不加校验**(nullable,直接保留)。import `com.springclaw.runtime.contract.AgentParadigm`(同包,无需 import——Task 1 后 AgentParadigm 在 contract,RunState 也在 contract)。

- [ ] **Step 5: `RunCoordinator.accept` + `copy` 写入/透传 paradigm**

`accept`(L30-38):`new RunState(...)` 末尾补 `acceptance.paradigm()`(在 `Map.of(), null` 后加 `, acceptance.paradigm()`)。

`copy`(L322-331):`new RunState(...)` 末尾补 `current.paradigm()`(在 `usage, failure` 后加 `, current.paradigm()`)——acceptance-immutable,跟 `current.roleCodeAtAcceptance()` 同列透传。

- [ ] **Step 6: 3 个 accept 调用点传 paradigm**

`grep -rn "new RunAcceptance(" src/main/java` 找 3 处:
- `ChatController.java:268-291`(`acceptRun`):传 `request.paradigm()`(ChatRequest 已有 paradigm)
- `WebhookRouterService.java:116-127`:传 `null`(无 ChatRequest)
- `TaskExecutionService.java:150-166`:传 `null`

在 RunAcceptance 构造末尾补 paradigm 实参。

- [ ] **Step 7: `AcceptedRunContext` 加 `paradigm()` delegate**

`AcceptedRunContext.java`(wrap RunState,L14-44 现有 6 个 delegate)加:
```java
public AgentParadigm paradigm() {
    return runState.paradigm();
}
```

- [ ] **Step 8: 测试构造点补参 + 跑测试**

`grep -rn "new RunState(\|new RunAcceptance(" src/test` 找所有构造点(~14 RunState + ~20 RunAcceptance),用编译错误驱动逐一补 paradigm 实参(多数测试 helper 补 null,带 paradigm 的测试 helper 补枚举值)。

Run: `mvn test -Dtest=RunCoordinatorTest`(应 PASS,含新测试)
Run 全量(带 env):Expected BUILD SUCCESS,0 failures。

- [ ] **Step 9: Commit**

```bash
git add <RunAcceptance/RunState/RunCoordinator/ChatController/WebhookRouterService/TaskExecutionService/AcceptedRunContext + 测试补参文件>
git commit -m "feat(runtime): RunState/RunAcceptance 记 paradigm(accept 时写入,nullable)+ 调用点透传"
```

---

## Task 3: `RunEvent`/`Draft` 加 paradigm + `event()` 单点焊

**Files:**
- Modify: `RunEvent.java`(RunEvent + Draft + persisted)、`RunCoordinator.java`(event())
- Test: `RunEventContractTest.java` 等 + 编译驱动补参

- [ ] **Step 1: Write the failing test**

在 `RunCoordinatorTest.java` 加测试,验证每个事件携带 run 的 paradigm:

```java
@Test
void eventsCarryParadigmFromState() {
    RunState state = coordinator.accept(acceptanceWithParadigm(AgentParadigm.OPAR));
    coordinator.contextReady(state.runId(), snapshot(), T0.plusSeconds(1));
    assertThat(store.findEventsByRunId(state.runId()))
            .extracting(RunEvent::paradigm)
            .allMatch(p -> p == AgentParadigm.OPAR); // 所有事件都带 OPAR
}
```
(若 `contextReady` 后事件足够;也可断言 RUN_CREATED 事件 paradigm。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=RunCoordinatorTest`
Expected: 编译失败 —— `RunEvent.paradigm()` 不存在。

- [ ] **Step 3: `RunEvent` + `Draft` 加 paradigm**

`RunEvent.java`:
- `RunEvent` record 头末尾加 `AgentParadigm paradigm`(第 13 字段,在 `correlationId` 之后)。compact constructor 不校验(nullable)。
- `Draft` record 头末尾加 `AgentParadigm paradigm`(第 11 字段)。compact constructor 不校验。
- `Draft.persisted(String eventId, long sequence)`(L78-93):`new RunEvent(...)` 末尾补 `paradigm`(透传 Draft 的 paradigm)。

- [ ] **Step 4: `RunCoordinator.event()` 焊 paradigm**

`event()`(L334-343):`new RunEvent.Draft(...)` 末尾补 `state.paradigm()`(在 `state.requestId()` 后)。这是单点——所有 commit/append/observation 事件都过 `event()`,自动携带 paradigm。

- [ ] **Step 5: 测试构造点补参 + 跑测试**

`grep -rn "new RunEvent(\|new RunEvent.Draft(" src/test` 找构造点(~3),补 paradigm(多数 null)。

Run: `mvn test -Dtest=RunCoordinatorTest,RunEventContractTest`(应 PASS)
Run 全量:Expected BUILD SUCCESS,0 failures。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/RunEvent.java \
        src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java \
        <测试补参文件>
git commit -m "feat(runtime): RunEvent/Draft 携带 paradigm(event() 单点焊,覆盖全部事件)"
```

---

## Task 4: canonical 校验容错纳入 paradigm

**Files:**
- Modify: `AcceptedRunContextResolver.java`、`RunTransitionPolicy.java`(runtime.contract)、`MySqlRunLifecycleStore.java`(sameAcceptance)
- Test: `AcceptedRunContextResolverTest.java`、`MySqlRunLifecycleStoreIT.java`/`InMemoryRunLifecycleStoreTest.java`

- [ ] **Step 1: Write the failing tests**

在 `AcceptedRunContextResolverTest.java` 加:
```java
@Test
void resolveRejectsParadigmMismatchWhenRequestSpecifiesOne() {
    // runState.paradigm()=OPAR,request.paradigm()=SINGLE_TURN → mismatch
    // (request.paradigm()=null 时容忍,不报 mismatch)
}
```
(以既有 resolver 测试模式为准。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AcceptedRunContextResolverTest`

- [ ] **Step 3: `AcceptedRunContextResolver.resolve` 容错比对 paradigm**

`AcceptedRunContextResolver.java` `resolve()`(L23-49),在 `responseMode` 比对之后、`requireMatchingClaim` 之前,加 nullable 容错比对:
```java
        // paradigm 容错:仅当请求显式指定 paradigm 时才比对(webhook/task 路径 paradigm=null,容忍)
        if (request.paradigm() != null) {
            requireMatching("paradigm", request.paradigm().name(), runState.paradigm() == null ? null : runState.paradigm().name());
        }
```
(nullable 容错:请求带 paradigm 才校验;runState null + 请求非 null → mismatch;都 null → 通过。)

- [ ] **Step 4: `sameAcceptance` 加 paradigm**

`MySqlRunLifecycleStore.java` `sameAcceptance`(L320-332,比对 11 字段),加:
```java
                && Objects.equals(existing.paradigm(), candidate.paradigm())
```

- [ ] **Step 5: `RunTransitionPolicy` 加 paradigm 不变性**

读 `RunTransitionPolicy.java`(runtime.contract)的 `validate` 方法(比对 previous/next 的 acceptance-immutable 字段,如 roleCodeAtAcceptance)。加 paradigm 不变性校验:
```java
        requireEqual(previous.paradigm(), next.paradigm(), "paradigm");
```
(与 roleCodeAtAcceptance 同簇——acceptance-immutable 字段,转换时不变。以实际 validate 方法签名为准。)

- [ ] **Step 6: 跑测试 + 全量**

Run: `mvn test -Dtest=AcceptedRunContextResolverTest,InMemoryRunLifecycleStoreTest`(应 PASS)
Run 全量:Expected BUILD SUCCESS,0 failures。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/AcceptedRunContextResolver.java \
        src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java \
        src/main/java/com/springclaw/runtime/contract/RunTransitionPolicy.java \
        <测试>
git commit -m "feat(runtime): canonical 校验纳入 paradigm(resolver 容错 + sameAcceptance + 不变性)"
```

---

## Task 5: timeline 后端透出 paradigm

**Files:**
- Modify: `AgentRunTraceEvent.java`(加 paradigm 字段)、`AgentRunTraceService.java`(toTraceEvent + replay + row)
- Test: `AgentRunTraceServiceTest.java`

- [ ] **Step 1: Write the failing test**

在 `AgentRunTraceServiceTest.java` 加:
```java
@Test
void traceEventCarriesParadigmFromRunEvent() {
    // 构造带 paradigm 的 RunEvent,经 toTraceEvent,断言 AgentRunTraceEvent.paradigm()
}
```
(以既有 trace service 测试模式为准。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AgentRunTraceServiceTest`

- [ ] **Step 3: `AgentRunTraceEvent` 加 paradigm 字段**

读 `AgentRunTraceEvent.java`(L3-18,16 字段,2 兼容构造 L22-58)。record 头末尾加 `AgentParadigm paradigm`(nullable),import `com.springclaw.runtime.contract.AgentParadigm`。2 个兼容构造末尾补 `null`。

- [ ] **Step 4: `AgentRunTraceService` 透出 paradigm**

- `toTraceEvent(RunEvent)`(L239-259):从 `event.paradigm()` 赋给 AgentRunTraceEvent 的 paradigm(在构造 AgentRunTraceEvent 时补 `event.paradigm()`)。
- `buildTraceEvent`(legacy 路径,L155-195):legacy trace 无 paradigm 来源,传 `null`。
- `canonicalReplayRun`(L846-861):`result.put("paradigm", state.paradigm() == null ? null : state.paradigm().name())`。
- `toCanonicalRunRow`(L352-369):recent runs row 加 paradigm(可选,从 `state.paradigm()`)。

(以实际方法签名/行号为准;grep `toTraceEvent\|canonicalReplayRun\|toCanonicalRunRow` 定位。)

- [ ] **Step 5: 跑测试 + 全量**

Run: `mvn test -Dtest=AgentRunTraceServiceTest`(应 PASS)
Run 全量:Expected BUILD SUCCESS,0 failures。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/agent/AgentRunTraceEvent.java \
        src/main/java/com/springclaw/service/agent/AgentRunTraceService.java \
        src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java
git commit -m "feat(trace): AgentRunTraceEvent 透出 paradigm(toTraceEvent/replay/row)"
```

---

## Self-Review

**1. Spec coverage(§4.5 PR3 范围):**
- run 的 paradigm 写进 RunState(accept 时记录)→ Task 2 ✓
- 每个 RunEvent 携带 paradigm 标签 → Task 3(event() 单点焊)✓
- 前端 timeline 按范式语义化 → Task 5 后端透出标签;前端渲染交 codex(spec §4.5 前端部分 + 记忆"前端统一 timeline 交 codex")✓
- canonical 校验(调研建议)→ Task 4 ✓

PR4(前端选择器)不在本 PR。

**2. Placeholder scan:** Task 1-3 代码完整(基于已读源码)。Task 4-5 给了行号 + 改动代码片段 + "以实际为准"指引(implementer 读实际方法确认),非 placeholder。命令带全套 env。✓

**3. Type consistency:** `AgentParadigm paradigm` 字段/参数名、`paradigm()` 访问器跨 Task 一致。Task 1 下沉后全程用 `com.springclaw.runtime.contract.AgentParadigm`。✓

---

## 风险与注意

- **Task 1 移动枚举影响面大**(~15 文件 import 改),但机械。用编译错误驱动定位所有需加 import 的点(包括原来同包无 import 的 service.agent 类)。移动后全量测试必须绿(行为不变)。
- **RunState/RunEvent/RunAcceptance 加字段是 canonical 契约变更**:大量测试构造点补参(编译驱动,~14+3+20 处)。`CanonicalTransportIdentityTest.createdRun`(:131)等直接构造要补。
- **nullable paradigm**:RunState/RunEvent/RunAcceptance 的 paradigm 允许 null(webhook/task 路径)。compact constructor 不校验 null。canonical 比对容错(request 带 paradigm 才校验)。
- **无需 flyway 迁移**:paradigm 随 state_json/event_json JSON 存取。若 `RunStateContractTest.terminalRunStateSurvivesJacksonRoundTrip` 等往返测试因新字段失败,补测试断言 paradigm 往返即可。
- **碰状态机核心**:每 Task 末全量测试(带全套 env)。Task 4 的 RunTransitionPolicy/Resolver 改动影响不变量,尤其要确认 nullable 容错不破坏现有 accept/transition 测试。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
