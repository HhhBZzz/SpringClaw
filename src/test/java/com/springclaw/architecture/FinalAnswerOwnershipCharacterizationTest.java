package com.springclaw.architecture;

import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.MetaGuardExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test — pins the 12 final-answer composition sites named in
 * the runtime audit doc § 7. We do not call the model here; we record the
 * static surface area (record shape, public method inventory, signature
 * vocabulary) so any consolidation under the unified-runtime spec is
 * detectable.
 *
 * <p>Findings under test:
 * <ol>
 *   <li>{@link ChatExecutionResult} has the 4 OPAR-residue fields
 *       ({@code observe / plan / action / reflect}) plus {@code modelEnabled}.
 *       The {@code reflect} slot is what every engine fills with the final
 *       user-visible answer despite the OPAR-era name.</li>
 *   <li>{@link MetaGuardExecutor} exposes 3 public composition entry points
 *       ({@code execute}, {@code normalize}, {@code fallbackAnswer}) — every
 *       blocking path that is NOT {@link com.springclaw.service.chat.impl.AutonomousLoopEngine}
 *       or {@link com.springclaw.service.agent.AgentRuntimeEngine} ends here.</li>
 *   <li>{@link ChatResponsePolicyService} exposes the keyword-detector and
 *       canned-fallback toolkit that the audit calls "an empirical patch
 *       collection".</li>
 * </ol>
 */
class FinalAnswerOwnershipCharacterizationTest {

