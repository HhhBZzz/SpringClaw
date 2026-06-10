package com.springclaw.tool.runtime;

import com.springclaw.service.skill.SkillService;
import com.springclaw.service.agent.AgentDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 工具编排器。
 *
 * 设计说明：
 * 1. 由运行时按请求语义动态挑选工具包，避免固定全量暴露。
 * 2. 新增工具包只需给 ToolPack 增加 ToolPackDescriptor，不影响 ChatService 主流程。
 * 3. 自主循环模式（autonomous）下，按 intent 范围暴露完整工具集，让模型自主选择工具。
 */
@Component
public class ToolOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrator.class);

    /**
     * 每个 intent 对应的完整工具集范围（写操作 — 全量暴露）。
     * 自主循环模式下，模型可以看到这个范围内的所有工具，自主决定调用顺序。
     */
    private static final Set<String> WORKSPACE_WRITE_TOOLSETS = Set.of("workspace", "file", "script", "system");
    /**
     * 每个 intent 对应的只读工具集范围（读操作 — 不暴露 workspace-edit）。
     * workspace-analysis + riskLevel=read 只需要搜索和读取工具，不需要写文件和执行命令。
     */
    private static final Set<String> WORKSPACE_READ_TOOLSETS = Set.of("workspace", "file");
    private static final Set<String> WEB_TOOLSETS = Set.of("web", "file");
    private static final Set<String> LOCAL_FILES_TOOLSETS = Set.of("file");
    private static final Set<String> SKILL_TOOLSETS = Set.of("script", "system");
    private static final Set<String> ALL_TOOLSETS = Set.of("workspace", "web", "file", "script", "system");

    private final SkillService skillService;
    private final CapabilityRegistry capabilityRegistry;

    @Autowired
    public ToolOrchestrator(SkillService skillService,
                            CapabilityRegistry capabilityRegistry) {
        this.skillService = skillService;
        this.capabilityRegistry = capabilityRegistry;
    }

    public Object[] selectTools(String channel,
                                String userId,
                                String userMessage,
                                String planText) {
        String merged = mergeText(userMessage, planText);
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        return capabilityRegistry.listAll().stream()
                .filter(entry -> isAllowed(entry, allowedToolPacks))
                .filter(entry -> entry.matchesKeywords(merged))
                .map(CapabilityRegistry.CapabilityEntry::toolPackBean)
                .filter(Objects::nonNull)
                .toArray();
    }

    public Object[] selectAgentTools(String channel, String userId) {
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        return capabilityRegistry.listAll().stream()
                .filter(CapabilityRegistry.CapabilityEntry::includeForAgentMode)
                .filter(entry -> isAllowed(entry, allowedToolPacks))
                .map(CapabilityRegistry.CapabilityEntry::toolPackBean)
                .filter(Objects::nonNull)
                .toArray();
    }

    /** Legacy method: select by selectedCapabilities (still used by OparLoopEngine for non-autonomous mode) */
    public Object[] selectAgentTools(String channel, String userId, AgentDecision decision) {
        if (decision == null || decision.isGeneral()) {
            return new Object[0];
        }
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        Set<String> capabilityIds = decision.selectedCapabilities() == null
                ? Set.of()
                : decision.selectedCapabilities().stream()
                .map(this::normalizeCapability)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return capabilityRegistry.listAll().stream()
                .filter(CapabilityRegistry.CapabilityEntry::includeForAgentMode)
                .filter(entry -> isAllowed(entry, allowedToolPacks))
                .filter(entry -> capabilityIds.isEmpty() || capabilityIds.contains(normalizeCapability(entry.id())))
                .map(CapabilityRegistry.CapabilityEntry::toolPackBean)
                .filter(Objects::nonNull)
                .toArray();
    }

    /**
     * 自主循环模式下的工具选择：按 intent + riskLevel 动态暴露工具集。
     *
     * 分级路由策略：
     * - write/side_effect/dangerous → 全量工具（workspace + file + script + system）
     *   模型需要写文件、执行命令、搜索代码的完整能力
     * - read → 只读工具（workspace + file，不含 script/system）
     *   代码审查、项目查看只需要搜索和读取，不需要写和执行
     */
    public Object[] selectAutonomousTools(String channel, String userId, AgentDecision decision) {
        if (decision == null || decision.isGeneral()) {
            return new Object[0];
        }
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        Set<String> scopeToolsets = resolveScopeToolsets(decision.intent(), decision.riskLevel());

        log.info("自主循环工具选择: intent={}, riskLevel={}, scopeToolsets={}",
                decision.intent(), decision.riskLevel(), scopeToolsets);

        return capabilityRegistry.listAll().stream()
                .filter(CapabilityRegistry.CapabilityEntry::includeForAgentMode)
                .filter(entry -> isAllowed(entry, allowedToolPacks))
                .filter(entry -> scopeToolsets.contains(normalizeCapability(entry.toolset())))
                .map(CapabilityRegistry.CapabilityEntry::toolPackBean)
                .filter(Objects::nonNull)
                .toArray();
    }

    public Object[] selectTools(String channel,
                                String userId,
                                String userMessage,
                                String planText,
                                AgentDecision decision) {
        if (decision == null) {
            return selectTools(channel, userId, userMessage, planText);
        }
        String merged = mergeText(userMessage, planText);
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        Set<String> capabilityIds = decision.selectedCapabilities() == null
                ? Set.of()
                : decision.selectedCapabilities().stream()
                .map(this::normalizeCapability)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return capabilityRegistry.listAll().stream()
                .filter(entry -> isAllowed(entry, allowedToolPacks))
                .filter(entry -> capabilityIds.isEmpty() || capabilityIds.contains(normalizeCapability(entry.id())))
                .filter(entry -> entry.matchesKeywords(merged) || capabilityIds.contains(normalizeCapability(entry.id())))
                .map(CapabilityRegistry.CapabilityEntry::toolPackBean)
                .filter(Objects::nonNull)
                .toArray();
    }

    private Set<String> resolveScopeToolsets(String intent, String riskLevel) {
        // write/side_effect/dangerous 不管 intent 是什么，都需要完整的写操作工具集
        if ("write".equals(riskLevel) || "side_effect".equals(riskLevel) || "dangerous".equals(riskLevel)) {
            return WORKSPACE_WRITE_TOOLSETS; // workspace + file + script + system
        }
        // read-only 的查询按 intent 选择对应工具范围
        Set<String> baseToolsets = resolveScopeToolsets(intent);
        // 动态缩减：read-only 的 workspace_analysis 不暴露 script/system
        if ("workspace_analysis".equals(intent) && "read".equals(riskLevel)) {
            return WORKSPACE_READ_TOOLSETS;
        }
        return baseToolsets;
    }

    private Set<String> resolveScopeToolsets(String intent) {
        return switch (intent) {
            case "workspace_analysis" -> WORKSPACE_WRITE_TOOLSETS;
            case "web_research" -> WEB_TOOLSETS;
            case "local_files" -> LOCAL_FILES_TOOLSETS;
            case "skill_task" -> SKILL_TOOLSETS;
            default -> ALL_TOOLSETS;
        };
    }

    private String mergeText(String userMessage, String planText) {
        return ((userMessage == null ? "" : userMessage)
                + " "
                + (planText == null ? "" : planText))
                .toLowerCase();
    }

    private String normalizeCapability(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private boolean isAllowed(CapabilityRegistry.CapabilityEntry entry, Set<String> allowedToolPacks) {
        if (entry == null) {
            return false;
        }
        if (allowedToolPacks == null || allowedToolPacks.isEmpty()) {
            return false;
        }
        return allowedToolPacks.contains(normalizeCapability(entry.toolset()))
                || allowedToolPacks.contains(normalizeCapability(entry.id()));
    }
}
