package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationSnapshot;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class LocalFileWriteProposalService {

    private static final String TOOL_NAME = "FileToolPack.writeTextFile";
    private static final String TOOLSET_ID = "file";
    private static final Set<String> DENIED_SEGMENTS = Set.of(
            ".git", ".ssh", ".gnupg", ".aws", ".kube", ".env", "keychains"
    );

    private final ToolInvocationSnapshotService snapshotService;
    private final ToolInvocationProposalService proposalService;
    private final LocalFileWritePlanner planner;

    public LocalFileWriteProposalService(ToolInvocationSnapshotService snapshotService,
                                         ToolInvocationProposalService proposalService,
                                         LocalFileWritePlanner planner) {
        this.snapshotService = snapshotService;
        this.proposalService = proposalService;
        this.planner = planner;
    }

    public Optional<ToolInvocationProposal> createProposalIfSupported(ChatContext context) {
        if (!isLocalWriteDecision(context)) {
            return Optional.empty();
        }
        return planner.plan(context)
                .filter(plan -> isSafeRelativePath(plan.relativePath()))
                .map(plan -> createProposal(context, plan));
    }

    private ToolInvocationProposal createProposal(ChatContext context, LocalFileWritePlan plan) {
        Object[] args = new Object[]{
                normalizeRelativePath(plan.relativePath()),
                plan.content() == null ? "" : plan.content(),
                plan.overwrite()
        };
        ToolInvocationSnapshot snapshot = snapshotService.capture(TOOL_NAME, TOOLSET_ID, args, "write");
        ToolExecutionContext toolContext = new ToolExecutionContext(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.requestId(),
                "LOCAL-FILE-WRITE-PROPOSAL",
                context.requestId(),
                context.roleCode()
        );
        return proposalService.createPending(snapshot, toolContext);
    }

    private boolean isLocalWriteDecision(ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        if (decision == null || !decision.requiresConfirmation()) {
            return false;
        }
        boolean writeRisk = "write".equalsIgnoreCase(decision.riskLevel());
        boolean localFileIntent = "local_files".equalsIgnoreCase(decision.intent())
                || decision.selectedCapabilities().stream().anyMatch(value ->
                "file".equalsIgnoreCase(value) || "local-files".equalsIgnoreCase(value));
        return writeRisk && localFileIntent;
    }

    private boolean isSafeRelativePath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return false;
        }
        String normalized = normalizeRelativePath(rawPath);
        if (!StringUtils.hasText(normalized)
                || normalized.startsWith("/")
                || normalized.startsWith("~")
                || normalized.contains("\0")) {
            return false;
        }
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute()) {
            return false;
        }
        String posix = path.toString().replace('\\', '/');
        if (!StringUtils.hasText(posix)
                || ".".equals(posix)
                || posix.equals("..")
                || posix.startsWith("../")
                || posix.contains("/../")) {
            return false;
        }
        for (String segment : posix.split("/")) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (!StringUtils.hasText(segment)
                    || ".".equals(segment)
                    || "..".equals(segment)
                    || DENIED_SEGMENTS.contains(lower)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeRelativePath(String rawPath) {
        return rawPath == null ? "" : rawPath.trim().replace('\\', '/');
    }
}