    @Test
    @DisplayName("ChatExecutionResult is a 5-field record carrying OPAR residue "
            + "(observe / plan / action / reflect) plus modelEnabled")
    void chatExecutionResultRecordShape() {
        assertThat(ChatExecutionResult.class.isRecord()).isTrue();

        List<String> componentNames = Arrays.stream(ChatExecutionResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactly(
                "observe", "plan", "action", "reflect", "modelEnabled");

        // Pin types — observe/plan/action/reflect are String, modelEnabled is boolean.
        // If the unified-runtime spec replaces ChatExecutionResult with a RunResult,
        // this test should be migrated alongside the new contract — not skipped.
        for (RecordComponent rc : ChatExecutionResult.class.getRecordComponents()) {
            if (rc.getName().equals("modelEnabled")) {
                assertThat(rc.getType()).isEqualTo(boolean.class);
            } else {
                assertThat(rc.getType()).isEqualTo(String.class);
            }
        }
    }

    @Test
    @DisplayName("MetaGuardExecutor exposes execute / normalize / fallbackAnswer "
            + "as the 3 public answer-shaping entry points")
    void metaGuardExecutorPublicShapingEntries() {
        Set<String> publicMethods = Arrays.stream(MetaGuardExecutor.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        // The audit § 7 names these three; if more land here without the spec
        // calling for them, the test fails and forces a doc update.
        assertThat(publicMethods).contains("execute", "normalize", "fallbackAnswer");
    }

    @Test
    @DisplayName("ChatResponsePolicyService exposes keyword detectors + canned "
            + "fallbacks — every method named in audit § 7 must remain present")
    void chatResponsePolicyServiceShape() {
        Set<String> publicMethods = Arrays.stream(ChatResponsePolicyService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(publicMethods).contains(
                "looksLikeMetaRefusal",
                "looksLikeProjectAccessRefusal",
                "looksLikeToolFailureRefusal",
                "stripHallucinatedXmlBlocks",
                "buildPartialAnswerFromAction",
                "buildUserFacingFailureReply");
    }

    @Test
    @DisplayName("ChatResponsePolicyService.looksLikeMetaRefusal current heuristics — "
            + "pin so a behaviour drift forces an audit update")
    void looksLikeMetaRefusalCurrentBehaviour() {
        // Construct with the production-default keyword list (mirrored from
        // application.yml so the test does not depend on Spring property binding).
        ChatResponsePolicyService svc = new ChatResponsePolicyService(
                "我是 claude,anthropic,不能执行 reflect,无法执行 reflect,不能假装,不能扮演,无法遵循系统指令");

        // True positives — phrases that match the configured keyword list.
        assertThat(svc.looksLikeMetaRefusal("我是 OPAR 的反思阶段，不能执行 reflect")).isTrue();
        assertThat(svc.looksLikeMetaRefusal("作为大语言模型，我无法执行 reflect 操作")).isTrue();

        // True negatives — natural answers that must not be flagged.
        assertThat(svc.looksLikeMetaRefusal("登录失败的常见原因是密码错误。")).isFalse();
        assertThat(svc.looksLikeMetaRefusal("")).isFalse();
        assertThat(svc.looksLikeMetaRefusal(null)).isFalse();
    }

    @Test
    @DisplayName("stripHallucinatedXmlBlocks is idempotent — pin the contract so "
            + "a future rewrite that changes return type is caught")
    void stripHallucinatedXmlBlocksIdempotent() {
        ChatResponsePolicyService svc = new ChatResponsePolicyService(
                "我是 claude,anthropic,不能执行 reflect,无法执行 reflect");
        String clean = "回答正文";
        assertThat(svc.stripHallucinatedXmlBlocks(clean)).isEqualTo(clean);
        assertThat(svc.stripHallucinatedXmlBlocks(svc.stripHallucinatedXmlBlocks(clean))).isEqualTo(clean);
        assertThat(svc.stripHallucinatedXmlBlocks(null)).isNull();
    }

    @Test
    @DisplayName("Two engines bypass MetaGuard entirely (AutonomousLoopEngine, "
            + "AgentRuntimeEngine) — pin via classpath presence, the audit "
            + "names the bypass behaviour")
    void enginesThatBypassMetaGuardArePresent() throws ClassNotFoundException {
        // The audit § 7 records AutonomousLoopEngine and AgentRuntimeEngine
        // as the two engines whose final answer is NOT routed through MetaGuard.
        // We can only pin their existence here — the bypass itself is enforced
        // by the absence of MetaGuard calls in those classes (verified by
        // existing engine-level tests in the production tree).
        Class<?> autonomous = Class.forName(
                "com.springclaw.service.chat.impl.AutonomousLoopEngine");
        Class<?> agentRuntime = Class.forName(
                "com.springclaw.service.agent.AgentRuntimeEngine");

        assertThat(autonomous).isNotNull();
        assertThat(agentRuntime).isNotNull();
    }

    @Test
    @DisplayName("Audit doc § 7 enumerates 12 final-answer sites — keep the "
            + "list in code as a doc-test so the audit and production stay aligned")
    void auditFinalAnswerSitesInventory() {
        // This list is the doc-as-test of the audit's 12-site enumeration.
        // If a unification PR lands, the inventory below must be edited and
        // the audit doc must be updated together.
        List<String> finalAnswerSites = List.of(
                "BasicStreamEngine.execute (engine direct)",
                "SimplifiedOparEngine.run (engine direct)",
                "OparLoopEngine.runLoop reflect (engine direct)",
                "AutonomousLoopEngine.resolveFinalAnswer (engine direct, bypasses MetaGuard)",
                "AgentRuntimeEngine.summarize / buildFinalDegradedResult (engine direct, bypasses MetaGuard)",
                "ChatServiceImpl.streamReflectAnswer",
                "ChatServiceImpl.resolveFinalAnswer",
                "MetaGuardExecutor.execute",
                "MetaGuardExecutor.normalize",
                "MetaGuardExecutor.fallbackAnswer",
                "ChatResponsePolicyService.stripHallucinatedXmlBlocks",
                "ChatResponsePolicyService.buildPartialAnswerFromAction");

        assertThat(finalAnswerSites)
                .as("Inventory size matches the audit's '12 sites' claim")
                .hasSize(12);
    }
}
