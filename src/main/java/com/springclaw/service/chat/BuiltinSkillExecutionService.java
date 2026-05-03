package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.service.workspace.WorkspaceReviewService;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 显式 builtin skill 执行器。
 * 先把高价值能力收成 skill，后续再逐步替换散落规则。
 */
@Service
public class BuiltinSkillExecutionService {

    private final SkillRegistryService skillRegistryService;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final WorkspaceReviewService workspaceReviewService;
    private final LocalSkillQuerySupport querySupport;

    @Autowired
    public BuiltinSkillExecutionService(SkillRegistryService skillRegistryService,
                                        WorkspaceSearchToolPack workspaceSearchToolPack,
                                        WorkspaceReviewService workspaceReviewService,
                                        ScriptSkillExecutorService scriptSkillExecutorService,
                                        com.springclaw.service.skill.script.ScriptSkillCatalogService scriptSkillCatalogService) {
        this.skillRegistryService = skillRegistryService;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.workspaceReviewService = workspaceReviewService;
        this.querySupport = new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService);
    }

    BuiltinSkillExecutionService(SkillRegistryService skillRegistryService,
                                 WorkspaceSearchToolPack workspaceSearchToolPack,
                                 ScriptSkillExecutorService scriptSkillExecutorService,
                                 com.springclaw.service.skill.script.ScriptSkillCatalogService scriptSkillCatalogService) {
        this(
                skillRegistryService,
                workspaceSearchToolPack,
                new WorkspaceReviewService(System.getProperty("user.dir"), 8, 3000, 40, 512),
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
        return switch (definition.skillId()) {
            case "code-analysis" -> Optional.of(runCodeAnalysis(definition, question));
            case "workspace-review" -> Optional.of(runWorkspaceReview(definition, question));
            case "web-crawl" -> Optional.of(runWebCrawl(definition, question));
            case "log-diagnostics" -> Optional.of(runLogDiagnostics(definition, question));
            default -> Optional.empty();
        };
    }

    private LocalSkillFallbackService.LocalSkillResult runWorkspaceReview(SkillDefinition definition, String question) {
        String answer = workspaceReviewService.reviewWorkspace(question);
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:WORKSPACE_REVIEW",
                detail,
                answer,
                true
        );
    }

    private LocalSkillFallbackService.LocalSkillResult runCodeAnalysis(SkillDefinition definition, String question) {
        String answer = workspaceSearchToolPack.analyzeWorkspaceTask(question);
        if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
            String scriptAnswer = querySupport.runScriptSkillByCategory("workspace", question);
            if (StringUtils.hasText(scriptAnswer)) {
                answer = scriptAnswer;
            }
        }
        String fallback = querySupport.renderWorkspaceAnswer(answer);
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:CODE_ANALYSIS",
                detail,
                fallback,
                true
        );
    }

    private LocalSkillFallbackService.LocalSkillResult runLogDiagnostics(SkillDefinition definition, String question) {
        String answer = querySupport.runScriptSkillByCategory("runtime", question);
        if (!StringUtils.hasText(answer)) {
            answer = workspaceSearchToolPack.analyzeWorkspaceTask(question);
        }
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:LOG_DIAGNOSTICS",
                detail,
                answer,
                true
        );
    }

    private LocalSkillFallbackService.LocalSkillResult runWebCrawl(SkillDefinition definition, String question) {
        String answer = querySupport.runScriptSkillByCategory("web", question);
        if (!StringUtils.hasText(answer)) {
            String target = querySupport.extractFirstUrl(question);
            answer = StringUtils.hasText(target)
                    ? "未找到可用的网页抓取 Python skill，目标链接: " + target
                    : "未找到可用的网页抓取 Python skill，请在 skills 目录中提供 web 类 skill。";
        }
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:WEB_CRAWL",
                detail,
                answer,
                true
        );
    }
}
