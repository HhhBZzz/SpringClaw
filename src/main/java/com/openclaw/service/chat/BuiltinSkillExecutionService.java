package com.openclaw.service.chat;

import com.openclaw.service.skill.SkillDefinition;
import com.openclaw.service.skill.impl.BuiltinSkillCatalogService;
import com.openclaw.service.skill.script.ScriptSkillExecutorService;
import com.openclaw.tool.pack.WorkspaceSearchToolPack;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 显式 builtin skill 执行器。
 * 先把高价值能力收成 skill，后续再逐步替换散落规则。
 */
@Service
public class BuiltinSkillExecutionService {

    private final BuiltinSkillCatalogService builtinSkillCatalogService;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final LocalSkillQuerySupport querySupport;

    public BuiltinSkillExecutionService(BuiltinSkillCatalogService builtinSkillCatalogService,
                                        WorkspaceSearchToolPack workspaceSearchToolPack,
                                        ScriptSkillExecutorService scriptSkillExecutorService,
                                        com.openclaw.service.skill.script.ScriptSkillCatalogService scriptSkillCatalogService) {
        this.builtinSkillCatalogService = builtinSkillCatalogService;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.querySupport = new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService);
    }

    public Optional<LocalSkillFallbackService.LocalSkillResult> tryExecute(String question) {
        return builtinSkillCatalogService.matchDefinition(question)
                .flatMap(definition -> execute(definition, question));
    }

    public Optional<LocalSkillFallbackService.LocalSkillResult> tryExecuteHighConfidence(String question) {
        return builtinSkillCatalogService.matchHighConfidenceDefinition(question)
                .flatMap(definition -> execute(definition, question));
    }

    private Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
        return switch (definition.skillId()) {
            case "code-analysis" -> Optional.of(runCodeAnalysis(definition, question));
            case "web-crawl" -> Optional.of(runWebCrawl(definition, question));
            case "log-diagnostics" -> Optional.of(runLogDiagnostics(definition, question));
            default -> Optional.empty();
        };
    }

    private LocalSkillFallbackService.LocalSkillResult runCodeAnalysis(SkillDefinition definition, String question) {
        String answer = workspaceSearchToolPack.analyzeWorkspaceTask(question);
        if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
            String scriptAnswer = querySupport.runScriptSkillByCategory("workspace", question);
            if (StringUtils.hasText(scriptAnswer)) {
                answer = scriptAnswer;
            }
        }
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:CODE_ANALYSIS",
                detail,
                answer,
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
