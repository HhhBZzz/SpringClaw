# SpringClaw Agent Action Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn SpringClaw from “chat model with tools nearby” into an observable Agent runtime where each user request goes through decision, capability selection, execution, verification, trace, and final answer.

**Architecture:** Keep Spring AI as the model/tool/advisor/vector engine. Keep SpringClaw as the governance and runtime layer: routing, permissions, risk confirmation, skill execution, task scheduling, trace persistence, audit, and UI visualization. The first milestone avoids a full rewrite and focuses on making Agent behavior visible and real for everyday questions.

**Tech Stack:** Spring Boot 3.5, Spring AI 1.1.2, MyBatis-Plus, MySQL, Redis/Redis VectorStore, Vue 3, Vite, GSAP.

---

## Current Diagnosis

SpringClaw is not useless, but the current experience makes it look useless because most user requests can finish through a plain model answer before the Agent layer becomes visible. The project has tools, skills, memory, trace, risk policy, and scheduled tasks, but the default path still often behaves like: user message -> model call -> final answer.

Main causes:

1. Agent decision exists, but it is not the single mandatory entry gate for every chat request.
2. Tool selection is too conservative in some paths and too broad in others.
3. Some local fallback paths answer before the model/tool loop has a chance to run.
4. Spring AI native tool calling is partially disabled for DeepSeek V4 compatibility.
5. Run trace is not treated as first-class product output in the chat page.
6. The UI does not make “what the Agent is doing” obvious enough.
7. Skills exist, but they are not naturally selected for common user goals.
8. Memory exists, but the user cannot clearly see when memory was recalled and used.
9. Verification is weak: the Agent often reports instead of checking tool results against the goal.

## Target Behavior

A normal user question should produce one of these visible paths:

- General answer: classify as `general`, no tools, stream answer quickly.
- Project/code question: classify as `workspace_analysis`, inspect files, show file reads/searches in trace, then answer with evidence.
- Local file question: classify as `local_files`, check authorization roots, list/read/search files, show exact paths used.
- Web question: classify as `web_research`, fetch/search, summarize sources.
- Skill question: classify as `skill_task`, select one skill, run it, show input/output.
- Scheduled task request: classify as `scheduled_task`, create proposal, wait for confirmation.
- Dangerous/write request: create action proposal or reject.

## Files To Touch

- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java`
- Modify: `src/main/java/com/springclaw/service/agent/AgentDecisionService.java`
- Modify: `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java`
- Modify: `src/main/java/com/springclaw/tool/runtime/ToolOrchestrator.java`
- Modify: `src/main/java/com/springclaw/service/chat/LocalSkillFallbackService.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/LocalExecutionNarrator.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/SemanticMemoryAdvisor.java`
- Modify: `src/main/resources/application.yml`
- Modify: `frontend/src/views/AgentView.vue`
- Modify: `frontend/src/components/AgentMessage.vue`
- Modify: `frontend/src/styles/agent-console.css` or the current Agent view stylesheet
- Test: `src/test/java/com/springclaw/service/agent/AgentDecisionServiceTest.java`
- Test: `src/test/java/com/springclaw/tool/runtime/ToolOrchestratorTest.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java`
- Test: `src/test/java/com/springclaw/service/chat/LocalSkillFallbackServiceTest.java`

---

## Task 1: Make Agent Decision Mandatory

**Purpose:** Every chat request must produce a decision event before model/tool execution.

- [ ] Add tests in `AgentDecisionServiceTest` for these inputs:
  - `你好` -> `intent=general`, `executionPath=basic_model`
  - `分析当前项目结构` -> `intent=workspace_analysis`, `executionPath=agent_tools`, selected capabilities include workspace tools
  - `读取桌面文件` -> `intent=local_files`, `executionPath=agent_tools`, selected capabilities include local file tools
  - `每天 9 点抓网页` -> `intent=scheduled_task`, `executionPath=task_draft`, `requiresConfirmation=true`
  - `删除这个目录` -> `riskLevel=dangerous`, `requiresConfirmation=true` or reject

- [ ] Change `ChatContextFactory` so `AgentDecisionService` runs before legacy routing.

- [ ] Emit `event: decision` over SSE with:

```json
{
  "requestId": "req_xxx",
  "intent": "workspace_analysis",
  "executionPath": "agent_tools",
  "riskLevel": "read",
  "requiresConfirmation": false,
  "reason": "用户要求分析当前项目，需要读取本地工作区文件"
}
```

- [ ] Persist the decision as a trace event through `AgentRunTraceService`.

- [ ] Run:

```bash
mvn -q -Dtest=AgentDecisionServiceTest,ChatServiceImplModeTest test
```

Expected: decision is always present before the first model call.

---

## Task 2: Capability-Gated Tool Selection

**Purpose:** Do not expose all tools to the model. Expose only the tools matching the decision.

- [ ] Extend `ToolOrchestrator` with a method shaped like:

```java
public List<Object> selectAgentToolsForDecision(AgentDecision decision, Set<String> allowedToolPacks)
```

- [ ] Mapping:
  - `general`: no tools
  - `workspace_analysis`: `workspace-search`, `workspace-review`, `file`, `skill-library`
  - `local_files`: `local-files`, `file`
  - `web_research`: `web-search`, `weather`, `news`, `exchange`
  - `skill_task`: `script-skill`, `skill-library`
  - `scheduled_task`: no direct tools, create proposal
  - `model_control`: system/model state tools only

- [ ] Add tests in `ToolOrchestratorTest` proving each intent exposes only expected tools.

- [ ] Run:

```bash
mvn -q -Dtest=ToolOrchestratorTest test
```

Expected: project-analysis questions no longer expose weather/news, and general chat exposes no tools.

---

## Task 3: Reduce Local Fallback Interference

**Purpose:** Stop local fallback from making the product feel like a template bot.

- [ ] In `application.yml`, change defaults:

```yaml
springclaw:
  chat:
    local-fallback-first: false
    model-led-streaming-enabled: true
```

- [ ] Keep local fallback only for:
  - model unavailable
  - model control questions such as “当前模型是什么”
  - deterministic system facts such as current time
  - explicit tool-only commands

- [ ] Update `LocalExecutionNarrator` so tool results are model-summarized when model is available.

- [ ] Add tests that workspace/code questions do not return deterministic fallback when a model client is available.

- [ ] Run:

```bash
mvn -q -Dtest=LocalSkillFallbackServiceTest,LocalExecutionNarratorTest test
```

Expected: normal project questions go through Agent/tool/model path instead of local shortcut answer.

---

## Task 4: Make Tool Execution Visible

**Purpose:** The user should see the Agent doing work, not wait for a black-box final answer.

- [ ] Add trace steps for:
  - `decision.started`
  - `decision.completed`
  - `tools.selected`
  - `tool.call.started`
  - `tool.call.completed`
  - `memory.recall.started`
  - `memory.recall.completed`
  - `model.call.started`
  - `model.token`
  - `model.call.completed`
  - `final.completed`

- [ ] SSE should emit:

```text
event: trace
data: {"step":"tool.call.started","name":"WorkspaceSearchToolPack.search","status":"running"}
```

- [ ] Persist trace into `MessageEvent` or current trace table by `requestId`.

- [ ] Add `GET /api/chat/runs/{requestId}/trace` regression test.

- [ ] Run:

```bash
mvn -q -Dtest=ChatControllerAuthTest,ChatServiceImplModeTest test
```

Expected: frontend can render the full execution timeline without refresh.

---

## Task 5: Spring AI First Memory Path

**Purpose:** Stop duplicating chat memory assembly.

- [ ] Enable Spring AI `MessageChatMemoryAdvisor` in dev profile first:

```yaml
springclaw:
  chat:
    spring-ai-chat-memory-enabled: true
