package com.springclaw.service.chat;

import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.service.skill.script.ScriptSkillDefinition;
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalSkillQuerySupport {

    private static final Pattern WEATHER_CITY_PATTERN =
            Pattern.compile("([\\p{IsHan}A-Za-z]{2,20}?)(天气|气温|温度|下雨|weather)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN =
            Pattern.compile("((?:https?://|www\\.)[^\\s]+)", Pattern.CASE_INSENSITIVE);

    private final ScriptSkillRunner scriptSkillRunner;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final Map<String, String> currencyAliasMap = new LinkedHashMap<>();

    LocalSkillQuerySupport(ScriptSkillExecutorService scriptSkillExecutorService,
                           ScriptSkillCatalogService scriptSkillCatalogService) {
        this(scriptSkillExecutorService::runScriptSkillByGoal, scriptSkillCatalogService);
    }

    LocalSkillQuerySupport(SkillRuntimeService skillRuntimeService,
                           ScriptSkillCatalogService scriptSkillCatalogService) {
        this((skillName, goal) -> skillRuntimeService.executeBySkillId(skillName, goal, Set.of("script")), scriptSkillCatalogService);
    }

    private LocalSkillQuerySupport(ScriptSkillRunner scriptSkillRunner,
                                   ScriptSkillCatalogService scriptSkillCatalogService) {
        this.scriptSkillRunner = scriptSkillRunner;
        this.scriptSkillCatalogService = scriptSkillCatalogService;

        currencyAliasMap.put("美元", "USD");
        currencyAliasMap.put("美金", "USD");
        currencyAliasMap.put("人民币", "CNY");
        currencyAliasMap.put("日元", "JPY");
        currencyAliasMap.put("欧元", "EUR");
        currencyAliasMap.put("英镑", "GBP");
        currencyAliasMap.put("港币", "HKD");
        currencyAliasMap.put("韩元", "KRW");
    }

    boolean looksLikeWorkspaceQuestion(String text) {
        boolean searchIntent = containsAny(text,
                "找文件", "在哪个文件", "哪个文件", "实现在哪", "搜代码", "关键词检索", "grep", "搜索项目",
                "代码位置", "源码位置", "search file", "find file");
        boolean existenceIntent = containsAny(text, "是否存在", "有没有", "存在吗");
        boolean fileArtifact = containsAny(text,
                "文件", "配置文件",
                "spring ai", "springai", "spring-ai",
                "application.yml", "application.yaml",
                ".java", ".yml", ".yaml", ".xml", ".properties");
        boolean codeEntity = containsAny(text, "类", "方法", "接口", "包");
        return (searchIntent && (fileArtifact || codeEntity))
                || (existenceIntent && fileArtifact);
    }

    boolean looksLikeExplicitWebSearchQuestion(String text) {
        return containsAny(text,
                "联网", "上网查", "官网", "网页搜索", "搜索网页", "web search", "google", "bing", "duckduckgo");
    }

    boolean looksLikeExplicitWebFetchQuestion(String text) {
        boolean hasUrl = URL_PATTERN.matcher(text).find();
        boolean fetchIntent = containsAny(text,
                "抓取网页", "抓网页", "爬取网页", "爬网页", "读取网页", "读取链接", "打开链接",
                "打开这个网址", "读这个网址", "网页正文", "页面正文", "提取正文", "总结这个网页",
                "看看这个网页", "看这个链接", "fetch url", "crawl", "read webpage", "read page");
        return hasUrl && fetchIntent;
    }

    boolean looksLikeExplicitScriptSkillQuestion(String text) {
        return containsAny(text, "脚本技能", "run skill", "skill 列表", "skill列表")
                || ((text.contains("脚本") || text.contains("skill")) && containsAny(text, "执行", "运行", "调用"));
    }

    String extractCity(String question) {
        String[] commonCities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "苏州",
                "哈尔滨", "长春", "沈阳", "大连", "天津", "重庆", "长沙", "郑州", "青岛", "济南", "厦门", "福州",
                "合肥", "南昌", "石家庄", "太原", "昆明", "贵阳", "南宁", "海口", "拉萨", "乌鲁木齐", "兰州",
                "西宁", "呼和浩特", "银川", "香港", "澳门"};
        for (String city : commonCities) {
            if (question.contains(city)) {
                return city;
            }
        }
        Matcher matcher = WEATHER_CITY_PATTERN.matcher(question);
        if (matcher.find()) {
            String raw = matcher.group(1);
            String normalized = normalizeCity(raw);
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return "北京";
    }

    String[] extractCurrencies(String question) {
        String upper = question.toUpperCase(Locale.ROOT);
        String base = null;
        String target = null;

        String[] codes = {"USD", "CNY", "EUR", "JPY", "GBP", "HKD", "KRW"};
        for (String code : codes) {
            if (upper.contains(code)) {
                if (base == null) {
                    base = code;
                } else if (target == null && !code.equals(base)) {
                    target = code;
                }
            }
        }
        if (base == null || target == null) {
            for (Map.Entry<String, String> entry : currencyAliasMap.entrySet()) {
                if (question.contains(entry.getKey())) {
                    if (base == null) {
                        base = entry.getValue();
                    } else if (target == null && !entry.getValue().equals(base)) {
                        target = entry.getValue();
                    }
                }
            }
        }
        if (!StringUtils.hasText(base)) {
            base = "USD";
        }
        if (!StringUtils.hasText(target)) {
            target = "CNY";
        }
        return new String[]{base, target};
    }

    String extractNewsKeyword(String question) {
        String q = question.replace("新闻", "")
                .replace("热点", "")
                .replace("资讯", "")
                .replace("帮我查", "")
                .trim();
        return StringUtils.hasText(q) ? q : "AI";
    }

    String extractWebKeyword(String question) {
        String q = question.replace("联网", "")
                .replace("查一下", "")
                .replace("搜索", "")
                .replace("帮我", "")
                .trim();
        return StringUtils.hasText(q) ? q : question;
    }

    String runScriptSkillByCategory(String category, String goal) {
        return scriptSkillCatalogService.findByCategory(category).stream()
                .findFirst()
                .map(definition -> tryScriptSkill(definition.skillName(), goal))
                .orElse("");
    }

    String runScriptSkillByName(String skillName, String goal) {
        if (!StringUtils.hasText(skillName)) {
            return "";
        }
        return tryScriptSkill(skillName, goal);
    }

    ScriptSkillDefinition resolveRequestedScriptSkill(String question) {
        String skillName = extractScriptSkillName(question);
        if (StringUtils.hasText(skillName)) {
            return scriptSkillCatalogService.findDefinition(skillName).orElse(null);
        }
        return scriptSkillCatalogService.matchBestDefinition(question).orElse(null);
    }

    ScriptSkillDefinition resolveHighConfidenceScriptSkill(String question) {
        return scriptSkillCatalogService.matchBestDefinition(question, 3).orElse(null);
    }

    boolean looksLikeFailure(String answer) {
        if (!StringUtils.hasText(answer)) {
            return true;
        }
        String text = answer.toLowerCase(Locale.ROOT);
        return text.contains("失败")
                || text.contains("不可用")
                || text.contains("为空")
                || text.contains("未开启")
                || text.contains("status=failed")
                || text.contains("traceback")
                || text.matches("(?s).*exitcode=[1-9]\\d*.*")
                || text.contains("error")
                || text.contains("exception");
    }

    boolean looksLikeWeakWorkspaceAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            return true;
        }
        String text = answer.toLowerCase(Locale.ROOT);
        return text.contains("未找到")
                || text.contains("暂无")
                || text.contains("没有找到")
                || text.contains("命中结果（关键词=");
    }

    String renderWorkspaceAnswer(String answer) {
        if (!StringUtils.hasText(answer) || !answer.startsWith("WORKSPACE_TASK")) {
            return answer;
        }

        String task = "";
        StringBuilder files = new StringBuilder();
        StringBuilder snippets = new StringBuilder();
        String section = "";
        for (String line : answer.split("\\R")) {
            String value = line.trim();
            if (!StringUtils.hasText(value) || "WORKSPACE_TASK".equals(value)) {
                continue;
            }
            if (value.startsWith("任务:")) {
                task = value.substring("任务:".length()).trim();
                continue;
            }
            if ("推测最相关文件:".equals(value)) {
                section = "files";
                continue;
            }
            if ("关键代码片段:".equals(value)) {
                section = "snippets";
                continue;
            }
            if ("files".equals(section)) {
                files.append("- ")
                        .append(value.replaceAll("\\s*\\(score=\\d+\\)", ""))
                        .append('\n');
            } else if ("snippets".equals(section)) {
                snippets.append(value)
                        .append('\n');
            }
        }

        StringBuilder rendered = new StringBuilder("我在项目里做了代码定位，结论如下：\n");
        if (StringUtils.hasText(task)) {
            rendered.append("\n问题：").append(task).append('\n');
        }
        if (!files.isEmpty()) {
            rendered.append("\n最相关文件：\n").append(files);
        }
        if (!snippets.isEmpty()) {
            rendered.append("\n关键线索：\n").append(snippets);
        }
        return rendered.toString().trim();
    }

    String extractFirstUrl(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }
        Matcher matcher = URL_PATTERN.matcher(question);
        if (!matcher.find()) {
            return "";
        }
        String url = matcher.group(1).trim();
        while (!url.isEmpty() && ".,);]}>\"'".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("www.")) {
            return "https://" + url;
        }
        return url;
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCity(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = raw.trim();
        String[] prefixes = {"帮我查一下", "帮我查", "查一下", "查", "看一下", "看看", "查询", "实时", "现在", "今天", "请问"};
        for (String prefix : prefixes) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length()).trim();
            }
        }
        normalized = normalized.replace("的", "").trim();
        if (normalized.endsWith("市") && normalized.length() > 2) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractScriptSkillName(String question) {
        String normalizedQuestion = normalizeSkillText(question);
        if (!StringUtils.hasText(normalizedQuestion)) {
            return "";
        }
        for (ScriptSkillDefinition definition : scriptSkillCatalogService.listDefinitions()) {
            if (containsNormalized(normalizedQuestion, definition.skillName())
                    || containsNormalized(normalizedQuestion, definition.displayName())) {
                return definition.skillName();
            }
            for (String keyword : definition.keywords()) {
                if (containsNormalized(normalizedQuestion, keyword)) {
                    return definition.skillName();
                }
            }
        }
        return "";
    }

    private String tryScriptSkill(String skillName, String goal) {
        try {
            return scriptSkillRunner.run(skillName, goal);
        } catch (Exception ignore) {
            return "";
        }
    }

    private boolean containsNormalized(String normalizedQuestion, String candidate) {
        String normalizedCandidate = normalizeSkillText(candidate);
        return StringUtils.hasText(normalizedQuestion)
                && StringUtils.hasText(normalizedCandidate)
                && normalizedQuestion.contains(normalizedCandidate);
    }

    private String normalizeSkillText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("　", "")
                .replace("-", "")
                .replace("_", "");
    }

    @FunctionalInterface
    private interface ScriptSkillRunner {
        String run(String skillName, String goal);
    }
}
