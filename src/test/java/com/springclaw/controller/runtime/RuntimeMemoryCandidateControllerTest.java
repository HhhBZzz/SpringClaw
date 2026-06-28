package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.review.MemoryCandidateReviewService;
import com.springclaw.web.auth.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeMemoryCandidateControllerTest {

    @Test
    void shouldListMemoryCandidatesThroughRuntimeConsole() {
        MemoryCandidateReviewService reviewService = mock(MemoryCandidateReviewService.class);
        RuntimeMemoryCandidateController controller = new RuntimeMemoryCandidateController(reviewService);
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
        RuntimeMemoryCandidateController controller = new RuntimeMemoryCandidateController(reviewService);
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
    void shouldRejectMissingCandidateStatusUpdateTarget() {
        RuntimeMemoryCandidateController controller =
                new RuntimeMemoryCandidateController(mock(MemoryCandidateReviewService.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateCandidateStatus(new RuntimeMemoryCandidateController.UpdateMemoryCandidateStatusRequest(
                        "",
                        "ACTIVE",
                        "approved"
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

        assertThat(list.getAnnotation(RequireRole.class).value()).containsExactly("ADMIN");
        assertThat(update.getAnnotation(RequireRole.class).value()).containsExactly("ADMIN");
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
