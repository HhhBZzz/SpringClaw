package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 显式 builtin skill 执行器。
 * 先把高价值能力收成 skill，后续再逐步替换散落规则。
 */
@Service
public class BuiltinSkillExecutionService {

    private final SkillRegistryService skillRegistryService;
    private final Map<String, BuiltinSkillHandler> handlers;

    @Autowired
    public BuiltinSkillExecutionService(SkillRegistryService skillRegistryService,
                                        List<BuiltinSkillHandler> handlers) {
        this.skillRegistryService = skillRegistryService;
        this.handlers = indexHandlers(handlers);
    }

    public BuiltinSkillExecutionService(SkillRegistryService skillRegistryService,
                                        WorkspaceSearchToolPack workspaceSearchToolPack,
                                        com.springclaw.service.workspace.WorkspaceReviewService workspaceReviewService,
                                        com.springclaw.service.skill.script.ScriptSkillExecutorService scriptSkillExecutorService,
                                        com.springclaw.service.skill.script.ScriptSkillCatalogService scriptSkillCatalogService) {
        this(
                skillRegistryService,
                List.of(
                        new CodeAnalysisSkillHandler(workspaceSearchToolPack, scriptSkillExecutorService, scriptSkillCatalogService),
                        new WorkspaceReviewSkillHandler(workspaceReviewService),
                        new WebCrawlSkillHandler(scriptSkillExecutorService, scriptSkillCatalogService),
                        new LogDiagnosticsSkillHandler(workspaceSearchToolPack, scriptSkillExecutorService, scriptSkillCatalogService)
                )
        );
    }

    BuiltinSkillExecutionService(SkillRegistryService skillRegistryService,
                                        WorkspaceSearchToolPack workspaceSearchToolPack,
                                        com.springclaw.service.skill.script.ScriptSkillExecutorService scriptSkillExecutorService,
                                        com.springclaw.service.skill.script.ScriptSkillCatalogService scriptSkillCatalogService) {
        this(
                skillRegistryService,
                workspaceSearchToolPack,
                new com.springclaw.service.workspace.WorkspaceReviewService(System.getProperty("user.dir"), 8, 3000, 40, 512),
                scriptSkillExecutorService,
                scriptSkillCatalogService
        );
    }

    public Optional<LocalSkillFallbackService.LocalSkillResult> tryExecute(String question) {
        return skillRegistryService.matchDefinition(question)
                .flatMap(definition -> execute(definition, question));
    }

    public Optional<LocalSkillFallbackService.LocalSkillResult> tryExecuteHighConfidence(String question) {
        return skillRegistryService.matchHighConfidenceDefinition(question)
                .flatMap(definition -> execute(definition, question));
    }

    public Optional<LocalSkillFallbackService.LocalSkillResult> executeBySkillId(String skillId, String question) {
        if (!StringUtils.hasText(skillId)) {
            return Optional.empty();
        }
        return skillRegistryService.listAllDefinitions().stream()
                .filter(definition -> skillId.trim().equalsIgnoreCase(definition.skillId()))
                .findFirst()
                .flatMap(definition -> execute(definition, question));
    }

    private Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
        if (definition == null || !StringUtils.hasText(definition.skillId())) {
            return Optional.empty();
        }
        BuiltinSkillHandler handler = handlers.get(definition.skillId().trim().toLowerCase());
        return handler == null ? Optional.empty() : handler.execute(definition, question);
    }

    private Map<String, BuiltinSkillHandler> indexHandlers(List<BuiltinSkillHandler> handlers) {
        Map<String, BuiltinSkillHandler> indexed = new LinkedHashMap<>();
        if (handlers == null) {
            return indexed;
        }
        for (BuiltinSkillHandler handler : handlers) {
            if (handler != null && StringUtils.hasText(handler.skillId())) {
                indexed.putIfAbsent(handler.skillId().trim().toLowerCase(), handler);
            }
        }
        return indexed;
    }
}
