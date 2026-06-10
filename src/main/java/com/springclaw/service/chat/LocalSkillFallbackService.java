package com.springclaw.service.chat;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.util.TextUtils;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.service.skill.script.ScriptSkillDefinition;
import com.springclaw.tool.pack.ExchangeRateToolPack;
import com.springclaw.tool.pack.NewsToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.pack.SystemToolPack;
import com.springclaw.tool.pack.WebSearchToolPack;
import com.springclaw.tool.pack.WeatherToolPack;
import com.springclaw.tool.pack.WorkspaceSearchToolPack;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 本地技能兜底服务。
 *
 * 设计说明：
 * 1. 当模型不可用或回答偏离时，使用规则路由到现有 ToolPack，保证“可执行能力”。
 * 2. 仅处理高频强确定性意图（天气/汇率/新闻/检索/时间），避免误判干扰主链路。
 */
@Service
public class LocalSkillFallbackService {

    private final boolean enabled;
    private final WorkspaceSearchToolPack workspaceSearchToolPack;
    private final WebSearchToolPack webSearchToolPack;
    private final WeatherToolPack weatherToolPack;
    private final ExchangeRateToolPack exchangeRateToolPack;
    private final NewsToolPack newsToolPack;
    private final ScriptSkillToolPack scriptSkillToolPack;
    private final BuiltinSkillExecutionService builtinSkillExecutionService;
    private final LocalSkillModelControlSupport modelControlSupport;
    private final LocalSkillQuerySupport querySupport;
    private final LocalFilesystemService localFilesystemService;
    private final CapabilityRegistry capabilityRegistry;

    LocalSkillFallbackService(boolean enabled,
                              SystemToolPack systemToolPack,
                              WorkspaceSearchToolPack workspaceSearchToolPack,
                              WebSearchToolPack webSearchToolPack,
                              WeatherToolPack weatherToolPack,
                              ExchangeRateToolPack exchangeRateToolPack,
                              NewsToolPack newsToolPack,
                              ScriptSkillToolPack scriptSkillToolPack,
                              ScriptSkillCatalogService scriptSkillCatalogService,
                              SkillRegistryService skillRegistryService,
                              AiProviderService aiProviderService) {
        this(enabled,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                createBuiltinSkillExecutionService(enabled, workspaceSearchToolPack, scriptSkillCatalogService, skillRegistryService),
                new LocalSkillQuerySupport(createScriptSkillExecutorService(enabled, scriptSkillCatalogService), scriptSkillCatalogService),
                aiProviderService,
                null,
                null);
    }