```

- [ ] Keep `ContextAssembler` only for OPAR observation and UI trace, not as the primary chat memory mechanism.

- [ ] Ensure `MessageEventChatMemory` reads recent user/assistant messages and does not write duplicates.

- [ ] Add tests proving recent conversation is loaded through `ChatMemory` for a session.

- [ ] Run:

```bash
mvn -q -Dtest=ChatMemoryConfigTest,ContextAssemblerTest test
```

Expected: recent conversation memory is Spring AI advisor-led, while audit remains in MySQL events.

---

## Task 6: Skill Selection Becomes Goal-Based

**Purpose:** Skills should feel like capabilities the Agent chooses, not hidden scripts.

- [ ] Extend `AgentDecisionService` to detect likely skill goals:
  - `生成 PDF` -> `pdf_generator`
  - `生成 PPT` -> `ppt_generator`
  - `提取视频帧` -> `video_frames`
  - `读取办公文件` -> `office_file_tools`
  - `爬网页` -> `web_crawler`
  - `项目审查` -> `repo_inspector` or `workspace-review`

- [ ] Use `SkillRegistryService` metadata instead of hardcoded Java if possible.

- [ ] For skill execution, emit trace:

```json
{
  "step": "skill.selected",
  "skillId": "pdf_generator",
  "reason": "用户要求把文本生成 PDF"
}
```

- [ ] Add tests for skill matching by metadata keywords.

- [ ] Run:

```bash
mvn -q -Dtest=SkillRegistryServiceTest,SkillRuntimeServiceTest test
```

Expected: common skill requests automatically select real skills.

---

## Task 7: Verification Step Before Final Answer

**Purpose:** A mature Agent should verify whether the action result satisfies the user goal.

- [ ] Add a final verification trace step:

```json
{
  "step": "verification.completed",
  "status": "success",
  "summary": "已读取 8 个项目文件，回答包含入口、服务层、工具层和 skill 层"
}
```

- [ ] For file/project analysis, verify that at least one relevant file was read before giving a confident code answer.

- [ ] For skill execution, verify exit code or JSON result contains success/error.

- [ ] For web research, verify at least one source was fetched.

- [ ] If verification fails, answer should say what failed and what can retry, not fake成功.

- [ ] Run:

```bash
mvn -q -Dtest=ChatServiceImplModeTest,SkillRuntimeServiceTest test
```

Expected: final answer includes evidence when tools were used, and failed tool paths are explicit.

---

## Task 8: Frontend Agent Command Center

**Purpose:** Make Agent behavior visible in the product.

- [ ] In `frontend/src/views/AgentView.vue`, show decision chip above the assistant message:
  - Intent
  - Execution path
  - Risk level
  - Selected capability count

- [ ] In the right Inspector, make Run Trace the default active tab.

- [ ] Render trace timeline from SSE immediately.

- [ ] Render tool calls with:
  - name
  - duration
  - risk
  - status
  - input summary
  - output summary

- [ ] Render action proposals with confirm/cancel buttons.

- [ ] Run:

```bash
cd frontend
npm run build
```

Expected: no build error, and a project-analysis question visibly shows decision + tool calls + trace.

---

## Task 9: Acceptance Smoke Tests

Run manual tests after backend and frontend start.

- [ ] Start backend:

```bash
mvn spring-boot:run
```

- [ ] Start frontend:

```bash
cd frontend
npm run dev
```

- [ ] Test prompts:
  - `你好，你是谁`
  - `帮我分析当前项目结构`
  - `我的 skills 现在有哪些`
  - `读取桌面文件列表`
  - `每天 9 点帮我抓这个网页`
  - `把这段文字生成 PDF`

Expected:

- General chat has no tool trace.
- Project question has workspace tools trace.
- Skills question uses skill registry/tool.
- Local file question checks authorization.
- Scheduled task creates confirmation proposal.
- PDF request selects `pdf_generator` or asks for missing input.

---

## Definition Of Done

- Every chat run has a `decision` event.
- Every non-general run has visible trace.
- Tools are selected by capability gate, not globally exposed.
- Local fallback no longer steals normal Agent questions.
- Spring AI handles primary chat memory and stream path.
- Skill requests can trigger real skill execution.
- Frontend shows decision, trace, tool calls, memory, logs.
- `mvn -q test` and `npm run build` pass.

