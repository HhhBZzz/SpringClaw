package com.springclaw.controller.proposal;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolProposalControllerTest {

    private final ToolInvocationProposalService proposalService = mock(ToolInvocationProposalService.class);
    private final ToolInvocationProposalRepository repository = mock(ToolInvocationProposalRepository.class);
    private final ToolProposalController controller = new ToolProposalController(proposalService, repository);

    @BeforeEach
    void setUp() {
        RequestUserContextHolder.set(new RequestUserContext("user-1", "USER", System.currentTimeMillis() + 60_000));
    }

    @AfterEach
    void tearDown() {
        RequestUserContextHolder.clear();
    }

    @Test
    void confirm_success_returns200WithProposal() {
        ToolInvocationProposal proposal = proposal("tip-1", ToolInvocationProposalStatus.EXECUTING);
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));
        when(proposalService.confirm("tip-1", "ok")).thenReturn(proposal);

        ApiResponse<ToolInvocationProposal> response = controller.confirm("tip-1", Map.of("reason", "ok"));

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).isSameAs(proposal);
    }

    @Test
    void confirm_propagatesBusinessException() {
        ToolInvocationProposal proposal = proposal("tip-1", ToolInvocationProposalStatus.EXECUTING);
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));
        when(proposalService.confirm("tip-1", "ok"))
                .thenThrow(new BusinessException(40409, "状态非法"));

        assertThatThrownBy(() -> controller.confirm("tip-1", Map.of("reason", "ok")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("状态非法");
    }

    @Test
    void confirm_rejectsOtherUsersProposal() {
        ToolInvocationProposal proposal = new ToolInvocationProposal(
                1L, "tip-1", "req-1", "run-1", "session-A", "other-user", "USER",
                "WorkspaceTool.writeFile", "workspace", "{}", "hash", "HIGH",
                List.of("README.md"), "preview", false, List.of(),
                ToolInvocationProposalStatus.PENDING, 0, null, null, null, "head-sha", null, null,
                List.of(), null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> controller.confirm("tip-1", Map.of("reason", "ok")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 40332)
                .hasMessage("无权处理该确认请求");

        verify(proposalService, never()).confirm(any(), any());
    }

    @Test
    void confirm_allowsAdminForOtherUsersProposal() {
        RequestUserContextHolder.set(new RequestUserContext("admin", "ADMIN", System.currentTimeMillis() + 60_000));
        ToolInvocationProposal proposal = new ToolInvocationProposal(
                1L, "tip-1", "req-1", "run-1", "session-A", "other-user", "USER",
                "WorkspaceTool.writeFile", "workspace", "{}", "hash", "HIGH",
                List.of("README.md"), "preview", false, List.of(),
                ToolInvocationProposalStatus.PENDING, 0, null, null, null, "head-sha", null, null,
                List.of(), null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));
        when(proposalService.confirm("tip-1", "ok")).thenReturn(proposal);

        ApiResponse<ToolInvocationProposal> response = controller.confirm("tip-1", Map.of("reason", "ok"));

        assertThat(response.getCode()).isEqualTo(0);
        verify(proposalService).confirm("tip-1", "ok");
    }

    @Test
    void reject_success_returns200WithProposal() {
        ToolInvocationProposal proposal = proposal("tip-1", ToolInvocationProposalStatus.REJECTED);
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));
        when(proposalService.reject("tip-1", "no")).thenReturn(proposal);

        ApiResponse<ToolInvocationProposal> response = controller.reject("tip-1", Map.of("reason", "no"));

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).isSameAs(proposal);
    }

    @Test
    void get_existing_returns200() {
        ToolInvocationProposal proposal = proposal("tip-1", ToolInvocationProposalStatus.PENDING);
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));

        ApiResponse<ToolInvocationProposal> response = controller.get("tip-1");

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).isSameAs(proposal);
    }

    @Test
    void get_missing_throws40404() {
        when(proposalService.findByProposalId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("missing"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 40404)
                .hasMessage("proposal 不存在");
    }

    @Test
    void list_filtersBySessionAndStatus() {
        List<ToolInvocationProposal> proposals = List.of(
                proposal("tip-1", ToolInvocationProposalStatus.PENDING),
                proposal("tip-2", ToolInvocationProposalStatus.PENDING)
        );
        when(repository.listBy("session-A", "PENDING")).thenReturn(proposals);

        ApiResponse<List<ToolInvocationProposal>> response = controller.list("session-A", "PENDING");

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).hasSize(2);
    }

    @Test
    void list_filtersToCurrentUserForRegularUser() {
        ToolInvocationProposal own = proposal("tip-1", ToolInvocationProposalStatus.PENDING);
        ToolInvocationProposal other = new ToolInvocationProposal(
                2L, "tip-2", "req-2", "run-1", "session-A", "other-user", "USER",
                "WorkspaceTool.writeFile", "workspace", "{}", "hash", "HIGH",
                List.of("README.md"), "preview", false, List.of(),
                ToolInvocationProposalStatus.PENDING, 0, null, null, null, "head-sha", null, null,
                List.of(), null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
        when(repository.listBy(null, null)).thenReturn(List.of(own, other));

        ApiResponse<List<ToolInvocationProposal>> response = controller.list(null, null);

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).containsExactly(own);
    }

    @Test
    void list_adminSeesAll() {
        RequestUserContextHolder.set(new RequestUserContext("admin", "ADMIN", System.currentTimeMillis() + 60_000));
        ToolInvocationProposal own = proposal("tip-1", ToolInvocationProposalStatus.PENDING);
        ToolInvocationProposal other = new ToolInvocationProposal(
                2L, "tip-2", "req-2", "run-1", "session-A", "other-user", "USER",
                "WorkspaceTool.writeFile", "workspace", "{}", "hash", "HIGH",
                List.of("README.md"), "preview", false, List.of(),
                ToolInvocationProposalStatus.PENDING, 0, null, null, null, "head-sha", null, null,
                List.of(), null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
        when(repository.listBy(null, null)).thenReturn(List.of(own, other));

        ApiResponse<List<ToolInvocationProposal>> response = controller.list(null, null);

        assertThat(response.getCode()).isEqualTo(0);
        assertThat(response.getData()).containsExactly(own, other);
    }

    @Test
    void unauthenticatedRequestThrows40101() {
        RequestUserContextHolder.clear();

        assertThatThrownBy(() -> controller.list(null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 40101)
                .hasMessage("未登录");
    }

    private static ToolInvocationProposal proposal(String proposalId, ToolInvocationProposalStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                1L,
                proposalId,
                "req-1",
                "run-1",
                "session-A",
                "user-1",
                "USER",
                "WorkspaceTool.writeFile",
                "workspace",
                "{}",
                "hash",
                "HIGH",
                List.of("README.md"),
                "preview",
                false,
                List.of(),
                status,
                0,
                null,
                null,
                null,
                "head-sha",
                null,
                null,
                List.of(),
                null,
                null,
                now,
                now,
                now.plusMinutes(15)
        );
    }
}
