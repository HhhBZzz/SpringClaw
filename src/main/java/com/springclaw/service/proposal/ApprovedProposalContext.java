package com.springclaw.service.proposal;

/**
 * 已批准授权单上下文——通过 ContextInjection 干路注入到 ToolRuntimeAspect 的二次校验路径。
 *
 * 仅携带二次校验所需的最小字段（id、requestId、runId、用户、工具、参数指纹），
 * 不直接传递完整 proposal 以避免上下文泄露。
 */
public record ApprovedProposalContext(
        String proposalId,
        String requestId,
        String runId,
        String userId,
        String toolName,
        String argumentsHash
) {
    public static ApprovedProposalContext from(ToolInvocationProposal p) {
        return new ApprovedProposalContext(
                p.proposalId(), p.requestId(), p.runId(),
                p.userId(), p.toolName(), p.argumentsHash()
        );
    }
}
