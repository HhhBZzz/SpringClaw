# Skill System Final Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the SpringClaw skill system cleanup so skills are stored, discovered, executed, tested, and documented through one coherent model.

**Architecture:** `skills/<skillId>/SKILL.md` remains the single source for skill definitions. `SkillCatalogService` owns discovery and metadata, while `SkillRuntimeService` owns executable dispatch. Script, builtin, and prompt skills stay as executor types, but callers outside adapter internals must not branch on those types themselves.

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, Mockito, AssertJ, Python script skills under `skills/`.

---

## File Structure

The final shape should be:

```text
skills/
├── <skillId>/
│   ├── SKILL.md
│   └── scripts/run.py
├── _shared/
└── _templates/

src/main/java/com/springclaw/service/skill/
├── bundle/
│   ├── SkillCatalogService.java
│   ├── SkillScaffoldService.java
│   ├── SkillBundleDefinition.java
│   ├── SkillBundleSupport.java
│   ├── SkillUsageRecord.java
│   └── SkillUsageService.java
├── runtime/
│   └── SkillRuntimeService.java
├── script/
│   ├── ScriptSkillCatalogService.java
│   ├── ScriptSkillDefinition.java
│   └── ScriptSkillExecutorService.java
├── markdown/
│   └── MarkdownSkillCatalogService.java
└── impl/
    ├── SkillRegistryService.java
    └── SkillServiceImpl.java
```

Responsibility boundaries:

- `SkillCatalogService`: scans `skills/` and `external-roots`, parses `SKILL.md`, returns bundle definitions.
- `SkillRegistryService`: converts catalog definitions into runtime-visible `SkillDefinition`.
- `SkillRuntimeService`: executes a resolved skill or resolves by skillId plus allowed tool packs.
- `ScriptSkillExecutorService`: low-level Python adapter only.
- `BuiltinSkillExecutionService`: low-level builtin adapter only.
- `ScriptSkillToolPack`: public compatibility tool surface; execution should go through `SkillRuntimeService`.
- `SkillLibraryToolPack`: Hermes-style list/view/status surface; no execution logic.

---

### Task 1: Rename Historical Package Services

**Files:**
- Rename: `src/main/java/com/springclaw/service/skill/bundle/SkillPackageCatalogService.java` -> `src/main/java/com/springclaw/service/skill/bundle/SkillCatalogService.java`
- Rename: `src/main/java/com/springclaw/service/skill/bundle/SkillPackageScaffoldService.java` -> `src/main/java/com/springclaw/service/skill/bundle/SkillScaffoldService.java`
- Rename: `src/test/java/com/springclaw/service/skill/bundle/SkillPackageCatalogServiceTest.java` -> `src/test/java/com/springclaw/service/skill/bundle/SkillCatalogServiceTest.java`
- Rename: `src/test/java/com/springclaw/service/skill/bundle/SkillPackageScaffoldServiceTest.java` -> `src/test/java/com/springclaw/service/skill/bundle/SkillScaffoldServiceTest.java`
- Modify: all imports and references under `src/main/java`, `src/test/java`, `docs/SCRIPT_SKILL_GUIDE.md`, `CLAUDE.md`, `CHANGELOG.md`

- [ ] **Step 1: Write the failing rename expectation**

Add this test to the renamed `SkillCatalogServiceTest` after the class rename:

```java
@Test
void shouldExposeNeutralSkillCatalogName() {
    SkillCatalogService service = new SkillCatalogService(true, tempDir.toString());

    Assertions.assertTrue(service.enabled());
    Assertions.assertTrue(service.rootPath().endsWith(tempDir));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=SkillCatalogServiceTest#shouldExposeNeutralSkillCatalogName test
```

Expected: FAIL at compile time because `SkillCatalogService` does not exist yet.

- [ ] **Step 3: Rename production classes**

Use non-interactive rename commands:

