package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.knowledge.MarkdownKnowledgeSourceService;
import com.springclaw.web.auth.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
                        128
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

        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        RequireRole snapshotRequireRole = snapshotMethod.getAnnotation(RequireRole.class);

        assertThat(requireRole).isNotNull();
        assertThat(requireRole.value()).containsExactly("ADMIN");
        assertThat(snapshotRequireRole).isNotNull();
        assertThat(snapshotRequireRole.value()).containsExactly("ADMIN");
    }
}
