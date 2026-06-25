# Unified Runtime Phase 3D Retrieval Quarantine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure canonical ContextSnapshot mode performs exactly one memory retrieval path before model calls and does not re-enable legacy Spring AI chat memory retrieval through advisors.

**Architecture:** Keep rollback mode intact. In canonical mode (`springclaw.context.snapshot.factory-enabled=true`), `ChatContextFactory` already skips `ContextAssembler` and `ContextSnapshotFactory` owns memory retrieval through `MemoryCoordinator`; Phase 3D tightens the model-call boundary so `ConversationAdvisorSupport` attaches no retrieval advisors even if `springclaw.chat.spring-ai-chat-memory-enabled=true`. Legacy rollback mode keeps the existing advisor behavior.

**Tech Stack:** Java 17, Spring Boot 3.5.7, JUnit 5, AssertJ, Mockito, Spring AI `ChatClient.AdvisorSpec`.

---

## Files

- Modify: `src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java`
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
- Create: `docs/superpowers/plans/2026-06-25-unified-runtime-phase-3d-retrieval-quarantine.md`

## Task 1: Quarantine advisor-side retrieval in canonical mode

**Files:**
- Modify: `src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java`

- [x] **Step 1: Write the failing test**

Add a test that constructs `ConversationAdvisorSupport` with:

```java
new ConversationAdvisorSupport(messageAdvisor, semanticAdvisor, true, true)
```

Then call `apply(...)` and assert:

```java
assertThat(application.advisors()).isEmpty();
```

Expected RED: the test fails because the current code still attaches `MessageChatMemoryAdvisor` in canonical mode when `springclaw.chat.spring-ai-chat-memory-enabled=true`.

- [x] **Step 2: Run RED**

Run:

```bash
mvn -Dtest=ConversationAdvisorSupportCanonicalModeTest test
```

Expected: failure showing one advisor was attached instead of none.

Actual RED:

```bash
mvn -Dtest=ConversationAdvisorSupportCanonicalModeTest test
```

Result: failed as expected because canonical mode still attached `MessageChatMemoryAdvisor` when Spring AI chat memory was enabled.

- [x] **Step 3: Implement minimal production change**

Change canonical branch in `ConversationAdvisorSupport.apply(...)` from:

```java
if (contextSnapshotFactoryEnabled) {
    advisors = springAiChatMemoryEnabled
            ? List.of(messageChatMemoryAdvisor)
            : List.of();
}
```

to:

```java
if (contextSnapshotFactoryEnabled) {
    advisors = List.of();
}
```

Do not change rollback behavior.

- [x] **Step 4: Run GREEN**

Run:

```bash
mvn -Dtest=ConversationAdvisorSupportCanonicalModeTest,ContextPropagationCharacterizationTest test
```

Expected: canonical advisor quarantine passes and existing rollback/default advisor characterization remains green.

Actual GREEN:

```bash
mvn -Dtest=ConversationAdvisorSupportCanonicalModeTest,ContextPropagationCharacterizationTest test
```

Result: 7 tests, 0 failures, 0 errors, 0 skipped.

## Task 2: Verify canonical HTTP/runtime gates still pass

**Files:**
- Test only.

- [x] **Step 1: Run canonical retrieval and HTTP smoke gates**

Run:

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a; mvn -Dtest=CanonicalRetrievalBoundaryTest,ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT test
```

Expected: all three canonical boundary/smoke tests pass.

Actual result: 4 tests, 0 failures, 0 errors, 0 skipped.

- [x] **Step 2: Run full suite**

Run:

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a; MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn test
```

Expected: full suite passes.

Actual result: 807 tests, 0 failures, 0 errors, 0 skipped.

## Task 3: Record ledger and commit

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
- Modify: `docs/superpowers/plans/2026-06-25-unified-runtime-phase-3d-retrieval-quarantine.md`

- [x] **Step 1: Update plan checkboxes and verification evidence**

Record RED/GREEN commands and results in this plan.

- [x] **Step 2: Update collaboration ledger**

Append a Phase 3D update stating:

```text
Canonical mode now attaches no Spring AI retrieval advisors. ContextSnapshot/MemoryCoordinator remains the single retrieval source before model calls. Rollback mode preserves legacy SemanticMemoryAdvisor and optional MessageChatMemoryAdvisor behavior.
```

- [x] **Step 3: Commit**

Run:

```bash
git add src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java \
  src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java \
  docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md \
  docs/superpowers/plans/2026-06-25-unified-runtime-phase-3d-retrieval-quarantine.md
git commit -m "fix: quarantine retrieval advisors in canonical mode"
```
