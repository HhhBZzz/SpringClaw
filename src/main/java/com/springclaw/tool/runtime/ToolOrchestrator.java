package com.springclaw.tool.runtime;

import com.springclaw.service.skill.SkillService;
import com.springclaw.service.agent.AgentDecision;
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
 */
@Component
public class ToolOrchestrator {

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
