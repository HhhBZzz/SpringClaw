package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationSnapshot;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Creates durable approval proposals for the small, explicit command allowlist.
 */
@Service
public class ApprovedCommandProposalService {

    private static final String TOOL_NAME = "SystemToolPack.runCommand";
    private static final String TOOLSET_ID = "system";
    private static final String RISK_LEVEL = "execution";
    private static final String COMMAND_PREFIX = "请执行命令 ";
    private static final String PROPOSAL_PHASE = "APPROVED-COMMAND-PROPOSAL";
    private static final String UNSAFE_COMMAND_CHARACTERS = ";|&<>$(){}[]";

    private final ToolInvocationSnapshotService snapshotService;
    private final ToolInvocationProposalService proposalService;

    public ApprovedCommandProposalService(ToolInvocationSnapshotService snapshotService,
                                          ToolInvocationProposalService proposalService) {
        this.snapshotService = snapshotService;
        this.proposalService = proposalService;
    }

    public Optional<ToolInvocationProposal> createProposalIfSupported(ChatContext context) {
        if (!isExecutionModelControlConfirmation(context)) {
            return Optional.empty();
        }

        String command = supportedCommand(context.userMessage());
        if (command == null) {
            return Optional.empty();
        }

        ToolInvocationSnapshot snapshot = snapshotService.capture(
                TOOL_NAME,
                TOOLSET_ID,
                new Object[]{command},
                RISK_LEVEL
        );
        ToolExecutionContext toolContext = new ToolExecutionContext(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.requestId(),
                PROPOSAL_PHASE,
                context.requestId(),
                context.roleCode()
        );
        return Optional.of(proposalService.createPending(snapshot, toolContext));
    }

    private boolean isExecutionModelControlConfirmation(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        return decision != null
                && decision.requiresConfirmation()
                && "model_control".equalsIgnoreCase(decision.intent())
                && RISK_LEVEL.equalsIgnoreCase(decision.riskLevel());
    }

    private String supportedCommand(String rawMessage) {
        if (!StringUtils.hasText(rawMessage)) {
            return null;
        }

        String message = rawMessage.trim();
        if (!message.startsWith(COMMAND_PREFIX)) {
            return null;
        }

        String command = message.substring(COMMAND_PREFIX.length());
        if (containsUnsafeCharacter(command)) {
            return null;
        }
        if ("pwd".equals(command) || "git status".equals(command)) {
            return command;
        }
        if (command.startsWith("echo ") && StringUtils.hasText(command.substring("echo ".length()))) {
            return command;
        }
        return null;
    }

    private boolean containsUnsafeCharacter(String command) {
        if (command.indexOf('\\') >= 0 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return true;
        }
        return command.chars().anyMatch(character -> UNSAFE_COMMAND_CHARACTERS.indexOf(character) >= 0);
    }
}
