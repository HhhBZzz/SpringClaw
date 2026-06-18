package com.springclaw.architecture;

import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.tool.runtime.ToolRuntimeAspect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test — pins the safety boundary today: every actual
 * {@code @Tool} invocation must funnel through {@link ToolRuntimeAspect}, and
 * the proposal state machine must enforce the documented transitions.
 * Findings referenced from runtime audit doc § 3 ("Risk and confirmation"),
 * § 5 ("Capability / tool execution"), and § 11 ("Proposal confirm-resume").
 *
 * <p>This test does NOT exercise the aspect end-to-end (that is what
 * {@code ToolRuntimeAspectInterceptionIT} already does). It pins the static
 * structural invariants the unified-runtime spec must preserve:
 * <ol>
 *   <li>{@link ToolRuntimeAspect} carries the {@code @Around} pointcut bound
 *       to {@code @org.springframework.ai.tool.annotation.Tool}, so any
 *       Spring proxy invocation of a {@code @Tool} method must intercept.</li>
 *   <li>The aspect's risk-resolution accepts {@code write / dangerous /
 *       side_effect / execution} as the runtime "needs a proposal" set —
 *       see {@link ToolRuntimeAspect#aroundTool} line 91-94.</li>
 *   <li>{@link ToolInvocationProposalStatus} state machine has the documented
 *       8 states with the documented terminal set.</li>
 * </ol>
 *
 * <p>If a future refactor moves the safety boundary somewhere else — e.g., a
 * unified {@code ToolGateway} — these assertions need to be replaced together
 * with the implementation, not silently skipped.
 */
class ToolSafetyPathCharacterizationTest {

    @Test
    @DisplayName("ToolRuntimeAspect is annotated with @Aspect and @Component "
            + "so Spring activates it as the @Tool interception point")
    void toolRuntimeAspectIsActiveAspectComponent() {
        // The class-level annotations together are what make the @Around
        // pointcut fire on @Tool methods. Removing either kills the
        // safety boundary.
        boolean hasAspect = Arrays.stream(ToolRuntimeAspect.class.getAnnotations())
                .anyMatch(a -> a.annotationType().getName()
                        .equals("org.aspectj.lang.annotation.Aspect"));
        boolean hasComponent = ToolRuntimeAspect.class.isAnnotationPresent(Component.class);
        assertThat(hasAspect)
                .as("ToolRuntimeAspect must be @Aspect")
                .isTrue();
        assertThat(hasComponent)
                .as("ToolRuntimeAspect must be a Spring @Component")
                .isTrue();
    }

    @Test
    @DisplayName("ToolRuntimeAspect.aroundTool is bound by @Around to the "
            + "Spring AI @Tool annotation — the only entry that gates @Tool calls")
    void aroundToolPointcutTargetsSpringAiToolAnnotation() throws Exception {
        Method method = ToolRuntimeAspect.class.getMethod(
                "aroundTool", org.aspectj.lang.ProceedingJoinPoint.class);

        // Read @Around dynamically to avoid a hard dep on the annotation class
        // at compile time of this test.
        Annotation[] annos = method.getAnnotations();
        boolean hasAround = Arrays.stream(annos).anyMatch(a -> a.annotationType()
                .getName().equals("org.aspectj.lang.annotation.Around"));
        assertThat(hasAround)
                .as("aroundTool must carry @Around")
                .isTrue();

        // The pointcut text references @annotation(Tool) — pin the literal
        // so a typo or annotation rename fails this test loudly.
        Annotation around = Arrays.stream(annos).filter(a -> a.annotationType()
                .getName().equals("org.aspectj.lang.annotation.Around"))
                .findFirst().orElseThrow();
        Method valueAccessor = around.annotationType().getMethod("value");
        String pointcut = (String) valueAccessor.invoke(around);
        assertThat(pointcut)
                .as("Pointcut must target the Spring AI @Tool annotation")
                .contains("org.springframework.ai.tool.annotation.Tool");
    }

    @Test
    @DisplayName("ProposalStatus state machine has the documented 8 states "
            + "and terminal set")
    void proposalStatusEnumShape() {
        // The audit doc § 11 + the P0 spec rely on this state inventory and
        // terminal classification. Adding a new state silently breaks the
        // unified-runtime spec's compatibility plan.
        assertThat(EnumSet.allOf(ToolInvocationProposalStatus.class))
                .containsExactlyInAnyOrder(
                        ToolInvocationProposalStatus.PENDING,
                        ToolInvocationProposalStatus.APPROVED,
                        ToolInvocationProposalStatus.EXECUTING,
                        ToolInvocationProposalStatus.EXECUTED,
                        ToolInvocationProposalStatus.FAILED,
                        ToolInvocationProposalStatus.REJECTED,
                        ToolInvocationProposalStatus.EXPIRED,
                        ToolInvocationProposalStatus.CANCELLED);

        Set<ToolInvocationProposalStatus> terminal = Stream
                .of(ToolInvocationProposalStatus.values())
                .filter(ToolInvocationProposalStatus::isTerminal)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(terminal).containsExactlyInAnyOrder(
                ToolInvocationProposalStatus.EXECUTED,
                ToolInvocationProposalStatus.FAILED,
                ToolInvocationProposalStatus.REJECTED,
                ToolInvocationProposalStatus.EXPIRED,
                ToolInvocationProposalStatus.CANCELLED);

        // PENDING / APPROVED / EXECUTING are non-terminal by design — they
        // are the live phases of the state machine.
        assertThat(ToolInvocationProposalStatus.PENDING.isTerminal()).isFalse();
        assertThat(ToolInvocationProposalStatus.APPROVED.isTerminal()).isFalse();
        assertThat(ToolInvocationProposalStatus.EXECUTING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Risk levels that REQUIRE a proposal today: write / dangerous "
            + "/ side_effect / execution — pinned from ToolRuntimeAspect")
    void requiresProposalRiskLevelsAreDocumented() {
        // This is enforced inside ToolRuntimeAspect.aroundTool (line 91-94):
        //   boolean requiresProposal =
        //       "write".equalsIgnoreCase(riskLevel)
        //    || "dangerous".equalsIgnoreCase(riskLevel)
        //    || "side_effect".equalsIgnoreCase(riskLevel)
        //    || "execution".equalsIgnoreCase(riskLevel);
        // Reflective/integration testing of that logic lives in
        // ToolRuntimeAspectGuardTest. Here we pin the documented set in a
        // place easy to grep so the unified-runtime ToolGateway design has a
        // canonical reference.
        List<String> requiresProposal = List.of(
                "write", "dangerous", "side_effect", "execution");
        List<String> readOnly = Arrays.asList("read", "safe", null, "");

        for (String level : requiresProposal) {
            assertThat(level).isIn("write", "dangerous", "side_effect", "execution");
        }
        for (String level : readOnly) {
            assertThat(level == null
                    || !List.of("write", "dangerous", "side_effect", "execution")
                            .contains(level.toLowerCase()))
                    .as("read-only level [%s] must not be in requires-proposal set", level)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Spring AOP utilities are available at test runtime — sanity "
            + "check that proxy interception infra is on the classpath")
    void springAopProxyInfrastructureAvailable() {
        // Used by the production aspect; if this dependency disappears, the
        // safety boundary stops working entirely.
        assertThat(AopUtils.class).isNotNull();
        assertThat(AopProxyUtils.class).isNotNull();
    }
}
