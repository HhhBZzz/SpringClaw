package com.springclaw.controller.proposal;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalRepository;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolGateway;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用授权单 REST 接口。
 *
 * <p>路径前缀 /api/tool-proposals 与既有的 /api/tasks (定时任务) 区分，
 * 也避免和 service.agent.AgentActionProposalService 用的 task-draft proposal 混淆。
 */
@RestController
@RequestMapping("/api/tool-proposals")
public class ToolProposalController {

    private final ToolInvocationProposalService proposalService;
    private final ToolGateway toolGateway;
    private final ToolInvocationProposalRepository repository;

    public ToolProposalController(ToolInvocationProposalService proposalService,
                                  ToolGateway toolGateway,
                                  ToolInvocationProposalRepository repository) {
        this.proposalService = proposalService;
        this.toolGateway = toolGateway;
        this.repository = repository;
    }

    @PostMapping("/{proposalId}/confirm")
    public ApiResponse<ToolInvocationProposal> confirm(@PathVariable String proposalId,
                                                       @RequestBody(required = false) Map<String, String> body) {
        RequestUserContext context = requireContext();
        ToolInvocationProposal existing = proposalService.findByProposalId(proposalId)
                .orElseThrow(() -> new BusinessException(40404, "proposal 不存在"));
        requireOwner(existing, context);
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.success(toolGateway.confirm(proposalId, reason));
    }

    @PostMapping("/{proposalId}/reject")
    public ApiResponse<ToolInvocationProposal> reject(@PathVariable String proposalId,
                                                      @RequestBody(required = false) Map<String, String> body) {
        RequestUserContext context = requireContext();
        ToolInvocationProposal existing = proposalService.findByProposalId(proposalId)
                .orElseThrow(() -> new BusinessException(40404, "proposal 不存在"));
        requireOwner(existing, context);
        String reason = body == null ? "" : body.getOrDefault("reason", "");
        return ApiResponse.success(proposalService.reject(proposalId, reason));
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<ToolInvocationProposal> get(@PathVariable String proposalId) {
        RequestUserContext context = requireContext();
        ToolInvocationProposal proposal = proposalService.findByProposalId(proposalId)
                .orElseThrow(() -> new BusinessException(40404, "proposal 不存在"));
        requireVisible(proposal, context);
        return ApiResponse.success(proposal);
    }

    @GetMapping
    public ApiResponse<List<ToolInvocationProposal>> list(@RequestParam(required = false) String sessionKey,
                                                          @RequestParam(required = false) String status) {
        RequestUserContext context = requireContext();
        List<ToolInvocationProposal> list = repository.listBy(sessionKey, status);
        if (!isPrivileged(context)) {
            String username = context.username();
            list = list.stream()
                    .filter(p -> Objects.equals(p.userId(), username))
                    .toList();
        }
        return ApiResponse.success(list);
    }

    private RequestUserContext requireContext() {
        RequestUserContext context = RequestUserContextHolder.get();
        if (context == null) {
            throw new BusinessException(40101, "未登录");
        }
        return context;
    }

    private boolean isPrivileged(RequestUserContext context) {
        return context != null && context.hasAnyRole("ADMIN", "DEVELOPER");
    }

    private void requireOwner(ToolInvocationProposal proposal, RequestUserContext context) {
        if (proposal == null) {
            throw new BusinessException(40404, "proposal 不存在");
        }
        if (!Objects.equals(proposal.userId(), context.username())) {
            throw new BusinessException(40332, "无权处理该确认请求");
        }
    }

    private void requireVisible(ToolInvocationProposal proposal, RequestUserContext context) {
        if (proposal == null) {
            throw new BusinessException(40404, "proposal 不存在");
        }
        if (isPrivileged(context)) {
            return;
        }
        if (!Objects.equals(proposal.userId(), context.username())) {
            throw new BusinessException(40332, "无权处理该确认请求");
        }
    }
}