    public LocalSkillFallbackService(boolean enabled,
                                     SystemToolPack systemToolPack,
                                     WorkspaceSearchToolPack workspaceSearchToolPack,
                                     WebSearchToolPack webSearchToolPack,
                                     WeatherToolPack weatherToolPack,
                                     ExchangeRateToolPack exchangeRateToolPack,
                                     NewsToolPack newsToolPack,
                                     ScriptSkillToolPack scriptSkillToolPack,
                                     BuiltinSkillExecutionService builtinSkillExecutionService,
                                     ScriptSkillExecutorService scriptSkillExecutorService,
                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                     AiProviderService aiProviderService) {
        this(enabled,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                builtinSkillExecutionService,
                new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService),
                aiProviderService,
                null,
                null);
    }

    public LocalSkillFallbackService(boolean enabled,
                                     SystemToolPack systemToolPack,
                                     WorkspaceSearchToolPack workspaceSearchToolPack,
                                     WebSearchToolPack webSearchToolPack,
                                     WeatherToolPack weatherToolPack,
                                     ExchangeRateToolPack exchangeRateToolPack,
                                     NewsToolPack newsToolPack,
                                     ScriptSkillToolPack scriptSkillToolPack,
                                     BuiltinSkillExecutionService builtinSkillExecutionService,
                                     ScriptSkillExecutorService scriptSkillExecutorService,
                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                     AiProviderService aiProviderService,
                                     LocalFilesystemService localFilesystemService) {
        this(enabled,
                systemToolPack,
                workspaceSearchToolPack,
                webSearchToolPack,
                weatherToolPack,
                exchangeRateToolPack,
                newsToolPack,
                scriptSkillToolPack,
                builtinSkillExecutionService,
                new LocalSkillQuerySupport(scriptSkillExecutorService, scriptSkillCatalogService),
                aiProviderService,
                localFilesystemService,
                null);
    }

    private LocalSkillFallbackService(boolean enabled,
                                      SystemToolPack systemToolPack,
                                      WorkspaceSearchToolPack workspaceSearchToolPack,
                                      WebSearchToolPack webSearchToolPack,
                                      WeatherToolPack weatherToolPack,
                                      ExchangeRateToolPack exchangeRateToolPack,
                                      NewsToolPack newsToolPack,
                                      ScriptSkillToolPack scriptSkillToolPack,
                                      BuiltinSkillExecutionService builtinSkillExecutionService,
                                      LocalSkillQuerySupport querySupport,
                                      AiProviderService aiProviderService,
                                      LocalFilesystemService localFilesystemService,
                                      CapabilityRegistry capabilityRegistry) {
        this.enabled = enabled;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.newsToolPack = newsToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.builtinSkillExecutionService = builtinSkillExecutionService;
        this.modelControlSupport = new LocalSkillModelControlSupport(systemToolPack, aiProviderService);
        this.querySupport = querySupport;
        this.localFilesystemService = localFilesystemService;
        this.capabilityRegistry = capabilityRegistry;
    }

    @Autowired
    public LocalSkillFallbackService(@Value("${springclaw.chat.local-fallback-enabled:true}") boolean enabled,
                                     SystemToolPack systemToolPack,
                                     WorkspaceSearchToolPack workspaceSearchToolPack,
                                     WebSearchToolPack webSearchToolPack,
                                     WeatherToolPack weatherToolPack,
                                     ExchangeRateToolPack exchangeRateToolPack,
                                     NewsToolPack newsToolPack,
                                     ScriptSkillToolPack scriptSkillToolPack,
                                     BuiltinSkillExecutionService builtinSkillExecutionService,
                                     SkillRuntimeService skillRuntimeService,
                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                     AiProviderService aiProviderService,
                                     LocalFilesystemService localFilesystemService,
                                     CapabilityRegistry capabilityRegistry) {
        this.enabled = enabled;
        this.workspaceSearchToolPack = workspaceSearchToolPack;
        this.webSearchToolPack = webSearchToolPack;
        this.weatherToolPack = weatherToolPack;
        this.exchangeRateToolPack = exchangeRateToolPack;
        this.newsToolPack = newsToolPack;
        this.scriptSkillToolPack = scriptSkillToolPack;
        this.builtinSkillExecutionService = builtinSkillExecutionService;
        this.modelControlSupport = new LocalSkillModelControlSupport(systemToolPack, aiProviderService);
        this.querySupport = new LocalSkillQuerySupport(skillRuntimeService, scriptSkillCatalogService);
        this.localFilesystemService = localFilesystemService;
        this.capabilityRegistry = capabilityRegistry;
    }

    private static ScriptSkillExecutorService createScriptSkillExecutorService(boolean enabled,
                                                                               ScriptSkillCatalogService scriptSkillCatalogService) {
        return new ScriptSkillExecutorService(
                enabled,
                scriptSkillCatalogService,
                "python3",
                8,
                3000,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
    }

    private static BuiltinSkillExecutionService createBuiltinSkillExecutionService(boolean enabled,
                                                                                   WorkspaceSearchToolPack workspaceSearchToolPack,
                                                                                   ScriptSkillCatalogService scriptSkillCatalogService,
                                                                                   SkillRegistryService skillRegistryService) {
        return new BuiltinSkillExecutionService(
                skillRegistryService,
                workspaceSearchToolPack,
                createScriptSkillExecutorService(enabled, scriptSkillCatalogService),
                scriptSkillCatalogService
        );
    }

    public Optional<String> tryHandle(String question) {
        return tryHandleStructured(question).map(LocalSkillResult::fallbackAnswer);
    }

    public Optional<LocalSkillResult> tryHandleControlPlane(String question) {
        if (!enabled) {
            return Optional.empty();
        }
        return modelControlSupport.tryHandleControlPlane(question);
    }

    public Optional<LocalSkillResult> tryHandlePriorityStructured(String question) {
        if (!enabled || !StringUtils.hasText(question)) {
            return Optional.empty();
        }
        Optional<LocalSkillResult> localFileResult = tryHandleLocalFileBrowse(question.trim());
        if (localFileResult.isPresent()) {
            return localFileResult;
        }
        return builtinSkillExecutionService.tryExecuteHighConfidence(question.trim());
    }

    public Optional<LocalSkillResult> tryHandleStructured(String question) {
        if (!enabled || !StringUtils.hasText(question)) {
            return Optional.empty();
        }

        String q = question.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        Optional<LocalSkillResult> controlPlane = tryHandleControlPlane(q);
        if (controlPlane.isPresent()) {
            return controlPlane;
        }

        Optional<LocalSkillResult> localFileResult = tryHandleLocalFileBrowse(q);
        if (localFileResult.isPresent()) {
            return localFileResult;
        }

        if (looksLikeLocalFileBoundaryQuestion(lower)) {
            return renderLocalFileBoundary();
        }

        Optional<LocalSkillResult> highConfidenceBuiltinSkill = builtinSkillExecutionService.tryExecuteHighConfidence(q);
        if (highConfidenceBuiltinSkill.isPresent()) {
            return highConfidenceBuiltinSkill;
        }

        Optional<LocalSkillResult> builtinSkill = builtinSkillExecutionService.tryExecute(q);
        if (builtinSkill.isPresent()) {
            return builtinSkill;
        }

        if (modelControlSupport.looksLikeDateQuestion(lower)) {
            String answer = modelControlSupport.renderTodayInfo();
            return localResult("SYSTEM_DATE", answer, answer, false);
        }

        // 运行时诊断（无对应 ToolPack，保留）
        if (TextUtils.containsAny(lower, "端口占用", "谁占用了", "启动失败", "服务没起来", "port", "端口被占用", "诊断运行")) {
            String answer = querySupport.runScriptSkillByCategory("runtime", q);
            if (StringUtils.hasText(answer)) {
                return localResult("RUNTIME_DIAGNOSIS", answer, answer, true);
            }
            String fallback = "未找到可用的运行诊断 skill，请检查 skills 目录与白名单配置。";
            return localResult("RUNTIME_DIAGNOSIS", fallback, fallback, false);
        }

        // 使用 CapabilityRegistry 匹配最佳兜底候选（替代所有硬编码 containsAny）
        if (capabilityRegistry != null) {
            CapabilityRegistry.CapabilityEntry bestMatch = capabilityRegistry.findBestFallback(q);
            if (bestMatch != null) {
                return dispatchFallback(bestMatch.id(), q, lower);
            }
        } else {
            // 向后兼容：CapabilityRegistry 不可用时的硬编码兜底
            Optional<LocalSkillResult> legacyResult = tryLegacyFallback(q, lower);
            if (legacyResult.isPresent()) {
                return legacyResult;
            }
        }

        // 网页抓取（需要特殊 URL 提取逻辑，无法仅靠 ToolPack）
        if (querySupport.looksLikeExplicitWebFetchQuestion(q)) {
            return handleWebFetch(q);
        }

        // 显式脚本技能请求
        if (querySupport.looksLikeExplicitScriptSkillQuestion(lower)) {
            return handleExplicitScriptSkill(q);
        }

        // 高置信度自动脚本技能
        ScriptSkillDefinition autoSkill = querySupport.resolveHighConfidenceScriptSkill(q);
        if (autoSkill != null) {
            String answer = querySupport.runScriptSkillByName(autoSkill.skillName(), q);
            if (StringUtils.hasText(answer) && !querySupport.looksLikeFailure(answer)) {
                return localResult("SCRIPT_SKILL_AUTO", answer, answer, true);
            }
        }

        return Optional.empty();
    }

    /**
     * 根据 CapabilityRegistry 匹配结果分发到对应的 ToolPack。
     * 关键词匹配已由 CapabilityRegistry 完成，此处仅做方法调用分发。
     */
    private Optional<LocalSkillResult> dispatchFallback(String capabilityId, String q, String lower) {
        return switch (capabilityId) {
            case "weather" -> {
                String city = querySupport.extractCity(q);
                String answer = weatherToolPack.queryWeather(city);
                if (querySupport.looksLikeFailure(answer)) {
                    String skillAnswer = querySupport.runScriptSkillByCategory("weather", q);
                    if (StringUtils.hasText(skillAnswer)) {
                        yield localResult("WEATHER_QUERY", skillAnswer, skillAnswer, true);
                    }
                }
                yield localResult("WEATHER_QUERY", answer, answer, true);
            }
            case "exchange" -> {
                String[] currencies = querySupport.extractCurrencies(q);
                String answer = exchangeRateToolPack.queryExchangeRate(currencies[0], currencies[1]);
                yield localResult("EXCHANGE_RATE", answer, answer, true);
            }
            case "news" -> {
                String keyword = querySupport.extractNewsKeyword(q);
                String answer = newsToolPack.searchNews(keyword);
                yield localResult("NEWS_SEARCH", answer, answer, true);
            }
            case "web" -> {
                String keyword = querySupport.extractWebKeyword(q);
                String answer = webSearchToolPack.webSearch(keyword);
                yield localResult("WEB_SEARCH", answer, answer, true);
            }
            case "workspace-search" -> {
                String answer = workspaceSearchToolPack.analyzeWorkspaceTask(q);
                if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
                    String skillAnswer = querySupport.runScriptSkillByCategory("workspace", q);
                    if (StringUtils.hasText(skillAnswer)) {
                        yield localResult("WORKSPACE_ANALYSIS", skillAnswer, querySupport.renderWorkspaceAnswer(skillAnswer), true);
                    }
                }
                yield localResult("WORKSPACE_ANALYSIS", answer, querySupport.renderWorkspaceAnswer(answer), true);
            }
            case "script-skill" -> {
                String answer = scriptSkillToolPack.listScriptSkills();
                yield localResult("SCRIPT_SKILL_LIST", answer, answer, true);
            }
            case "file" -> localResult("FILE_OPERATION",
                    "文件操作需要通过 Agent 工具链路执行。",
                    "文件操作需要通过 Agent 工具链路执行。", false);
            case "local-files" -> tryHandleLocalFileBrowse(q).or(
                    () -> localResult("LOCAL_FILES", "请在聊天中说明具体要读取的本地文件。", "请在聊天中说明具体要读取的本地文件。", false));
            default -> Optional.empty();
        };
    }

    private Optional<LocalSkillResult> handleWebFetch(String q) {
        String answer = querySupport.runScriptSkillByCategory("web", q);
        if (StringUtils.hasText(answer)) {
            return localResult("WEB_CRAWL", answer, answer, true);
        }
        String target = querySupport.extractFirstUrl(q);
        String fallback = StringUtils.hasText(target)
                ? "未找到可用的网页抓取 Python skill，目标链接: " + target
                : "未找到可用的网页抓取 Python skill。";
        return localResult("WEB_CRAWL", fallback, fallback, false);
    }

    /**
     * CapabilityRegistry 不可用时的向后兼容硬编码兜底。
     * 当所有构造函数都传递了 CapabilityRegistry 后，此方法可删除。
     */
    private Optional<LocalSkillResult> tryLegacyFallback(String q, String lower) {
        if (TextUtils.containsAny(lower, "天气", "气温", "下雨", "weather")) {
            String city = querySupport.extractCity(q);
            String answer = weatherToolPack.queryWeather(city);
            if (querySupport.looksLikeFailure(answer)) {
                String skillAnswer = querySupport.runScriptSkillByCategory("weather", q);
                if (StringUtils.hasText(skillAnswer)) {
                    return localResult("WEATHER_QUERY", skillAnswer, skillAnswer, true);
                }
            }
            return localResult("WEATHER_QUERY", answer, answer, true);
        }
        if (TextUtils.containsAny(lower, "汇率", "exchange", "美元", "人民币", "欧元", "日元")) {
            String[] currencies = querySupport.extractCurrencies(q);
            String answer = exchangeRateToolPack.queryExchangeRate(currencies[0], currencies[1]);
            return localResult("EXCHANGE_RATE", answer, answer, true);
        }
        if (TextUtils.containsAny(lower, "新闻", "热点", "资讯", "news")) {
            String keyword = querySupport.extractNewsKeyword(q);
            String answer = newsToolPack.searchNews(keyword);
            return localResult("NEWS_SEARCH", answer, answer, true);
        }
        if (querySupport.looksLikeWorkspaceQuestion(lower)) {
            String answer = workspaceSearchToolPack.analyzeWorkspaceTask(q);
            if (querySupport.looksLikeWeakWorkspaceAnswer(answer)) {
                String skillAnswer = querySupport.runScriptSkillByCategory("workspace", q);
                if (StringUtils.hasText(skillAnswer)) {
                    return localResult("WORKSPACE_ANALYSIS", skillAnswer, querySupport.renderWorkspaceAnswer(skillAnswer), true);
                }
            }
            return localResult("WORKSPACE_ANALYSIS", answer, querySupport.renderWorkspaceAnswer(answer), true);
        }
        if (querySupport.looksLikeExplicitWebSearchQuestion(lower)) {
            String keyword = querySupport.extractWebKeyword(q);
            String answer = webSearchToolPack.webSearch(keyword);
            return localResult("WEB_SEARCH", answer, answer, true);
        }
        if (TextUtils.containsAny(lower, "脚本技能列表", "有哪些脚本技能", "可用脚本技能", "script skill", "script skills")) {
            String answer = scriptSkillToolPack.listScriptSkills();
            return localResult("SCRIPT_SKILL_LIST", answer, answer, true);
        }
        return Optional.empty();
    }

    private Optional<LocalSkillResult> handleExplicitScriptSkill(String q) {
        ScriptSkillDefinition skillDefinition = querySupport.resolveRequestedScriptSkill(q);
        if (skillDefinition == null) {
            String answer = scriptSkillToolPack.listScriptSkills();
            return localResult("SCRIPT_SKILL_LIST", answer, answer, true);
        }
        String answer = querySupport.runScriptSkillByName(skillDefinition.skillName(), q);
        return localResult("SCRIPT_SKILL_RUN", answer, answer, true);
    }

    private Optional<LocalSkillResult> tryHandleLocalFileBrowse(String question) {
        if (localFilesystemService == null || !StringUtils.hasText(question)) {
            return Optional.empty();
        }
        String lower = question.toLowerCase(Locale.ROOT);
        // 优先判断"打开/读取桌面上的XX"意图
        if (looksLikeDesktopFileOpenQuestion(lower)) {
            return handleDesktopFileOpen(question);
        }
        if (!looksLikeDesktopListQuestion(lower)) {
            return Optional.empty();
        }
        return renderDesktopListing();
    }

    /**
     * 识别"打开/读取/查看桌面上的XX文件"意图。
     * 区别于"列出桌面有什么"：这里是动作意图（要打开/读取某个文件），不是列举意图。
     */
    private boolean looksLikeDesktopFileOpenQuestion(String lower) {
        boolean mentionsDesktop = lower.contains("桌面") || lower.contains("desktop");
        if (!mentionsDesktop) {
            return false;
        }
        return TextUtils.containsAny(lower,
                "打开", "读取", "查看", "看看", "读一下", "看一下", "open", "read", "view");
    }

    /**
     * 处理"打开桌面上的XX"意图：先列出桌面文件，再按关键词匹配。
     * - 唯一匹配：直接返回文件信息（路径+是否支持读取）
     * - 多个匹配：列出候选让用户选择
     * - 无匹配：告知没找到
     */
    private Optional<LocalSkillResult> handleDesktopFileOpen(String question) {
        String listing = safeListDesktop();
        if (!StringUtils.hasText(listing) || listing.startsWith("目录不存在") || listing.startsWith("桌面目录读取失败")) {
            return localResult("LOCAL_FILES_DESKTOP_OPEN_FAILED",
                    listing, "桌面目录读取失败，无法搜索文件。", false);
        }
        List<DesktopEntry> allEntries = parseDesktopEntries(listing);
        // 从问题中提取文件关键词（去掉意图词和方位短语）
        String keywordPart = extractFileKeywordFromOpenQuestion(question);
        List<DesktopEntry> ranked = filterEntriesByKeyword(allEntries, keywordPart);

        if (ranked.isEmpty()) {
            String answer = "在桌面上没有找到匹配 \"" + keywordPart + "\" 的文件。桌面上的文件有：\n"
                    + renderCandidateList(allEntries);
            return localResult("LOCAL_FILES_DESKTOP_OPEN_NO_MATCH",
                    listing, answer, true);
        }

        // 计算各级命中数量
        String queryLower = keywordPart.toLowerCase(Locale.ROOT);
        int exactCount = 0;
        int andCount = 0;
        int orCount = 0;
        for (DesktopEntry entry : ranked) {
            String nameLower = entry.name().toLowerCase(Locale.ROOT);
            if (nameLower.contains(queryLower)) {
                exactCount++;
            } else if (isAllKeywordsMatch(nameLower, queryLower)) {
                andCount++;
            } else {
                orCount++;
            }
        }

        // 直接选中规则：仅当最高置信级只有一个候选时才直接选中
        if (exactCount == 1) {
            // 唯一 exact match → 高置信，直接选中
            DesktopEntry entry = ranked.get(0);
            return buildSingleFileResult(entry, listing);
        }
        if (exactCount == 0 && andCount == 1) {
            // 唯一 AND match → 也直接选中
            DesktopEntry entry = ranked.get(0);
            return buildSingleFileResult(entry, listing);
        }
        if (exactCount == 0 && andCount == 0 && ranked.size() == 1) {
            // 唯一 OR match（整个列表只有1项）→ 直接选中
            DesktopEntry entry = ranked.get(0);
            return buildSingleFileResult(entry, listing);
        }

        // 多个候选（任何置信级有多个，或混合多个级）→ 列候选让用户选择
        String answer = "桌面上有多个匹配 \"" + keywordPart + "\" 的文件，请告诉我要打开哪一份（回复编号即可）：\n\n"
                + renderCandidateList(ranked);
        return localResult("LOCAL_FILES_DESKTOP_OPEN_MULTIPLE",
                listing, answer, true);
    }

    /**
     * 判断文件名是否同时包含所有拆分关键词（AND 语义）。
     */
    private boolean isAllKeywordsMatch(String nameLower, String queryLower) {
        List<String> keywords = splitQueryKeywords(queryLower);
        if (keywords.size() < 2) return false; // 单关键词无需 AND 判断
        for (String kw : keywords) {
            if (!nameLower.contains(kw)) return false;
        }
        return true;
    }

    private Optional<LocalSkillResult> buildSingleFileResult(DesktopEntry entry, String listing) {
        String filePath = resolveDesktopFilePath(entry.name());
        String readResult = safeReadDesktopFile(entry.name());
        String answer;
        if (readResult != null && !readResult.startsWith("找到了文件") && !readResult.startsWith("文件不存在")) {
            answer = "我找到了桌面上的文件 " + entry.name() + "，内容如下：\n\n" + readResult;
        } else {
            answer = "我找到了桌面上的文件：" + entry.name()
                    + (StringUtils.hasText(filePath) ? "。文件路径：" + filePath : "")
                    + "\n\n" + (readResult != null ? readResult : "");
        }
        return localResult("LOCAL_FILES_DESKTOP_OPEN_SINGLE",
                listing, answer, true);
    }

    /**
     * 从用户输入中提取 file_query 部分。
     * 只删除已知方位短语和意图词，保留 file_query 中的"的"等字。
     * 例："打开桌面上的老师的课件" → file_query="老师的课件"
     * 例："查看桌面上的毕业论文" → file_query="毕业论文"
     */
    private String extractFileKeywordFromOpenQuestion(String question) {
        String cleaned = question.toLowerCase(Locale.ROOT);
        // 删除已知方位短语（整体删除，不拆开"的"）
        cleaned = cleaned.replace("桌面上的", "")
                .replace("桌面的", "")
                .replace("desktop上的", "")
                .replace("desktop的", "")
                .replace("上的文件", "")
                .replace("上的", "")
                .replace("的文件", "");
        // 删除意图词
        cleaned = cleaned.replace("打开", "")
                .replace("读取", "")
                .replace("查看", "")
                .replace("看看", "")
                .replace("读一下", "")
                .replace("看一下", "")
                .replace("找一下", "")
                .replace("搜一下", "")
                .replace("open", "")
                .replace("read", "")
                .replace("view", "")
                .replace("桌面", "")
                .replace("desktop", "")
                .replace("那个", "")
                .replace("那个文件", "")
                .replace("这个文件", "")
                .replace("该文件", "")
                .trim();
        // 不做 .replace("的", "")——保留 file_query 中的"的"
        return StringUtils.hasText(cleaned) ? cleaned : question.trim();
    }

    /**
     * 通用 file_query ranking：三级置信度排序。
     * 第一级：完整 file_query 包含命中（最高置信度）
     * 第二级：所有拆分关键词 AND 命中（文件名同时包含每个关键词）
     * 第三级：任一关键词 OR 命中（低置信度）
     * 返回按置信度降序排列的候选列表。
     */
    private List<DesktopEntry> filterEntriesByKeyword(List<DesktopEntry> entries, String keywordPart) {
        if (!StringUtils.hasText(keywordPart) || entries.isEmpty()) {
            return List.of();
        }
        String queryLower = keywordPart.toLowerCase(Locale.ROOT);
        List<String> keywords = splitQueryKeywords(queryLower);

        // 三级分组
        List<DesktopEntry> exactMatches = new ArrayList<>();   // 完整 file_query 命中
        List<DesktopEntry> andMatches = new ArrayList<>();     // AND 命中（所有关键词同时出现）
        List<DesktopEntry> orMatches = new ArrayList<>();      // OR 命中（任一关键词出现）

        for (DesktopEntry entry : entries) {
            String nameLower = entry.name().toLowerCase(Locale.ROOT);
            // 第一级：完整 file_query 命中
            if (nameLower.contains(queryLower)) {
                exactMatches.add(entry);
                continue;
            }
            // 第二级和第三级依赖拆分关键词
            if (keywords.size() >= 2) {
                boolean allMatch = true;
                boolean anyMatch = false;
                for (String kw : keywords) {
                    if (nameLower.contains(kw)) {
                        anyMatch = true;
                    } else {
                        allMatch = false;
                    }
                }
                if (allMatch) {
                    andMatches.add(entry);
                } else if (anyMatch) {
                    orMatches.add(entry);
                }
            } else if (keywords.size() == 1) {
                // 单关键词：完整命中已在第一级处理，这里只剩部分场景
                if (nameLower.contains(keywords.get(0))) {
                    orMatches.add(entry);
                }
            }
        }

        // 合并：exact > and > or
        List<DesktopEntry> ranked = new ArrayList<>();
        ranked.addAll(exactMatches);
        ranked.addAll(andMatches);
        ranked.addAll(orMatches);
        return ranked;
    }

    /**
     * 拆分 file_query 为多个关键词：按空格、逗号、中文顿号分隔。
     * 对连续中文词不做分词拆分——"毕业论文"作为一个整体 token。
     * "项目 报告" → ["项目", "报告"]；"毕业论文" → ["毕业论文"]
     */
    private List<String> splitQueryKeywords(String queryLower) {
        if (!StringUtils.hasText(queryLower)) {
            return List.of();
        }
        String[] parts = queryLower.split("[\\s,，、]+");
        List<String> keywords = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2) {
                keywords.add(trimmed);
            }
        }
        // 如果拆分后没有有效关键词，整词作为兜底
        if (keywords.isEmpty() && queryLower.trim().length() >= 2) {
            keywords.add(queryLower.trim());
        }
        return keywords;
    }

    private String renderCandidateList(List<DesktopEntry> entries) {
        if (entries.isEmpty()) {
            return "（无候选文件）";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (DesktopEntry entry : entries) {
            builder.append(index++).append(". ").append(entry.name()).append("\n");
        }
        return builder.toString().trim();
    }

    private String resolveDesktopFilePath(String fileName) {
        try {
            String listing = localFilesystemService.listFiles("", "Desktop");
            for (String line : TextUtils.safe(listing).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.contains(fileName)) {
                    // 提取 root1:Desktop/xxx 格式的路径
                    int colonIdx = trimmed.indexOf(':');
                    if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
                        String afterColon = trimmed.substring(colonIdx + 1);
                        if (afterColon.contains(fileName)) {
                            return "root1:" + afterColon.trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            String listing = localFilesystemService.listFiles("Desktop", "");
            for (String line : TextUtils.safe(listing).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.contains(fileName)) {
                    int colonIdx = trimmed.indexOf(':');
                    if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
                        return "Desktop:" + trimmed.substring(colonIdx + 1).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return fileName;
    }

    private String safeReadDesktopFile(String fileName) {
        try {
            String filePath = resolveDesktopFilePath(fileName);
            if (filePath == null || !StringUtils.hasText(filePath)) {
                return null;
            }
            // 解析 rootRef 和 relativePath
            int colonIdx = filePath.indexOf(':');
            if (colonIdx < 0) {
                return null;
            }
            String rootRef = filePath.substring(0, colonIdx);
            String relativePath = filePath.substring(colonIdx + 1);
            return localFilesystemService.readTextFile(rootRef, relativePath);
        } catch (Exception ex) {
            return "读取失败: " + ex.getMessage();
        }
    }

    private boolean looksLikeDesktopListQuestion(String lower) {
        boolean mentionsDesktop = lower.contains("桌面") || lower.contains("desktop");
        if (!mentionsDesktop) {
            return false;
        }
        return TextUtils.containsAny(lower,
                "授权桌面", "桌面全部", "桌面所有", "桌面文件", "桌面目录",
                "列出", "列表", "有哪些", "有什么", "查看", "看看", "全部", "所有", "文件清单");
    }

    private Optional<LocalSkillResult> renderDesktopListing() {
        String roots = safeReadAuthorizedRoots();
        String listing = safeListDesktop();
        String answer = renderDesktopListingAnswer(roots, listing);
        return localResult("LOCAL_FILES_DESKTOP_LIST", listing, answer, true);
    }

    private String safeReadAuthorizedRoots() {
        try {
            return localFilesystemService.listAuthorizedRoots();
        } catch (Exception ex) {
            return "授权目录读取失败: " + ex.getMessage();
        }
    }

    private String safeListDesktop() {
        try {
            String listing = localFilesystemService.listFiles("", "Desktop");
            if (StringUtils.hasText(listing) && !listing.startsWith("目录不存在")) {
                return listing;
            }
        } catch (Exception ignored) {
            // 继续尝试 rootRef=Desktop，兼容直接把 Desktop 配成授权根目录的场景。
        }
        try {
            return localFilesystemService.listFiles("Desktop", "");
        } catch (Exception ex) {
            return "桌面目录读取失败: " + ex.getMessage();
        }
    }

    private String renderDesktopListingAnswer(String roots, String listing) {
        String desktopPath = System.getProperty("user.home", "/Users/<username>") + "/Desktop";
        if (!StringUtils.hasText(listing) || listing.startsWith("目录不存在") || listing.startsWith("桌面目录读取失败")) {
            return """
                    ### 结论
                    我现在没有成功列出桌面目录。

                    ### 原因
                    %s

                    ### 当前授权根目录
                    %s

                    ### 说明
                    “授权桌面全部”不能只靠聊天临时扩大权限；真实读取范围由后端启动配置 `SPRINGCLAW_LOCAL_FILES_ROOTS` 决定。
                    如果要完整读取桌面，请把 `%s` 或用户主目录配进该变量后重启后端。
                    """.formatted(listing, roots, desktopPath);
        }

        List<DesktopEntry> entries = parseDesktopEntries(listing);
        StringBuilder builder = new StringBuilder();
        builder.append("### 结论\n");
        builder.append("桌面在当前授权范围内。我可以列出文件和文件夹；读取内容时仍只允许非敏感文本文件，`.docx` 这类二进制文档当前先展示文件名。\n\n");
        builder.append("### 桌面文件清单\n");
        if (entries.isEmpty()) {
            builder.append("桌面目录为空，或只有受保护/不可列出的文件。\n\n");
        } else {
            builder.append("| 序号 | 类型 | 文件名 |\n");
            builder.append("| --- | --- | --- |\n");
            int index = 1;
            for (DesktopEntry entry : entries) {
                builder.append("| ")
                        .append(index++)
                        .append(" | ")
                        .append(entry.type())
                        .append(" | ")
                        .append(escapeMarkdownCell(entry.name()))
                        .append(" |\n");
            }
            builder.append("\n");
        }
        builder.append("### 授权说明\n");
        builder.append("当前授权根目录：\n");
        builder.append(roots).append("\n\n");
        builder.append("“授权桌面全部”不会在聊天里临时扩权；它只能使用后端已经配置好的授权根目录。\n");
        return builder.toString().trim();
    }

    private List<DesktopEntry> parseDesktopEntries(String listing) {
        List<DesktopEntry> entries = new ArrayList<>();
        List<String> seenNames = new ArrayList<>();
        for (String rawLine : TextUtils.safe(listing).split("\\R")) {
            String line = rawLine.trim();
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String type;
            if (line.startsWith("[F] ")) {
                type = "文件";
                line = line.substring(4).trim();
            } else if (line.startsWith("[D] ")) {
                type = "文件夹";
                line = line.substring(4).trim();
            } else {
                continue;
            }
            String name = decodeFileName(extractFileName(line));
            if (StringUtils.hasText(name) && !isDesktopNoiseFile(name) && !seenNames.contains(name)) {
                seenNames.add(name);
                entries.add(new DesktopEntry(type, name));
            }
        }
        return entries;
    }

    private String decodeFileName(String name) {
        String text = TextUtils.safe(name);
        if (!text.contains("%")) {
            return text;
        }
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return text;
        }
    }

    private boolean isDesktopNoiseFile(String name) {
        String text = TextUtils.safe(name).trim();
        return !StringUtils.hasText(text)
                || text.startsWith(".")
                || text.startsWith("~$");
    }

    private String extractFileName(String path) {
        String text = TextUtils.safe(path).trim();
        int slash = text.lastIndexOf('/');
        if (slash >= 0 && slash < text.length() - 1) {
            return text.substring(slash + 1);
        }
        int colon = text.indexOf(':');
        if (colon >= 0 && colon < text.length() - 1) {
            return text.substring(colon + 1);
        }
        return text;
    }

    private String escapeMarkdownCell(String text) {
        return TextUtils.safe(text).replace("|", "\\|");
    }

    private boolean looksLikeLocalFileBoundaryQuestion(String lower) {
        return TextUtils.containsAny(lower,
                "本地文件边界", "本地文件权限", "授权本地目录", "授权目录", "授权根目录",
                "授权文件", "电脑文件", "本机文件", "能读取哪些本地", "读取边界",
                "只能读取当前项目", "只能看当前项目", "当前项目目录");
    }

    private Optional<LocalSkillResult> renderLocalFileBoundary() {
        if (localFilesystemService == null) {
            return Optional.empty();
        }
        String roots;
        try {
            roots = localFilesystemService.listAuthorizedRoots();
        } catch (Exception ex) {
            roots = "授权目录读取失败: " + ex.getMessage();
        }
        String answer = """
                结论：不是只能读取当前项目目录。

                当前有两类本地文件能力：
                1. 项目工作区：用于审查当前项目源码、配置、脚本和文档。
                2. Local Files：用于浏览、搜索和读取你明确授权的本机目录。

                当前授权根目录：
                %s

                安全边界：
                1. 只能读取授权根目录内的非敏感文本文件。
                2. 不读取 .ssh、.gnupg、Keychains、浏览器 Profile、.env 等敏感路径。
                3. 不越权访问授权目录之外的系统路径。
                """.formatted(roots);
        return localResult("LOCAL_FILES_BOUNDARY", answer, answer, false);
    }

    private Optional<LocalSkillResult> localResult(String route, String executionDetails, String fallbackAnswer, boolean detailed) {
        return Optional.of(new LocalSkillResult(route, executionDetails, fallbackAnswer, detailed));
    }


    public record LocalSkillResult(String route,
                                   String executionDetails,
                                   String fallbackAnswer,
                                   boolean detailed) {
    }

    private record DesktopEntry(String type, String name) {
    }
}
