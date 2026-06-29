package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.consolidation.MemoryConsolidationRunResult;
import com.springclaw.service.memory.consolidation.MemoryConsolidationService;
import com.springclaw.service.memory.review.MemoryCandidateReviewService;
import com.springclaw.web.auth.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeMemoryCandidateControllerTest {

    @Test
    void shouldListMemoryCandidatesThroughRuntimeConsole() {
        MemoryCandidateReviewService reviewService = mock(MemoryCandidateReviewService.class);
        RuntimeMemoryCandidateController controller = controller(reviewService);
        List<MemoryCandidateReviewService.MemoryCandidateReviewItem> items = List.of(item());
        when(reviewService.list("CANDIDATE", 12)).thenReturn(items);

        ApiResponse<List<MemoryCandidateReviewService.MemoryCandidateReviewItem>> response =
                controller.memoryCandidates("CANDIDATE", 12);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(items);
        verify(reviewService).list("CANDIDATE", 12);
    }

    @Test
    void shouldUpdateMemoryCandidateStatusThroughRuntimeConsole() {
        MemoryCandidateReviewService reviewService = mock(MemoryCandidateReviewService.class);
        RuntimeMemoryCandidateController controller = controller(reviewService);
        MemoryCandidateReviewService.MemoryCandidateStatusUpdate update =
                new MemoryCandidateReviewService.MemoryCandidateStatusUpdate(
                        "version-1",
                        "CANDIDATE",
                        "ACTIVE",
                        "approved"
                );
        when(reviewService.updateStatus("version-1", "ACTIVE", "approved"))
                .thenReturn(update);

        ApiResponse<MemoryCandidateReviewService.MemoryCandidateStatusUpdate> response =
                controller.updateCandidateStatus(new RuntimeMemoryCandidateController.UpdateMemoryCandidateStatusRequest(
                        "version-1",
                        "ACTIVE",
                        "approved"
                ));

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(update);
        verify(reviewService).updateStatus("version-1", "ACTIVE", "approved");
    }

    @Test
    void shouldRunMemoryConsolidationThroughRuntimeConsole() {
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        RuntimeMemoryCandidateController controller = new RuntimeMemoryCandidateController(
                mock(MemoryCandidateReviewService.class),
                consolidationService
        );
        MemoryConsolidationRunResult result = new MemoryConsolidationRunResult(
                false,
                null,
                List.of()
        );
        when(consolidationService.consolidate(argThat(scope ->
                scope != null
                        && scope.scopeId().equals("alice")
                        && scope.channel().equals("api")
                        && scope.sessionKey().equals("consolidation")
        ), eq(20))).thenReturn(result);

        ApiResponse<MemoryConsolidationRunResult> response =
                controller.consolidateMemory(new RuntimeMemoryCandidateController.ConsolidateMemoryRequest(
                        "alice",
                        "api",
                        20
                ));

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(result);
        verify(consolidationService).consolidate(argThat(scope ->
                scope != null && scope.equals(MemoryScope.user("api", "consolidation", "alice"))
        ), eq(20));
    }

    @Test
    void shouldRejectMissingCandidateStatusUpdateTarget() {
        RuntimeMemoryCandidateController controller =
                controller(mock(MemoryCandidateReviewService.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateCandidateStatus(new RuntimeMemoryCandidateController.UpdateMemoryCandidateStatusRequest(
                        "",
                        "ACTIVE",
                        "approved"
                )));

        assertThat(ex.getCode()).isEqualTo(40103);
    }

    @Test
    void shouldRejectMissingConsolidationUserId() {
        RuntimeMemoryCandidateController controller =
                controller(mock(MemoryCandidateReviewService.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.consolidateMemory(new RuntimeMemoryCandidateController.ConsolidateMemoryRequest(
                        "",
                        "api",
                        20
                )));

        assertThat(ex.getCode()).isEqualTo(40103);
    }

    @Test
    void memoryCandidateReviewEndpointsShouldRequireAdminRole() throws Exception {
        Method list = RuntimeMemoryCandidateController.class.getMethod(
                "memoryCandidates",
                String.class,
                int.class
        );
        Method update = RuntimeMemoryCandidateController.class.getMethod(
                "updateCandidateStatus",
                RuntimeMemoryCandidateController.UpdateMemoryCandidateStatusRequest.class
        );
        Method consolidate = RuntimeMemoryCandidateController.class.getMethod(
                "consolidateMemory",
                RuntimeMemoryCandidateController.ConsolidateMemoryRequest.class
        );

        assertThat(list.getAnnotation(RequireRole.class).value()).containsExactly("ADMIN");
        assertThat(update.getAnnotation(RequireRole.class).value()).containsExactly("ADMIN");
        assertThat(consolidate.getAnnotation(RequireRole.class).value()).containsExactly("ADMIN");
    }

    private static RuntimeMemoryCandidateController controller(MemoryCandidateReviewService reviewService) {
        return new RuntimeMemoryCandidateController(
                reviewService,
                mock(MemoryConsolidationService.class)
        );
    }

    private static MemoryCandidateReviewService.MemoryCandidateReviewItem item() {
        return new MemoryCandidateReviewService.MemoryCandidateReviewItem(
                "version-1",
                "SEMANTIC",
                "CANDIDATE",
                "User prefers concise Chinese progress summaries.",
                "User preference",
                "alice",
                0.8,
                0.9,
                List.of("run:run-1", "event:chat:run-1:user"),
                List.of("USER_PREFERENCE"),
                "MESSAGE_EVENT",
                "run-1:hash:0",
                "semantic-extraction-v1",
                Instant.parse("2026-06-28T00:00:00Z")
        );
    }
}
