package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.AgentLearningService;
import com.springclaw.web.auth.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeLearningControllerTest {

    @Test
    void shouldUpdateLearningStatusThroughRuntimeConsole() {
        AgentLearningService learningService = mock(AgentLearningService.class);
        RuntimeLearningController controller = new RuntimeLearningController(learningService);
        AgentLearningService.AgentLearningStatusUpdate update =
                new AgentLearningService.AgentLearningStatusUpdate(
                        "sig-1",
                        "active",
                        "disabled",
                        "规则过宽"
                );
        when(learningService.updateStatus("sig-1", "disabled", "规则过宽"))
                .thenReturn(Optional.of(update));

        ApiResponse<AgentLearningService.AgentLearningStatusUpdate> response =
                controller.updateLearningStatus(new RuntimeLearningController.UpdateLearningStatusRequest(
                        "sig-1",
                        "disabled",
                        "规则过宽"
                ));

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(update);
        verify(learningService).updateStatus("sig-1", "disabled", "规则过宽");
    }

    @Test
    void shouldListLearningEntriesThroughRuntimeConsole() {
        AgentLearningService learningService = mock(AgentLearningService.class);
        RuntimeLearningController controller = new RuntimeLearningController(learningService);
        List<AgentLearningService.AgentLearningReviewItem> entries = List.of(
                new AgentLearningService.AgentLearningReviewItem(
                        "sig-1",
                        "active",
                        "manual",
                        "失败命令",
                        "先分析失败条件。",
                        "不要原样重复失败命令。",
                        "连续重试失败 shell。",
                        "tool_failure",
                        "",
                        "req-list-1",
                        "trace failed",
                        "",
                        ""
                )
        );
        when(learningService.listEntries(12)).thenReturn(entries);

        ApiResponse<List<AgentLearningService.AgentLearningReviewItem>> response = controller.learningEntries(12);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(entries);
        verify(learningService).listEntries(12);
    }

    @Test
    void shouldRejectMissingLearningStatusUpdateTarget() {
        RuntimeLearningController controller = new RuntimeLearningController(mock(AgentLearningService.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateLearningStatus(new RuntimeLearningController.UpdateLearningStatusRequest(
                        "",
                        "disabled",
                        "bad rule"
                )));

        assertThat(ex.getCode()).isEqualTo(40103);
    }

    @Test
    void shouldReturnNotFoundWhenLearningStatusCannotBeUpdated() {
        AgentLearningService learningService = mock(AgentLearningService.class);
        RuntimeLearningController controller = new RuntimeLearningController(learningService);
        when(learningService.updateStatus("missing", "disabled", "not found")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateLearningStatus(new RuntimeLearningController.UpdateLearningStatusRequest(
                        "missing",
                        "disabled",
                        "not found"
                )));

        assertThat(ex.getCode()).isEqualTo(40404);
    }

    @Test
    void learningStatusUpdateShouldRequireAdminRole() throws Exception {
        Method method = RuntimeLearningController.class.getMethod(
                "updateLearningStatus",
                RuntimeLearningController.UpdateLearningStatusRequest.class
        );

        RequireRole requireRole = method.getAnnotation(RequireRole.class);

        assertThat(requireRole).isNotNull();
        assertThat(requireRole.value()).containsExactly("ADMIN");
    }

    @Test
    void learningEntriesShouldRequireAdminRole() throws Exception {
        Method method = RuntimeLearningController.class.getMethod("learningEntries", int.class);

        RequireRole requireRole = method.getAnnotation(RequireRole.class);

        assertThat(requireRole).isNotNull();
        assertThat(requireRole.value()).containsExactly("ADMIN");
    }
}
