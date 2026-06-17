package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.knowledge.MarkdownKnowledgeSourceService;
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

class RuntimeKnowledgeSourceControllerTest {

    @Test
    void shouldListKnowledgeSourcesThroughRuntimeConsole() {
        MarkdownKnowledgeSourceService service = mock(MarkdownKnowledgeSourceService.class);
        RuntimeKnowledgeSourceController controller = new RuntimeKnowledgeSourceController(service);
        List<MarkdownKnowledgeSourceService.KnowledgeSourceReviewItem> entries = List.of(
                new MarkdownKnowledgeSourceService.KnowledgeSourceReviewItem(
                        "wiki/runtime.md",
                        "active",
                        "wiki-js",
                        true,
                        "included_in_context",
                        "Runtime Notes",
                        128,
                        "2026-06-17T10:00:00Z",
                        "架构师确认"
                )
        );
        when(service.listSources(12)).thenReturn(entries);

        ApiResponse<List<MarkdownKnowledgeSourceService.KnowledgeSourceReviewItem>> response =
                controller.knowledgeSources(12);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(entries);
        verify(service).listSources(12);
    }

    @Test
    void shouldUpdateKnowledgeSourceStatusThroughRuntimeConsole() {
        MarkdownKnowledgeSourceService service = mock(MarkdownKnowledgeSourceService.class);
        RuntimeKnowledgeSourceController controller = new RuntimeKnowledgeSourceController(service);
        MarkdownKnowledgeSourceService.KnowledgeSourceStatusUpdate update =
                new MarkdownKnowledgeSourceService.KnowledgeSourceStatusUpdate(
                        "wiki/runtime.md",
                        "unreviewed",
                        "approved",
                        "人工确认",
                        "2026-06-17T10:01:00Z",
                        true,
                        "included_in_context"
                );
        when(service.updateStatus("wiki/runtime.md", "approved", "人工确认")).thenReturn(Optional.of(update));

        ApiResponse<MarkdownKnowledgeSourceService.KnowledgeSourceStatusUpdate> response =
                controller.updateStatus(new RuntimeKnowledgeSourceController.UpdateKnowledgeSourceStatusRequest(
                        "wiki/runtime.md",
                        "approved",
                        "人工确认"
                ));

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(update);
        assertThat(response.getData().reviewedAt()).isEqualTo("2026-06-17T10:01:00Z");
        verify(service).updateStatus("wiki/runtime.md", "approved", "人工确认");
    }

    @Test
    void shouldRejectMissingKnowledgeSourceStatusUpdateTarget() {
        RuntimeKnowledgeSourceController controller = new RuntimeKnowledgeSourceController(mock(MarkdownKnowledgeSourceService.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStatus(new RuntimeKnowledgeSourceController.UpdateKnowledgeSourceStatusRequest(
                        "",
                        "approved",
                        "missing path"
                )));

        assertThat(ex.getCode()).isEqualTo(40103);
    }

    @Test
    void shouldReturnNotFoundWhenKnowledgeSourceStatusCannotBeUpdated() {
        MarkdownKnowledgeSourceService service = mock(MarkdownKnowledgeSourceService.class);
        RuntimeKnowledgeSourceController controller = new RuntimeKnowledgeSourceController(service);
        when(service.updateStatus("missing.md", "approved", "missing")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.updateStatus(new RuntimeKnowledgeSourceController.UpdateKnowledgeSourceStatusRequest(
                        "missing.md",
                        "approved",
                        "missing"
                )));

        assertThat(ex.getCode()).isEqualTo(40404);
    }

    @Test
    void shouldExposeSnapshotPreviewWithoutRuntimePromptInjection() {
        MarkdownKnowledgeSourceService service = mock(MarkdownKnowledgeSourceService.class);
        RuntimeKnowledgeSourceController controller = new RuntimeKnowledgeSourceController(service);
        when(service.renderSnapshot()).thenReturn(new MarkdownKnowledgeSourceService.KnowledgeSourceSnapshot(
                "### knowledge-source/wiki/runtime.md\nRuntime facts.",
                1,
                2
        ));

        ApiResponse<RuntimeKnowledgeSourceController.KnowledgeSourceSnapshotPreview> response =
                controller.snapshot();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().includedCount()).isEqualTo(1);
        assertThat(response.getData().filteredCount()).isEqualTo(2);
        assertThat(response.getData().contextPreview()).contains("knowledge-source/wiki/runtime.md");
        assertThat(response.getData().contextChars()).isPositive();
        assertThat(response.getData().injectedToRuntimePrompt()).isFalse();
        assertThat(response.getData().contextPolicy()).isEqualTo("review_only_not_runtime_prompt");
        verify(service).renderSnapshot();
    }

    @Test
    void knowledgeSourcesShouldRequireAdminRole() throws Exception {
        Method method = RuntimeKnowledgeSourceController.class.getMethod("knowledgeSources", int.class);
        Method snapshotMethod = RuntimeKnowledgeSourceController.class.getMethod("snapshot");
        Method updateStatusMethod = RuntimeKnowledgeSourceController.class.getMethod(
                "updateStatus",
                RuntimeKnowledgeSourceController.UpdateKnowledgeSourceStatusRequest.class
        );

        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        RequireRole snapshotRequireRole = snapshotMethod.getAnnotation(RequireRole.class);
        RequireRole updateStatusRequireRole = updateStatusMethod.getAnnotation(RequireRole.class);

        assertThat(requireRole).isNotNull();
        assertThat(requireRole.value()).containsExactly("ADMIN");
        assertThat(snapshotRequireRole).isNotNull();
        assertThat(snapshotRequireRole.value()).containsExactly("ADMIN");
        assertThat(updateStatusRequireRole).isNotNull();
        assertThat(updateStatusRequireRole.value()).containsExactly("ADMIN");
    }
}
