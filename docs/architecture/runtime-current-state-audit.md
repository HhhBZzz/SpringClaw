# Runtime Current-State Audit

> Phase 1 / Workstream B characterization output.
> Branch: `claude/runtime-characterization` · Base: `36ca396`
> Status: read-only audit; no production code modified.

This document records the **as-built** state of SpringClaw's chat runtime so the unified-runtime spec has a falsifiable starting point. It does not propose a target architecture. Where a responsibility has more than one source of truth, the duplication is named explicitly.

Companion ledger: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`.

---

## 1. AgentEngine implementations (6, not 5)

Interface: [AgentEngine.java](../../src/main/java/com/springclaw/service/agent/AgentEngine.java) — `name() / priority() / supports(ChatContext) / execute(...)`. Streaming subtype `StreamableAgentEngine` adds `stream(...)`.

| # | Class | priority() | supports() condition (verbatim guard) | StreamableAgentEngine? |
|---|---|---|---|---|
| 1 | [`BasicStreamEngine`](../../src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java) | **1** | `basicStreamingEnabled && useSimplifiedMode(executionMode) && (responseMode in {"agent","fast"}) && isGeneralIntent(ctx) && modelCallEnabled` | ✅ |
| 2 | [`AgentRuntimeEngine`](../../src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java) | **2** | `decision != null && !decision.isGeneral() && !decision.requiresConfirmation() && !decision.isDangerous() && !isOparContext(ctx)` | ❌ |
| 3 | [`AutonomousLoopEngine`](../../src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java) | **2** | `executionMode=="opar" && activeClient.available() && decision != null && !decision.isGeneral() && riskLevel in {write, side_effect, dangerous}` | ✅ |
| 4 | [`OparLoopEngine`](../../src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java) | **3** | `executionMode=="opar" \|\| routingReason contains "自动升级"` | ❌ |
| 5 | [`ModelLedStreamEngine`](../../src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java) | **5** | `modelLedStreamingEnabled && responseMode=="agent" && (decision==null \|\| !decision.isGeneral()) && !requiresConfirmation && intent ∉ {control_plane, model_control, local_files} && !requiresBackendCapabilityExecution && useSimplifiedMode && modelCallEnabled` | ✅ |
| 6 | [`SimplifiedOparEngine`](../../src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java) | **10** | `return true` (unconditional fallback) | ❌ |

Selector: [`EngineSelector.select`](../../src/main/java/com/springclaw/service/agent/EngineSelector.java) does linear scan in ascending `priority()` and returns the first `supports()==true`. **Two engines share `priority()=2`** (`AgentRuntimeEngine` and `AutonomousLoopEngine`); they are kept disjoint by mutually-exclusive `supports()` clauses (`!isOparContext(ctx)` vs `executionMode=="opar"`). The order between two engines with equal priority is therefore determined by the order Spring injects them — implicit, not declared.

### 1.1 Route → engine examples

| Routing input | RoutingDecision.executionMode | AgentDecision.intent / risk | Selected engine |
|---|---|---|---|
| `general` chat, `agent` responseMode, no skill/keyword match | `simplified` | `general / read` | `BasicStreamEngine` (priority 1) |
| `local_files` intent, `read` risk, simplified mode | `simplified` | `local_files / read` | `AgentRuntimeEngine` (priority 2, `!isGeneral`) |
| Auto-upgraded to opar (score ≥ 3), `write` risk | `opar` | `workspace_analysis / write` | `AutonomousLoopEngine` (priority 2, `executionMode==opar`) |
| Manual `深度分析:` prefix (ADMIN/DEVELOPER), `read` risk | `opar` | `general / read` | `OparLoopEngine` (priority 3, AutonomousLoop's `!isGeneral` fails) |
| `agent` responseMode, no confirm, `general` request, model-led streaming flag on | `simplified` | `general / read` | `ModelLedStreamEngine` (priority 5, BasicStream blocked because intent allows it but flag layout) |
| `dangerous` risk requiring confirmation | `simplified` | `workspace_analysis / dangerous, requiresConfirmation=true` | `SimplifiedOparEngine` (everything else's guard fails); proposal flow takes over |
| Anything else | (any) | (any) | `SimplifiedOparEngine` (priority 10, `return true`) |

---

## 2. Routing — three sources of truth in series

| Decider | File | Output type | Concern |
|---|---|---|---|
| [`ChatRoutingPolicyService.decide`](../../src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java) | `RoutingDecision { effectiveQuestion, executionMode (simplified\|opar), manualOverride, autoUpgraded, reason, responseMode (fast\|deep\|tool\|agent), intent (general\|web-research\|control-plane\|local-files\|workspace-analysis\|tool-skill) }` | Maps `defaultMode + auto-upgrade-enabled + question keywords` to `executionMode` and an internal intent token |
| [`AgentDecisionService.decide`](../../src/main/java/com/springclaw/service/agent/AgentDecisionService.java) | `AgentDecision { intent, executionPath (basic_model\|agent_tools\|skill_direct\|task_draft\|ask_clarification), selectedCapabilities, riskLevel (read\|write\|side_effect\|dangerous), requiresConfirmation, reason }` | Maps capability registry + optional model JSON router to `intent`, `riskLevel`, `requiresConfirmation` |
| [`EngineSelector.select`](../../src/main/java/com/springclaw/service/agent/EngineSelector.java) | `AgentEngine` | Re-derives routing from `executionMode + responseMode + intent + riskLevel + requiresConfirmation + feature flags` to pick an engine |

Each layer redefines the same axes with **different value spaces**:

- `intent` — `RoutingDecision.intent` (kebab) ≠ `AgentDecision.intent` (snake). `RoutingDecision.intent` is computed but not consumed downstream; `EngineSelector` reads `AgentDecision.intent`.
- `executionMode` (RoutingDecision) and `executionPath` (AgentDecision) overlap conceptually — both describe "how this should run" — but use disjoint vocabularies (`simplified|opar` vs `basic_model|agent_tools|...`).
- `requiresConfirmation` lives only on `AgentDecision`; `RoutingDecision` has no such field.

`AgentDecisionService.decide` may invoke a **model-call JSON router** (`renderRouterPrompt` + `parseModelDecision`, [AgentDecisionService.java:168-235](../../src/main/java/com/springclaw/service/agent/AgentDecisionService.java)) when capability match confidence is low. This is one of the LLM calls that can fire **before any engine starts**.

### 2.1 Auto-upgrade scoring (RoutingDecision)

`shouldAutoUpgrade(question)` ([ChatRoutingPolicyService.java:166-201](../../src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java)) sums:

- Category A (problem-type words: `分析/排查/定位/修复/对比/比较/梳理/拆解/设计/审查`) — +1 each
- Category B (domain words: `日志/报错/异常/堆栈/代码/项目/配置/启动/调用链/接口/类/文件/sql/redis/rabbitmq`) — +1 each
- Category C (multi-step words: `先/再/然后/同时/并且/分别/逐步/一步一步/最后`) — +1 each
- Category D (length): `question.length() ≥ 28` — +1
- Category E (formatting): contains newline or fenced block — +1
- Threshold: `score ≥ 3` ⇒ auto-upgrade `simplified → opar`.

There is also a high-confidence skill match short-circuit ([line 170-182](../../src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java)) that returns `true` regardless of score.

---

## 3. Risk and confirmation

| Concern | Owner(s) | Note |
|---|---|---|
| Computed risk level of the request | `AgentDecisionService` (`AgentDecision.riskLevel`) | One source. |
| Whether confirmation is required *before* engine runs | `AgentDecisionService.requiresConfirmation()` | Predicted. |
| Whether the actual tool call needs a proposal | [`ToolRuntimeAspect.resolveRiskLevel`](../../src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java) + `requiresProposal` predicate | Authoritative. Per the P0 spec, the runtime risk is final; the predicted risk is only a hint. |
| Workspace-level guarding (path normalisation, dirty-file tracking, git baseline) | [`WorkspaceGitGuard`](../../src/main/java/com/springclaw/service/workspace/WorkspaceGitGuard.java) | Wraps the actual tool execution. |
| Proposal lifecycle / state machine / authentication | [`ToolInvocationProposalService`](../../src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java), [`ToolProposalController`](../../src/main/java/com/springclaw/controller/proposal/ToolProposalController.java) | One source for the proposal record. |

**Two sources of truth for risk** — predicted (`AgentDecision.riskLevel`) vs runtime (`ToolRuntimeAspect.resolveRiskLevel`). P0 already designates the runtime owner as authoritative; the predicted value is consumed by `EngineSelector` (engine pickup) and `AutonomousLoopEngine.supports` (only `write/side_effect/dangerous` requests are routed to it). **The same axis is used for two different decisions in two different layers.**

---

## 4. Context construction and injection

### 4.1 Producers

| Producer | File | Output |
|---|---|---|
| [`ContextAssembler.assemble`](../../src/main/java/com/springclaw/service/context/ContextAssembler.java) | `AssembledContext { observePrompt, eventContext, semanticContext, question, ... }` | `observePrompt` is the concatenation of (1) current question, (2) Memory Bank snapshot, (3) recent `message_event` rows, (4) vector recall. |
| [`ChatContextFactory.build`](../../src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java:123-133) | `ContextInjection { observePrompt, … }` | Pass-through wrapper of `AssembledContext.observePrompt` plus reserved fields `decisionContext / risk` (currently empty). |
| [`MemoryBankService.renderSnapshot`](../../src/main/java/com/springclaw/service/memory/MemoryBankService.java) | Markdown rendering of `docs/memory-bank/*.md` | Read by `ContextAssembler.assemble` only. |
| [`MessageEventChatMemory`](../../src/main/java/com/springclaw/config/ai/ChatMemoryConfig.java:57-83) | Spring AI `ChatMemory` adapter | Reads recent CHAT events from `message_event`. |
| Vector store (when `springclaw.embedding.enabled=true`) | `VectorMemoryService.recallBySession / recallByUser` | Currently default-off; `EmbeddingModel` and `VectorStore` beans are not created. |

### 4.2 Injectors (advisors that mutate the model request)

| Advisor | Always on? | What it injects |
|---|---|---|
| [`SemanticMemoryAdvisor`](../../src/main/java/com/springclaw/service/chat/impl/SemanticMemoryAdvisor.java) | **Yes (unconditionally registered)** | Calls `memoryService.recallBySession + recallByUser`, appends `LONG_TERM_MEMORY:` block to system message |
| [`MessageChatMemoryAdvisor`](../../src/main/java/com/springclaw/config/ai/ChatMemoryConfig.java) | Only if `springclaw.chat.spring-ai-chat-memory-enabled=true` (default `false`) | Reads `message_event` history via `ChatMemory` |
| `QuestionAnswerAdvisor` | **Not registered** | Spring AI's RAG advisor is absent in this codebase. |

Container: [`ConversationAdvisorSupport.apply`](../../src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java:32-43) builds the advisor list at call time. Both lists always contain `SemanticMemoryAdvisor`.

### 4.3 Consumers (engines reading observePrompt or contextInjection)

| Engine | Reads `ctx.assembled().observePrompt()` | Reads `ctx.contextInjection().renderForPrompt()` | Goes through `ConversationAdvisorSupport.apply` |
|---|---|---|---|
| BasicStreamEngine | indirect | ✅ ([line 303](../../src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java)) | ✅ |
| SimplifiedOparEngine | indirect | ✅ ([line 270](../../src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java)) | ✅ |
| OparLoopEngine | ✅ (directly in `runLoop`) | ❌ (does not call `renderForPrompt`) | ✅ |
| AutonomousLoopEngine | indirect | ✅ ([line 397](../../src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java)) | ✅ |
| ModelLedStreamEngine | indirect | ✅ ([line 403](../../src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java)) | ✅ |
| AgentRuntimeEngine | ✅ | ✅ ([line 297, 748](../../src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java)) | ✅ |

### 4.4 Observed duplication

1. **Long-term semantic memory is materialised twice per call.** `ContextAssembler.buildSemanticContext` reads `memoryService.recallBySession + recallByUser` and embeds them inside `observePrompt`; `SemanticMemoryAdvisor.augment` reads the same methods again at the request boundary and adds `LONG_TERM_MEMORY:` to the system message. With embedding disabled both calls return empty, but the dual-callsite shape persists and re-enabling embedding produces two near-identical recall blocks per request.
2. **Short-term event history is materialised twice when the Spring-AI ChatMemory flag is on.** `ContextAssembler.buildEventContext` and `MessageChatMemoryAdvisor` (via `MessageEventChatMemory`) both read `message_event`. Default config disables one of them, hiding the duplication today.
3. **`ContextInjection` is a wrapper of `observePrompt`, but `OparLoopEngine` reads from `assembled.observePrompt()` directly while the other engines read through `ContextInjection`** — the data is identical, but the access path is not uniform.

---

## 5. Capability / tool execution

### 5.1 Tool entry points (5)

| # | Entry point | Files | Goes through `ToolRuntimeAspect`? |
|---|---|---|---|
| 1 | Spring AI `requestSpec.tools(tools)` (registers `@Tool` proxies, model decides when to call) | `AutonomousLoopEngine:227-228`, `OparLoopEngine:359-360`, `ModelLedStreamEngine:131-132 / 209-210`, `SimplifiedOparEngine:168-169` | ✅ via Spring AOP CGLIB proxy |
| 2 | Local shortcut path (deterministic answers via `LocalSkillFallbackService`) calling `@Tool` methods on injected ToolPack beans | `OparLoopEngine:134-177`, `SimplifiedOparEngine:125-138`, `AutonomousLoopEngine:171-179`, `LocalExecutionNarrator` | ✅ — calls go through Spring proxy beans, so the aspect still fires |
| 3 | `CapabilityExecutorRegistry.execute` (backend capabilities, e.g., WebSearch, WorkspaceSearch) | [`AgentRuntimeEngine.run`:209](../../src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java) | ✅ if the capability ultimately calls a `@Tool`-annotated method on a Spring bean; otherwise the capability is its own execution path |
| 4 | Direct `ToolInvoker.invoke` triggered by approved proposals during async execution | [`ToolProposalExecutionService.onExecutionRequested`:65](../../src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java) | ✅ — explicitly routes through `ToolRuntimeAspect` for second-pass validation |
| 5 | `AutonomousExecutionTracker` "fake-completion guard" (records tool effects after the aspect already ran) | [`AutonomousLoopEngine:190-197`](../../src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java) | After the aspect — it is a post-execution observer, not a separate entry |

`ToolOrchestrator` ([`selectAgentTools / selectAutonomousTools / selectTools`](../../src/main/java/com/springclaw/tool/runtime/ToolOrchestrator.java)) is **not** a tool entry; it only filters which `ToolCallback` beans get registered into the `requestSpec`.

### 5.2 Invariant 11 guards already in tree

- [`ToolRuntimeAspectInterceptionIT.invokingToolMethodOnSpringProxyTriggersAspect`](../../src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectInterceptionIT.java) — verifies `@Tool` calls on a Spring proxy fire the aspect.
- [`ToolRuntimeAspectGuardTest`](../../src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectGuardTest.java), [`SpringToolInvokerIT`](../../src/test/java/com/springclaw/service/proposal/SpringToolInvokerIT.java) — proposal-resume path goes through aspect.

No path was found that bypasses the aspect by `new`-ing a ToolPack instance.

---

## 6. Completion signals — at least 5 disjoint sources

| # | Signal | Used by | File:line |
|---|---|---|---|
| 1 | `TASK_COMPLETE` / `TASK_FAILED` text marker matched by regex | `AutonomousLoopEngine` | [AutonomousLoopEngine.java:47-48, 263-308](../../src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java) |
| 2 | `AutonomousExecutionTracker.satisfiesCompletionCondition(riskLevel)` | `AutonomousLoopEngine` (combined with #1 for write/side_effect/dangerous) | [AutonomousExecutionTracker.java:129-174](../../src/main/java/com/springclaw/service/chat/impl/AutonomousExecutionTracker.java) |
| 3 | `ReflectionResult.sufficient == true` (parsed from a JSON model response) | `AgentRuntimeEngine` | [AgentRuntimeEngine.java:170-173, 348-349, 370-398](../../src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java) |
| 4 | `MAX_REFLECTION_ATTEMPTS = 3` hard cap on reflection loops | `AgentRuntimeEngine` | [AgentRuntimeEngine.java:147-201](../../src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java) |
| 5 | `PlanResult.ready()` boolean returned from a JSON-parsed plan response, or `stepNo > maxAgentSteps` | `OparLoopEngine` | [OparLoopEngine.java:197-242](../../src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java) |
| 6 | Reactive `doOnComplete` (model stream finished) | `BasicStreamEngine`, `ModelLedStreamEngine` | [BasicStreamEngine.java:129-165](../../src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java), [ModelLedStreamEngine.java:236-273](../../src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java) |
| 7 | Single blocking `executeChat()` returning | `SimplifiedOparEngine` | [SimplifiedOparEngine.java:154-202](../../src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java) |
| 8 | `VerificationResult.sufficient` (consumed only for trace status, not for flow control) | `ChatServiceImpl.streamAgentRuntimeAnswer` | [ChatServiceImpl.java:365-367](../../src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java) |

`AutonomousLoopEngine` combines signals 1 + 2 (`TASK_COMPLETE` and tracker) for write/side_effect/dangerous risk levels — a single engine reads two different completion truth sources in the same loop iteration. `AgentRuntimeEngine` combines 3 + 4. There is no shared completion contract across engines.

---

## 7. Final-answer composition — 12 sites, several overlapping

Sites that produce, replace, or rewrite the user-visible answer text:

1. Engine direct return (`SimplifiedOparEngine.run`, `BasicStreamEngine.execute`, `OparLoopEngine.runLoop` → `reflect`, `AutonomousLoopEngine.resolveFinalAnswer`, `AgentRuntimeEngine.summarize` / `buildFinalDegradedResult` / `deterministicAnswer`).
2. [`ChatServiceImpl.streamReflectAnswer`](../../src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java) (lines 421-503) — emits a separate streaming reflection LLM call for OparLoopEngine.
3. [`ChatServiceImpl.resolveFinalAnswer`](../../src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java) (lines 548-571) — calls `metaGuardExecutor.execute` for blocking paths.
4. [`MetaGuardExecutor.execute`](../../src/main/java/com/springclaw/service/chat/impl/MetaGuardExecutor.java) — meta-refusal detection plus retry LLM calls (`retry-times=1` by default).
5. [`MetaGuardExecutor.normalize`](../../src/main/java/com/springclaw/service/chat/impl/MetaGuardExecutor.java) — empty/refusal/XML-hallucination cleanup.
6. [`MetaGuardExecutor.fallbackAnswer`](../../src/main/java/com/springclaw/service/chat/impl/MetaGuardExecutor.java) — final canned reply.
7. [`ChatResponsePolicyService.stripHallucinatedXmlBlocks`](../../src/main/java/com/springclaw/service/chat/impl/ChatResponsePolicyService.java) — removes hallucinated XML tool calls (also called from `SimplifiedOparEngine`).
8. [`ChatResponsePolicyService.buildPartialAnswerFromAction`](../../src/main/java/com/springclaw/service/chat/impl/ChatResponsePolicyService.java) — degraded answer assembled from action trace.
9. [`ChatResponsePolicyService.buildUserFacingFailureReply`](../../src/main/java/com/springclaw/service/chat/impl/ChatResponsePolicyService.java) — last-resort canned text.
10. [`LocalExecutionNarrator.narrate`](../../src/main/java/com/springclaw/service/chat/impl/LocalExecutionNarrator.java) — model-translates structured local-skill output.
11. [`LocalSkillFallbackService.LocalSkillResult.fallbackAnswer`](../../src/main/java/com/springclaw/service/chat/LocalSkillFallbackService.java) — raw deterministic text used when the model is unavailable.
12. Channel adapters (`FeishuChannelAdapter`, `TelegramChannelAdapter`, `WechatChannelAdapter`) wrap the answer for delivery — they do not rewrite content.

Composition order roughly:

```
model raw output
  → [SimplifiedOparEngine] stripHallucinatedXmlBlocks
  → [MetaGuardExecutor.normalize] empty / XML / refusal scrub
  → [MetaGuardExecutor.execute] meta-refusal detect + retry
  → [MetaGuardExecutor.fallbackAnswer] canned tail
  → [ChatResponsePolicyService.buildPartialAnswerFromAction] partial result fallback
  → [ChatResponsePolicyService.buildUserFacingFailureReply] failure-class canned reply
  → [LocalExecutionNarrator] local-skill model translation
  → [LocalSkillFallbackService.fallbackAnswer] deterministic text
```

Two engines opt out of MetaGuard entirely: `AutonomousLoopEngine.resolveFinalAnswer` returns `result.reflect()` directly without meta-refusal scrubbing; `AgentRuntimeEngine.summarize / buildFinalDegradedResult` likewise. Their outputs are not normalised by the same pipeline as the rest.

---

## 8. Persistence — 12 `persist()` callsites

[`ChatResultPersister.persist`](../../src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java) writes 4 `message_event` rows per call (`USER+ASSISTANT`, `ROUTING`, `PLAN`, `ACT`). It is invoked from:

| # | Caller | File:line | Path class |
|---|---|---|---|
| 1 | `ChatServiceImpl.executeInternal` (sync) | ChatServiceImpl.java:522 | sync |
| 2 | `ChatServiceImpl.streamActionRequired` | ChatServiceImpl.java:324-330 | SSE pre-confirmation |
| 3 | `ChatServiceImpl.streamImmediateAnswer` | ChatServiceImpl.java:347 | SSE shortcut |
| 4 | `ChatServiceImpl.streamAgentRuntimeAnswer` | ChatServiceImpl.java:364 | SSE / AgentRuntimeEngine |
| 5 | `ChatServiceImpl.streamBlockingFallback` | ChatServiceImpl.java:395 | SSE degraded |
| 6 | `ChatServiceImpl.streamReflectAnswer (doOnComplete)` | ChatServiceImpl.java:479 | SSE reflect success |
| 7 | `ChatServiceImpl.streamReflectAnswer (doOnError)` | ChatServiceImpl.java:497 | SSE reflect failure |
| 8 | `BasicStreamEngine.stream (doOnComplete)` | BasicStreamEngine.java:154 | SSE engine-internal |
| 9 | `BasicStreamEngine.handlePartialAnswer` | BasicStreamEngine.java:280 | SSE partial |
| 10 | `ModelLedStreamEngine.stream (doOnComplete)` | ModelLedStreamEngine.java:262 | SSE engine-internal |
| 11 | `ModelLedStreamEngine.handlePartialAnswer` | ModelLedStreamEngine.java:332 | SSE partial |
| 12 | `AutonomousLoopEngine.stream` | AutonomousLoopEngine.java:137 | SSE engine-internal |

Engines and `ChatServiceImpl` both decide independently when to persist. Asynchronous (`/api/chat/async`) does not have its own persist callsite — the consumer reuses the synchronous `chat()` method and persists through site #1.

---

## 9. Trace and audit — at least 5 sources

| Source | File | What it writes |
|---|---|---|
| [`SseEventBridge.sendTrace`](../../src/main/java/com/springclaw/service/chat/impl/SseEventBridge.java:247-258) | Each SSE trace event ⇒ `message_event` (TRACE) + `agent_run_step` |
| `SseEventBridge.recordRunTrace` | line 321-332 | Non-streaming AgentRun trace |
| `SseEventBridge.emitAndRecordRun` | line 295-299 | Capability/skill trace |
| `SseEventBridge.sendMeta` → `agentRunTraceService.recordRunMetadata` | line 63-73 | `agent_run` upsert |
| [`MessageEventToolAuditService`](../../src/main/java/com/springclaw/service/chat/impl/MessageEventToolAuditService.java) | Tool audit events |
| [`AgentActionProposalService.recordProposalTrace`](../../src/main/java/com/springclaw/service/agent/AgentActionProposalService.java:200-213) | Proposal confirm/cancel trace (this is the legacy `AgentActionProposal`, **not** the P0 `ToolInvocationProposal`) |

The `agent_run` row's `status` ends up at whatever the **last** `final` trace event sets it to. If a `final` trace is dropped (network partition, exception in the engine's `doOnComplete`), the row remains `RUNNING`. Three engines emit `final` from `doOnComplete/doOnError` independently; recovery semantics differ per engine.

---

## 10. Stream lifecycle — 4 SSE-completion sites across 4 classes

| # | Class | Site | Trigger |
|---|---|---|---|
| 1 | `ChatServiceImpl` | `stream` constructor (line 145) — `new SseEmitter(1_800_000L)` and `onCompletion`/`onTimeout` callbacks (line 148-162) | Connection-level cleanup |
| 2 | `ChatServiceImpl` | `executeStream` catch block (line 218-223) | Top-level error path |
| 3 | `BasicStreamEngine.stream` | `doOnComplete` / `doOnError` | Engine business completion / error |
| 4 | `ModelLedStreamEngine.stream` | `doOnComplete` / `doOnError` | Same |
| 5 | `AutonomousLoopEngine.stream` | `doOnComplete` / `fallbackHandler.handle` | Same, with degraded delegate |
| 6 | `SseEventBridge.completeEmitter` | line 266-269 (`sendEvent("done") + emitter.complete()`) | Helper called from all the above |

`SseEventBridge` is held directly by `ChatServiceImpl`, `BasicStreamEngine`, `ModelLedStreamEngine`, `AutonomousLoopEngine`. **Stream termination is decided in five places**, sometimes racing — for example, an `onTimeout` triggered by the framework calls `emitter.complete()` while the engine's `doOnComplete` has not yet run, leaving persistence/lock release straddling the close.

The sync (`/api/chat/send`) and RabbitMQ async paths have no SSE lifecycle. Async results are stored in Redis via [`AsyncChatResultStore`](../../src/main/java/com/springclaw/service/chat/async/AsyncChatResultStore.java) (24h TTL by default); polling reads through `GET /api/chat/async/{id}`.

---

## 11. Proposal confirm-resume — disconnected from the originating SSE

State machine: [`ToolInvocationProposalService.confirm`](../../src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java:86-119) does CAS `PENDING → APPROVED → EXECUTING` and emits `ToolProposalExecutionRequestedEvent`. Async listener: [`ToolProposalExecutionService.onExecutionRequested`](../../src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java:34-76) — `@TransactionalEventListener(AFTER_COMMIT) + @Async("proposalExecutor")` — re-opens the `ToolExecutionContext` and calls `ToolInvoker.invoke`, which goes through `ToolRuntimeAspect` again.

There is **no result projection back into the original chat stream**. By the time the user clicks Confirm:

- Original SSE emitter is already closed (`handlePendingApproval` calls `sseEventBridge.completeEmitter`).
- Proposal execution result lives only on the proposal row (status, `executionResult`, `executionError`, `gitCommitSha`, `gitChangedFiles`).
- The frontend must poll `GET /api/tool-proposals/{id}` to learn the outcome.

This is a complete cross-link gap: confirmed-tool-call execution does not appear in the originating Run's persistence (no `message_event` write tied back to the original `requestId`/`runId`) and does not appear on the SSE stream.

---

## 12. Configuration and environment variables

Real keys discovered in `application.yml` and config classes (no `OPENCLAW_*` form found in code; all are `SPRINGCLAW_*`):

| Key | Default | Env var | Consumer |
|---|---|---|---|
| `springclaw.chat.agent-mode` | `simplified` | `SPRINGCLAW_CHAT_AGENT_MODE` | `ChatContextFactory` |
| `springclaw.chat.routing.auto-upgrade-enabled` | `true` | `SPRINGCLAW_CHAT_ROUTING_AUTO_UPGRADE_ENABLED` | `ChatContextFactory` |
| `springclaw.chat.basic-streaming-enabled` | `true` | `SPRINGCLAW_CHAT_BASIC_STREAMING_ENABLED` | `BasicStreamEngine` |
| `springclaw.chat.model-led-streaming-enabled` | `false` | `SPRINGCLAW_CHAT_MODEL_LED_STREAMING_ENABLED` | `ModelLedStreamEngine` |
| `springclaw.chat.max-steps` | `3` (cap 6) | (none) | `OparLoopEngine` |
| `springclaw.chat.max-autonomous-steps` | `5` (cap 15) | `SPRINGCLAW_CHAT_MAX_AUTONOMOUS_STEPS` | `AutonomousLoopEngine` |
| `springclaw.chat.local-fallback-enabled` | `true` | (none) | `OparLoopEngine`, `AutonomousLoopEngine` |
| `springclaw.chat.local-fallback-first` | `false` | (none) | `OparLoopEngine` |
| `springclaw.chat.spring-ai-chat-memory-enabled` | `false` | `SPRINGCLAW_CHAT_SPRING_AI_CHAT_MEMORY_ENABLED` | `ConversationAdvisorSupport` |
| `springclaw.chat.meta-guard.enabled` | `true` | (none) | `MetaGuardExecutor` |
| `springclaw.chat.meta-guard.retry-times` | `1` | (none) | `MetaGuardExecutor` |
| `springclaw.ai.max-failover-attempts` | `2` | `SPRINGCLAW_AI_MAX_FAILOVER_ATTEMPTS` | `ModelCallExecutor` |
| `springclaw.ai.same-model-retry-attempts` | `0` | `SPRINGCLAW_AI_SAME_MODEL_RETRY_ATTEMPTS` | `ModelCallExecutor` |
| `springclaw.agent.decision.model-router-enabled` | `true` | `SPRINGCLAW_AGENT_DECISION_MODEL_ROUTER_ENABLED` | `AgentDecisionService` |
| `springclaw.embedding.enabled` | `false` | `SPRINGCLAW_EMBEDDING_ENABLED` | `EmbeddingConfig` |
| `springclaw.chat.memory-window-size` | `8` | `SPRINGCLAW_CHAT_MEMORY_WINDOW_SIZE` | `MessageEventChatMemory` |
| `springclaw.chat.model-transport-cooldown-seconds` | `30` | `SPRINGCLAW_MODEL_TRANSPORT_COOLDOWN_SECONDS` | `ModelTransportGuardService` |
| `springclaw.chat.provider-failure-cooldown-threshold` | `2` | `SPRINGCLAW_PROVIDER_FAILURE_COOLDOWN_THRESHOLD` | `ModelTransportGuardService` |
| `springclaw.rabbitmq.async-result-ttl-hours` | `24` | (none) | `AsyncChatResultStore` |

**No `max-iterations` key exists.** The runtime has three different ceilings: `OparLoopEngine` uses `max-steps`, `AutonomousLoopEngine` uses `max-autonomous-steps`, `AgentRuntimeEngine` uses a hardcoded `MAX_REFLECTION_ATTEMPTS = 3`.

---

## 13. `ChatExecutionResult` — OPAR data model leaked into the contract

[`ChatExecutionResult`](../../src/main/java/com/springclaw/service/chat/impl/ChatExecutionResult.java):

```java
public record ChatExecutionResult(
    String observe,        // OPAR residue
    String plan,           // OPAR residue
    String action,         // OPAR residue
    String reflect,        // holds the final answer text (despite the name)
    boolean modelEnabled
) {}
```

Every engine fills these four slots, but with **different semantics**:

| Engine | observe | plan | action | reflect |
|---|---|---|---|---|
| BasicStreamEngine | `assembled.observePrompt()` | static string `"BASIC_STREAM: 普通聊天最短路径。"` | static string `"未挂载工具，未进入多步规划。"` | the answer |
| AgentRuntimeEngine | `assembled.observePrompt()` | capability plan text | capability execution result | the answer |
| AutonomousLoopEngine | `assembled.observePrompt()` | `"自主循环执行 N 步"` | per-step trace | per-step summary |
| OparLoopEngine | `assembled.observePrompt()` | `buildPlanTrace(steps)` | `buildActionTrace(steps)` | the answer |
| ModelLedStreamEngine | `assembled.observePrompt()` | static string `"MODEL_LED: ..."` | static string `"使用 call() 代替 stream()。"` | the answer |
| SimplifiedOparEngine | `assembled.observePrompt()` | stage description | execution details | answer/reflect |

The same domain type is overloaded six different ways. Trace, persistence (`PLAN/ACT` `message_event` rows), and SSE all consume these strings as if they had stable meaning.

---

## 14. Responsibility ownership matrix (current)

| Responsibility | Owners (current) | Source-of-truth count |
|---|---|---|
| Routing | `ChatRoutingPolicyService.decide`, `AgentDecisionService.decide`, `EngineSelector.select` | **3** |
| Risk / confirmation prediction | `AgentDecisionService` (`riskLevel`, `requiresConfirmation`) | 1 (predicted) |
| Risk / confirmation enforcement | `ToolRuntimeAspect.resolveRiskLevel` + `ToolInvocationProposalService` | 1 (authoritative) |
| Context (semantic) materialisation | `ContextAssembler.buildSemanticContext` + `SemanticMemoryAdvisor.augment` | **2** |
| Context (event history) | `ContextAssembler.buildEventContext` + `MessageChatMemoryAdvisor` (when on) | **2** |
| Tool entry | Spring AI `requestSpec.tools` + local-shortcut `@Tool` calls + `CapabilityExecutorRegistry.execute` + `ToolProposalExecutionService.invoke` | **4** distinct entry shapes (all routed through `ToolRuntimeAspect`) |
| Completion | `TASK_COMPLETE`/`TASK_FAILED` text + `AutonomousExecutionTracker` + `ReflectionResult.sufficient` + `MAX_REFLECTION_ATTEMPTS` + `PlanResult.ready` + reactive `doOnComplete` + blocking return + `VerificationResult.sufficient` (trace-only) | **≥ 5** |
| Final answer composition | Engine direct return (6 engines) + `streamReflectAnswer` + `resolveFinalAnswer` + `MetaGuardExecutor.{execute,normalize,fallbackAnswer}` + `ChatResponsePolicyService.{stripHallucinatedXmlBlocks,buildPartialAnswerFromAction,buildUserFacingFailureReply}` + `LocalExecutionNarrator.narrate` + `LocalSkillFallbackService.fallbackAnswer` | **12** distinct sites |
| Persistence (`ChatResultPersister.persist`) | 12 callsites across `ChatServiceImpl` and 3 streaming engines | **12** |
| Trace / audit | `SseEventBridge.{sendTrace,recordRunTrace,emitAndRecordRun,sendMeta}`, `MessageEventToolAuditService`, `AgentActionProposalService.recordProposalTrace` | **6+** |
| Stream termination | `ChatServiceImpl.stream` (onCompletion/onTimeout/catch), `BasicStreamEngine`, `ModelLedStreamEngine`, `AutonomousLoopEngine`, `SseEventBridge.completeEmitter` helper | **4 owners + 1 helper** |
| Cross-transport projection (REST result, SSE event, async Redis bucket, RabbitMQ response, persistence row, trace row) | Each transport handled per-engine and per-site | **No single projection point** |

---

## 15. Honest scope notes

- The `OPENCLAW_*` environment-variable spelling referenced in some legacy docs (and in [`CLAUDE.md`](../../CLAUDE.md)) does not exist in current source. Only `SPRINGCLAW_*` keys are read.
- `AgentRuntimeEngine` (priority 2) was not in the original 5-engine narrative; it is the sixth engine and the only path that runs `CapabilityExecutorRegistry`.
- Two engines opt out of MetaGuard: `AutonomousLoopEngine` and `AgentRuntimeEngine`. Their outputs may contain meta-refusals or hallucinated XML the rest of the pipeline would have stripped.
- Default config (`springclaw.embedding.enabled=false`, `spring-ai-chat-memory-enabled=false`) hides two of the three context-source duplications today. Re-enabling either flag activates the duplication.
- This audit covers **runtime control flow only**. P0 invariants (`ToolRuntimeAspect`, `WorkspaceGitGuard`, `ToolInvocationProposal`) are explicitly out of scope per the collaboration plan; their existing behaviour is treated as a fixed boundary.
