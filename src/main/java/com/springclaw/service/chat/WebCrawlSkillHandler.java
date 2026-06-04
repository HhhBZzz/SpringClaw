package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
public class WebCrawlSkillHandler implements BuiltinSkillHandler {

    private final LocalSkillQuerySupport querySupport;

    public WebCrawlSkillHandler(ScriptSkillExecutorService scriptSkillExecutorService,
                                ScriptSkillCatalogService scriptSkillCatalogService) {
        this.querySupport = new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService);
    }

    @Override
    public String skillId() {
        return "web-crawl";
    }

    @Override
    public Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question) {
        String answer = querySupport.runScriptSkillByCategory("web", question);
        if (!StringUtils.hasText(answer)) {
            String target = querySupport.extractFirstUrl(question);
            answer = StringUtils.hasText(target)
                    ? "未找到可用的网页抓取 Python skill，目标链接: " + target
                    : "未找到可用的网页抓取 Python skill，请在 skills 目录中提供 web 类 skill。";
        }
        String detail = "skill=%s\n%s".formatted(definition.skillId(), answer);
        return Optional.of(new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:WEB_CRAWL",
                detail,
                answer,
                true
        ));
    }
}