```bash
mv src/main/java/com/springclaw/service/skill/bundle/SkillPackageCatalogService.java src/main/java/com/springclaw/service/skill/bundle/SkillCatalogService.java
mv src/main/java/com/springclaw/service/skill/bundle/SkillPackageScaffoldService.java src/main/java/com/springclaw/service/skill/bundle/SkillScaffoldService.java
```

Update class declarations:

```java
@Service
public class SkillCatalogService {
    public SkillCatalogService(boolean enabled, String root) {
        this(enabled, root, "");
    }
}
```

```java
@Service
public class SkillScaffoldService {
    public SkillScaffoldService(@Value("${springclaw.skills.scaffold-enabled:true}") boolean enabled,
                                SkillCatalogService skillCatalogService) {
        this.enabled = enabled;
        this.skillCatalogService = skillCatalogService;
    }
}
```

- [ ] **Step 4: Rename tests**

Run:

```bash
mv src/test/java/com/springclaw/service/skill/bundle/SkillPackageCatalogServiceTest.java src/test/java/com/springclaw/service/skill/bundle/SkillCatalogServiceTest.java
mv src/test/java/com/springclaw/service/skill/bundle/SkillPackageScaffoldServiceTest.java src/test/java/com/springclaw/service/skill/bundle/SkillScaffoldServiceTest.java
```

Update class declarations:

```java
class SkillCatalogServiceTest {
}
```

```java
class SkillScaffoldServiceTest {
}
```

- [ ] **Step 5: Replace references**

Run:

```bash
rg -l "SkillPackageCatalogService|SkillPackageScaffoldService|packageCatalogService|skillPackageScaffoldService" src/main/java src/test/java docs/SCRIPT_SKILL_GUIDE.md CLAUDE.md CHANGELOG.md
```

Replace names consistently:

```text
SkillPackageCatalogService -> SkillCatalogService
SkillPackageScaffoldService -> SkillScaffoldService
packageCatalogService -> skillCatalogService
skillPackageScaffoldService -> skillScaffoldService
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
mvn -q -Dtest=SkillCatalogServiceTest,SkillScaffoldServiceTest,SkillServiceImplTest,SkillRegistryServiceTest,MarkdownSkillCatalogServiceTest,BuiltinSkillCatalogServiceTest,SkillLibraryToolPackTest test
```

Expected: PASS.

- [ ] **Step 7: Commit checkpoint**

Run:

```bash
git add src/main/java/com/springclaw/service/skill src/test/java/com/springclaw/service/skill src/test/java/com/springclaw/tool/pack docs/SCRIPT_SKILL_GUIDE.md CLAUDE.md CHANGELOG.md
git commit -m "refactor: rename skill catalog services"
```

If the current branch intentionally contains older uncommitted work, skip commit and record the reason in the final handoff.

---

### Task 2: Make SkillRuntimeService Resolve Skill IDs

**Files:**
- Modify: `src/main/java/com/springclaw/service/skill/runtime/SkillRuntimeService.java`
- Modify: `src/test/java/com/springclaw/service/skill/runtime/SkillRuntimeServiceTest.java`
- Modify: `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
- Modify: `src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java`

- [ ] **Step 1: Write failing runtime resolution test**

Add to `SkillRuntimeServiceTest`:

```java
@Test
void shouldResolveAllowedSkillByIdBeforeExecution() {
    SkillRegistryService registryService = mock(SkillRegistryService.class);
    SkillRuntimeService runtimeService = new SkillRuntimeService(scriptExecutor, builtinExecutor, registryService);
    SkillDefinition definition = definition("repo_inspector", "python");
    when(registryService.listAgentVisibleDefinitions(Set.of("script"))).thenReturn(List.of(definition));
    when(scriptExecutor.runScriptSkillByGoal("repo_inspector", "分析项目")).thenReturn("分析完成");

    String result = runtimeService.executeBySkillId("repo_inspector", "分析项目", Set.of("script"));

    assertThat(result).isEqualTo("分析完成");
    verify(registryService).listAgentVisibleDefinitions(Set.of("script"));
}
```

Add imports:

```java
import com.springclaw.service.skill.impl.SkillRegistryService;
import java.util.Set;
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=SkillRuntimeServiceTest#shouldResolveAllowedSkillByIdBeforeExecution test
```

Expected: FAIL because constructor overload and `executeBySkillId` are missing.

- [ ] **Step 3: Implement runtime resolution**

Update `SkillRuntimeService`:

```java
private final SkillRegistryService skillRegistryService;

