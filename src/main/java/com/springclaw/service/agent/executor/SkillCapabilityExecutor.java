package com.springclaw.service.agent.executor;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityExecutor;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SkillLibraryToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class SkillCapabilityExecutor extends AbstractCapabilityExecutor implements CapabilityExecutor {

    private static final Set<String> PROVIDER_CAPABILITIES = Set.of(
            "system", "workspace-search", "workspace-review", "file", "local-files",
            "web", "weather", "news", "exchange", "script-skill", "skill-library",
            "scheduled-task", "dangerous-action"
    );

    private final SkillLibraryToolPack skillLibraryToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;

    public SkillCapabilityExecutor(SkillLibraryToolPack skillLibraryToolPack,
                                   ScriptSkillToolPack scriptSkillToolPack) {
        this.skillLibraryToolPack = skillLibraryToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
    }

    @Override
    public String toolset() {
        return "skills";
    }

    @Override
    public boolean supports(AgentDecision decision) {
        return intent(decision, "skill_task");
    }

    @Override
    public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
        ToolExecutionContext context = new ToolExecutionContext(assembled.sessionKey(), assembled.channel(), assembled.userId(), requestId, "AGENT-RUNTIME");
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            String skillId = decision.selectedCapabilities().stream()
                    .map(this::normalize)
                    .filter(StringUtils::hasText)
                    .filter(value -> !PROVIDER_CAPABILITIES.contains(value))
                    .findFirst()
                    .orElse("");
            if (StringUtils.hasText(skillId)) {
                return List.of(run("skill." + skillId, toolset(), decision.riskLevel(), "执行匹配 skill", () -> scriptSkillToolPack.executeSkillByGoal(skillId, assembled.question())));
            }
            return List.of(run("skill-library.list", toolset(), "read", "列出可用 skill", skillLibraryToolPack::skillsList));
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