public SkillRuntimeService(ScriptSkillExecutorService scriptSkillExecutorService,
                           BuiltinSkillExecutionService builtinSkillExecutionService,
                           SkillRegistryService skillRegistryService) {
    this.scriptSkillExecutorService = scriptSkillExecutorService;
    this.builtinSkillExecutionService = builtinSkillExecutionService;
    this.skillRegistryService = skillRegistryService;
}

public SkillRuntimeService(ScriptSkillExecutorService scriptSkillExecutorService,
                           BuiltinSkillExecutionService builtinSkillExecutionService) {
    this(scriptSkillExecutorService, builtinSkillExecutionService, null);
}

public String executeBySkillId(String skillId, String inputPayload, Set<String> allowedToolPacks) {
    if (!StringUtils.hasText(skillId)) {
        throw new BusinessException(40084, "skillId 不能为空");
    }
    if (skillRegistryService == null) {
        throw new BusinessException(50097, "skill registry 未配置");
    }
    SkillDefinition definition = skillRegistryService.listAgentVisibleDefinitions(allowedToolPacks).stream()
            .filter(candidate -> skillId.trim().equalsIgnoreCase(candidate.skillId()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(40362, "当前账号无权执行 skill: " + skillId));
    return execute(definition, inputPayload);
}
```

- [ ] **Step 4: Move TaskExecutionService resolution into runtime**

Change `TaskExecutionService` so `executeSkillTask` becomes:

```java
private TaskExecutionOutcome executeSkillTask(ScheduledTask task, String requestId) {
    String skillId = safe(task.getTargetRef());
    Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(task.getChannel(), task.getOwnerUserId());
    String answer = skillRuntimeService.executeBySkillId(skillId, task.getInputPayload(), allowedToolPacks);
    return new TaskExecutionOutcome(buildSummary(task, answer), answer, requestId, resolveTaskSessionKey(task));
}
```

Remove the `SkillRegistryService` field and constructor parameter from `TaskExecutionService`.

- [ ] **Step 5: Update TaskExecutionServiceTest**

Remove `SkillRegistryService` setup from task skill tests and verify runtime receives the id:

```java
when(skillService.resolveAllowedToolPacks("api", "tester")).thenReturn(Set.of("script"));
when(skillRuntimeService.executeBySkillId("repo_inspector", "分析项目结构", Set.of("script")))
        .thenReturn("结构分析完成");

verify(skillRuntimeService).executeBySkillId("repo_inspector", "分析项目结构", Set.of("script"));
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
mvn -q -Dtest=SkillRuntimeServiceTest,TaskExecutionServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit checkpoint**

Run:

```bash
git add src/main/java/com/springclaw/service/skill/runtime/SkillRuntimeService.java src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java src/test/java/com/springclaw/service/skill/runtime/SkillRuntimeServiceTest.java src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java
git commit -m "refactor: resolve skill execution through runtime"
```

If the working tree contains intentionally uncommitted prior changes, skip commit and record the reason.

---

### Task 3: Route ScriptSkillToolPack Execution Through Runtime

**Files:**
- Modify: `src/main/java/com/springclaw/tool/pack/ScriptSkillToolPack.java`
- Modify: `src/test/java/com/springclaw/tool/pack/ScriptSkillToolPackTest.java`
- Modify: `src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java`
- Modify: `src/test/java/com/springclaw/service/auth/ToolPermissionServiceImplTest.java`

- [ ] **Step 1: Write failing toolpack delegation test**

Add to `ScriptSkillToolPackTest`:

```java
@Test
void shouldRunScriptSkillThroughRuntime() {
    ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
    SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
    ScriptSkillToolPack toolPack = new ScriptSkillToolPack(true, catalogService, runtimeService, null);
    when(runtimeService.executeBySkillId("echo", "hello", Set.of("script"))).thenReturn("runtime result");

    String result = toolPack.runScriptSkillByGoal("echo", "hello");

    Assertions.assertEquals("runtime result", result);
    verify(runtimeService).executeBySkillId("echo", "hello", Set.of("script"));
}
```

Add imports:

```java
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import java.util.Set;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=ScriptSkillToolPackTest#shouldRunScriptSkillThroughRuntime test
```

Expected: FAIL because the constructor does not accept `SkillRuntimeService`.

- [ ] **Step 3: Change ScriptSkillToolPack dependencies**

Replace executor field:

```java
private final SkillRuntimeService skillRuntimeService;
```

Autowired constructor:

```java
public ScriptSkillToolPack(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                           ScriptSkillCatalogService scriptSkillCatalogService,
                           SkillRuntimeService skillRuntimeService,
                           SkillScaffoldService skillScaffoldService) {
    this.enabled = enabled;
    this.scriptSkillCatalogService = scriptSkillCatalogService;
    this.skillRuntimeService = skillRuntimeService;
    this.skillScaffoldService = skillScaffoldService;
}
```

Execution methods:

```java
public String runScriptSkill(String skillName, String argsJson) {
    if (!enabled) {
        return "脚本技能未开启（springclaw.skills.enabled=false）";
    }
    return skillRuntimeService.executeBySkillId(skillName, argsJson, Set.of("script"));
}

public String runScriptSkillByGoal(String skillName, String goal) {
    if (!enabled) {
        return "脚本技能未开启（springclaw.skills.enabled=false）";
    }
    return skillRuntimeService.executeBySkillId(skillName, goal, Set.of("script"));
}
```

For `Map<String, String>` arguments:

```java
public String runScriptSkill(String skillName, Map<String, String> args) {
    if (!enabled) {
        return "脚本技能未开启（springclaw.skills.enabled=false）";
    }
    return runScriptSkill(skillName, objectMapper.writeValueAsString(args == null ? Map.of() : args));
}
```

Keep the test-only convenience constructor by building a local `SkillRuntimeService` with `SkillRegistryService` and existing script/builtin adapters.

- [ ] **Step 4: Preserve public permission names**

Do not rename `ScriptSkillToolPack` yet. Existing permission checks rely on strings such as:

```text
ScriptSkillToolPack.*
ScriptSkillToolPack.runScriptSkill
```

Only change internals.

- [ ] **Step 5: Run focused tests**

Run:

```bash
mvn -q -Dtest=ScriptSkillToolPackTest,SkillRuntimeServiceTest,ToolPermissionServiceImplTest test
```

Expected: PASS.

- [ ] **Step 6: Commit checkpoint**

Run:

```bash
git add src/main/java/com/springclaw/tool/pack/ScriptSkillToolPack.java src/test/java/com/springclaw/tool/pack/ScriptSkillToolPackTest.java src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java src/test/java/com/springclaw/service/auth/ToolPermissionServiceImplTest.java
git commit -m "refactor: route script skill tool execution through runtime"
```

Skip commit if preserving the current dirty working tree is required.

---

### Task 4: Add Runtime Boundary Regression Test

**Files:**
- Create: `src/test/java/com/springclaw/service/skill/runtime/SkillRuntimeBoundaryTest.java`

- [ ] **Step 1: Write boundary test**

Create this test:

```java
package com.springclaw.service.skill.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRuntimeBoundaryTest {

    @Test
    void shouldPreventApplicationCallersFromBypassingSkillRuntime() throws Exception {
        Path sourceRoot = Path.of("src/main/java").toAbsolutePath().normalize();
        List<String> offenders;
        try (var stream = Files.walk(sourceRoot)) {
            offenders = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith("ScriptSkillExecutorService.java"))
                    .filter(path -> !path.endsWith("SkillRuntimeService.java"))
                    .filter(path -> !path.endsWith("BuiltinSkillExecutionService.java"))
                    .filter(path -> !path.endsWith("LocalSkillQuerySupport.java"))
                    .filter(path -> containsDirectScriptExecution(path))
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }

        assertThat(offenders).isEmpty();
    }

    private boolean containsDirectScriptExecution(Path path) {
        try {
            String text = Files.readString(path);
            return text.contains(".runScriptSkill(")
                    || text.contains(".runScriptSkillByGoal(");
        } catch (Exception ex) {
            return false;
        }
    }
}
```

Allowed exceptions:

- `ScriptSkillExecutorService`: low-level adapter.
- `SkillRuntimeService`: unified execution boundary.
- `BuiltinSkillExecutionService`: builtin adapter may use script fallback internally.
- `LocalSkillQuerySupport`: temporary compatibility helper until Task 5.

- [ ] **Step 2: Run boundary test**

Run:

```bash
mvn -q -Dtest=SkillRuntimeBoundaryTest test
```

Expected: PASS after Task 3.

- [ ] **Step 3: Commit checkpoint**

Run:

```bash
git add src/test/java/com/springclaw/service/skill/runtime/SkillRuntimeBoundaryTest.java
git commit -m "test: guard skill runtime boundary"
```

Skip commit if preserving the current dirty working tree is required.

---

### Task 5: Reduce Local Fallback Direct Script Calls

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/LocalSkillFallbackService.java`
- Modify: `src/main/java/com/springclaw/service/chat/LocalSkillQuerySupport.java`
- Modify: `src/test/java/com/springclaw/service/chat/LocalSkillFallbackServiceTest.java`
- Modify: `src/test/java/com/springclaw/service/skill/runtime/SkillRuntimeBoundaryTest.java`

- [ ] **Step 1: Write failing fallback runtime test**

Add to `LocalSkillFallbackServiceTest` near the auto-route test:

```java
@Test
void shouldAutoRouteScriptSkillThroughRuntime() throws Exception {
    writeScriptSkill("system_status", "系统状态", "runtime", List.of("系统状态"), "print('runtime ok')");
    SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
    when(runtimeService.executeBySkillId("system_status", "查看系统状态", Set.of("script"))).thenReturn("runtime ok");

    LocalSkillFallbackService service = createServiceWithRuntime(runtimeService);

    Optional<LocalSkillFallbackService.LocalSkillResult> result = service.tryHandleStructured("查看系统状态");

    assertThat(result).isPresent();
    assertThat(result.get().fallbackAnswer()).contains("runtime ok");
    verify(runtimeService).executeBySkillId("system_status", "查看系统状态", Set.of("script"));
}
```

Add helper:

```java
private LocalSkillFallbackService createServiceWithRuntime(SkillRuntimeService runtimeService) {
    ScriptSkillCatalogService catalogService = new ScriptSkillCatalogService(true, tempDir.toString(), "*", new ObjectMapper());
    return new LocalSkillFallbackService(
            true,
            new SystemToolPack(),
            new WorkspaceSearchToolPack(tempDir.toString(), 8, 20, 10, 512),
            mock(WebSearchToolPack.class),
            mock(WeatherToolPack.class),
            mock(ExchangeRateToolPack.class),
            mock(NewsToolPack.class),
            new ScriptSkillToolPack(true, catalogService, runtimeService, null),
            mock(BuiltinSkillExecutionService.class),
            runtimeService,
            catalogService,
            mock(AiProviderService.class)
    );
}
```

If constructor signatures differ after Task 3, adjust helper to match the actual constructors from production code.

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=LocalSkillFallbackServiceTest#shouldAutoRouteScriptSkillThroughRuntime test
```

Expected: FAIL because `LocalSkillFallbackService` still accepts `ScriptSkillExecutorService` directly.

- [ ] **Step 3: Refactor LocalSkillQuerySupport**

Replace field:

```java
private final SkillRuntimeService skillRuntimeService;
```

Constructor:

```java
LocalSkillQuerySupport(SkillRuntimeService skillRuntimeService,
                       ScriptSkillCatalogService scriptSkillCatalogService) {
    this.skillRuntimeService = skillRuntimeService;
    this.scriptSkillCatalogService = scriptSkillCatalogService;
}
```

Replace `tryScriptSkill`:

```java
private String tryScriptSkill(String skillName, String goal) {
    try {
        return skillRuntimeService.executeBySkillId(skillName, goal, Set.of("script"));
    } catch (Exception ignore) {
        return "";
    }
}
```

- [ ] **Step 4: Refactor LocalSkillFallbackService constructor**

Replace `ScriptSkillExecutorService` constructor parameter with `SkillRuntimeService`.

Change:

```java
this.querySupport = new LocalSkillQuerySupport(skillRuntimeService, scriptSkillCatalogService);
```

Keep a test-only compatibility constructor only if existing tests become too noisy; production constructor should use `SkillRuntimeService`.

- [ ] **Step 5: Tighten runtime boundary test**

Remove `LocalSkillQuerySupport.java` from the allow-list in `SkillRuntimeBoundaryTest`:

```java
.filter(path -> !path.endsWith("LocalSkillQuerySupport.java"))
```

should be deleted.

- [ ] **Step 6: Run focused tests**

Run:

```bash
mvn -q -Dtest=LocalSkillFallbackServiceTest,SkillRuntimeBoundaryTest,SkillRuntimeServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit checkpoint**

Run:

```bash
git add src/main/java/com/springclaw/service/chat/LocalSkillFallbackService.java src/main/java/com/springclaw/service/chat/LocalSkillQuerySupport.java src/test/java/com/springclaw/service/chat/LocalSkillFallbackServiceTest.java src/test/java/com/springclaw/service/skill/runtime/SkillRuntimeBoundaryTest.java
git commit -m "refactor: route local fallback script execution through runtime"
```

Skip commit if preserving current dirty work is required.

---

### Task 6: Documentation and Naming Cleanup

**Files:**
- Modify: `docs/SCRIPT_SKILL_GUIDE.md`
- Modify: `CLAUDE.md`
- Modify: `CHANGELOG.md`
- Modify: `src/main/java/com/springclaw/service/skill/impl/SkillRegistryService.java`
- Modify: comments in `src/main/java/com/springclaw/service/skill/**`

- [ ] **Step 1: Search stale naming**

Run:

```bash
rg -n "SkillPackage|package skill|skills/packages|Package-based|packageCatalogService|skillPackage" src/main/java src/test/java docs CLAUDE.md CHANGELOG.md
```

Expected before cleanup: stale references may exist in changelog history and class comments.

- [ ] **Step 2: Update live documentation**

In `docs/SCRIPT_SKILL_GUIDE.md`, make the execution section state:

```text
对外执行入口：SkillRuntimeService
定义扫描入口：SkillCatalogService
查看入口：SkillLibraryToolPack
脚本适配器：ScriptSkillExecutorService，仅供 runtime 和 builtin adapter 内部使用
```

In `CLAUDE.md`, make the skills subsystem line state:

```text
Skills are directory-based under skills/. SkillCatalogService scans definitions; SkillRuntimeService executes python/script and builtin skills; prompt skills are non-executable instructions.
```

- [ ] **Step 3: Update changelog**

Add:

```markdown
13. 完成 skill 命名和执行边界收口
   - `SkillPackageCatalogService` 更名为 `SkillCatalogService`
   - `SkillPackageScaffoldService` 更名为 `SkillScaffoldService`
   - `ScriptSkillToolPack` 和本地兜底脚本执行都改走 `SkillRuntimeService`
   - 新增边界测试，防止业务代码绕过 runtime 直接调用脚本执行器
```

- [ ] **Step 4: Run stale naming search**

Run:

```bash
rg -n "SkillPackage|package skill|skills/packages|Package-based|packageCatalogService|skillPackage" src/main/java src/test/java docs/SCRIPT_SKILL_GUIDE.md CLAUDE.md
```

Expected: no output.

- [ ] **Step 5: Commit checkpoint**

Run:

```bash
git add docs/SCRIPT_SKILL_GUIDE.md CLAUDE.md CHANGELOG.md src/main/java/com/springclaw/service/skill
git commit -m "docs: document unified skill runtime"
```

Skip commit if preserving current dirty work is required.

---

### Task 7: Final Verification

**Files:**
- No production files expected.

- [ ] **Step 1: Run skill-focused tests**

Run:

```bash
mvn -q -Dtest=SkillCatalogServiceTest,SkillScaffoldServiceTest,SkillRuntimeServiceTest,SkillRuntimeBoundaryTest,ScriptSkillToolPackTest,SkillLibraryToolPackTest,OpenClawRealSkillsSmokeTest,LocalSkillFallbackServiceTest,TaskExecutionServiceTest test
```

Expected: PASS.

- [ ] **Step 2: Run compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: exit code 0.

- [ ] **Step 3: Run full test suite**

Run:

```bash
mvn -q test
```

Expected: exit code 0. Existing test logs about mocked model timeouts are acceptable if the build exits 0.

- [ ] **Step 4: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 5: Confirm final architecture with search**

Run:

```bash
rg -n "SkillPackage|skills/packages|package skill|Package-based" src/main/java src/test/java docs/SCRIPT_SKILL_GUIDE.md CLAUDE.md
```

Expected: no output.

Run:

```bash
rg -n "\.runScriptSkill\(|\.runScriptSkillByGoal\(" src/main/java
```

Expected: only allow-list files:

```text
src/main/java/com/springclaw/service/skill/script/ScriptSkillExecutorService.java
src/main/java/com/springclaw/service/skill/runtime/SkillRuntimeService.java
src/main/java/com/springclaw/service/chat/BuiltinSkillExecutionService.java
```

- [ ] **Step 6: Final handoff**

Report:

```text
完成内容：
1. 命名收口：SkillCatalogService / SkillScaffoldService
2. 执行收口：SkillRuntimeService 是业务侧统一入口
3. 边界测试：防止业务代码绕过 runtime
4. 验证：mvn test / compile / diff check 均通过

保留说明：
ScriptSkillExecutorService 仍存在，但它现在是低层 Python adapter，不再是业务入口。
ScriptSkillToolPack 名称暂时保留，因为它是公开工具权限名，直接改会影响权限策略。
```

---

## Self-Review

Spec coverage:

- Direct `skills/<skillId>` layout: already done before this plan; Task 6 verifies no stale `skills/packages` references remain.
- Naming consistency: Task 1 and Task 6 cover service and docs naming.
- Unified execution: Task 2, Task 3, and Task 5 move task, tool, and local fallback execution toward `SkillRuntimeService`.
- High cohesion and low coupling: Task 4 adds a regression boundary so future business code cannot bypass runtime casually.
- Verification: Task 7 gives focused and full commands.

Known deliberate non-goals:

- Do not rename `ScriptSkillToolPack` public tool name in this plan. It appears in permission checks and model-facing tool names, so renaming it should be a later compatibility migration.
- Do not delete `ScriptSkillExecutorService`. It is still the Python process adapter under runtime.
- Do not remove `BuiltinSkillExecutionService`. It remains the builtin adapter under runtime.
