package com.springclaw.service.chat;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.tool.pack.SystemToolPack;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

final class LocalSkillModelControlSupport {

    private final SystemToolPack systemToolPack;
    private final AiProviderService aiProviderService;

    LocalSkillModelControlSupport(SystemToolPack systemToolPack,
                                  AiProviderService aiProviderService) {
        this.systemToolPack = systemToolPack;
        this.aiProviderService = aiProviderService;
    }

    Optional<LocalSkillFallbackService.LocalSkillResult> tryHandleControlPlane(String question) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }

        String q = question.trim();
        String lower = q.toLowerCase(Locale.ROOT);

        if (looksLikeDetailedModelProviderQuestion(lower)) {
            String detail = renderActiveProviderDetail();
            return localResult("MODEL_PROVIDER_DETAIL", detail, detail, true);
        }
        if (looksLikeModelAvailabilityQuestion(lower)) {
            String answer = renderModelAvailabilityAnswer(q);
            return localResult("MODEL_AVAILABILITY_QUERY", answer, answer, false);
        }
        if (looksLikeModelProviderQuestion(lower)) {
            return localResult("MODEL_PROVIDER_QUERY", renderActiveProviderSummary(), renderActiveProviderShortAnswer(), false);
        }
        if (looksLikeModelSwitchQuestion(lower)) {
            String detail = switchProviderByChat(lower);
            String fallback = simplifyModelSwitchAnswer(detail);
            return localResult("MODEL_PROVIDER_SWITCH", detail, fallback, false);
        }
        if (containsAny(lower, "现在几点", "当前时间", "几点了", "现在时间", "现在是什么时候", "现在几点了", "time now", "what time")) {
            String answer = systemToolPack.now();
            return localResult("SYSTEM_TIME", answer, answer, false);
        }
        if (looksLikeJvmStatusQuestion(lower)) {
            boolean preferArgs = containsAny(lower, "参数", "启动", "input", "jps -v", "jinfo", "-x", "-d");
            if (preferArgs) {
                String info = systemToolPack.jvmInputArguments();
                return localResult("SYSTEM_JVM_ARGS", info, info, true);
            }
            String jvmInfo = systemToolPack.jvmInfo();
            String args = systemToolPack.jvmInputArguments();
            String detail = jvmInfo + "\n\nJVM 启动参数：\n" + args;
            return localResult("SYSTEM_JVM_INFO", detail, detail, true);
        }
        if (containsAny(lower, "uuid", "唯一id")) {
            String answer = systemToolPack.uuid();
            return localResult("SYSTEM_UUID", answer, answer, false);
        }
        if (looksLikeExplicitCommandIntent(lower)) {
            String commandLine = extractCommandLine(q);
            String answer = systemToolPack.runCommand(commandLine);
            return localResult("SYSTEM_COMMAND", answer, answer, true);
        }

        return Optional.empty();
    }

    boolean looksLikeDateQuestion(String text) {
        return containsAny(text,
                "今天是什么日子", "今天几号", "今天几月几号", "今天星期几", "今天周几", "今天是周几",
                "今天日期", "今天几号了", "今天多少号", "今天礼拜几",
                "现在几号", "现在日期", "现在几月几号", "现在星期几", "现在周几");
    }

    String renderTodayInfo() {
        LocalDate today = LocalDate.now();
        return "今天是 %d年%d月%d日，%s。".formatted(
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth(),
                toChineseWeekday(today.getDayOfWeek())
        );
    }

    private Optional<LocalSkillFallbackService.LocalSkillResult> localResult(String route,
                                                                             String executionDetails,
                                                                             String fallbackAnswer,
                                                                             boolean detailed) {
        return Optional.of(new LocalSkillFallbackService.LocalSkillResult(route, executionDetails, fallbackAnswer, detailed));
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeModelProviderQuestion(String text) {
        return containsAny(text,
                "当前模型", "目前模型", "目前是什么模型", "目前用什么模型", "目前是哪个模型",
                "现在用什么模型", "现在是什么模型", "用的什么模型",
                "你是什么模型", "你现在是什么模型", "你现在用什么模型", "你目前是什么模型",
                "当前provider", "当前 provider", "active provider", "当前通道",
                "当前是 claude 还是千问", "当前是 qwen 还是 claude");
    }

    private boolean looksLikeDetailedModelProviderQuestion(String text) {
        return containsAny(text,
                "模型列表", "可用模型", "列出模型", "所有模型", "全部模型",
                "provider 列表", "providers", "详细模型", "详细状态",
                "base-url", "base url", "当前provider", "当前 provider", "active provider", "当前通道");
    }

    private boolean looksLikeModelAvailabilityQuestion(String text) {
        return containsAny(text,
                "可以换什么模型", "可以更换什么模型", "可以更换为什么模型", "能换什么模型",
                "能切换什么模型", "支持什么模型", "有哪些模型可以换", "可用模型")
                || (containsAny(text, "有没有", "有没", "没有", "支持", "可用", "能用")
                && (text.contains("模型") || text.contains("千问") || text.contains("qwen")
                || text.contains("claude") || text.contains("deepseek") || text.contains("深度求索")))
                || containsAny(text, "千问3.5plus", "千问3.5 plus", "qwen3.5-plus", "qwen3.5 plus");
    }

    private boolean looksLikeModelSwitchQuestion(String text) {
        return containsModelSwitchVerb(text)
                && (resolveProviderAlias(text) != null || hasKnownModelHint(text));
    }

    private boolean looksLikeJvmStatusQuestion(String text) {
        boolean jvmDomain = containsAny(text,
                "jvm", "java 虚拟机", "jvm参数", "jvm 参数", "jvm信息", "jvm 信息",
                "启动参数", "java 参数", "input arguments", "jps -v", "jinfo", "jstat", "jmap", "gc", "堆内存");
        if (!jvmDomain) {
            return false;
        }
        boolean explanationIntent = containsAny(text, "是什么", "什么意思", "原理", "介绍", "解释");
        boolean runtimeIntent = containsAny(text,
                "当前", "现在", "查看", "看一下", "看看", "本机", "运行", "参数", "启动", "进程", "堆", "gc",
                "input", "jps", "jinfo", "jstat", "jmap");
        return !explanationIntent && runtimeIntent;
    }

    private boolean looksLikeExplicitCommandIntent(String text) {
        return containsAny(text,
                "执行命令", "运行命令", "run command", "执行 shell", "运行 shell", "执行终端命令");
    }

    private String renderActiveProviderShortAnswer() {
        if (aiProviderService == null) {
            return "未注入模型提供方服务。";
        }
        AiProviderService.ActiveChatClient active = aiProviderService.activeClient();
        return """
                我当前使用的是 %s 的 %s。
                如需查看可切换模型和详细状态，可以直接说“列出所有模型”。
                """.formatted(active.providerId(), active.model()).trim();
    }

    private String renderActiveProviderSummary() {
        if (aiProviderService == null) {
            return "当前模型状态不可用。";
        }
        AiProviderService.ActiveChatClient active = aiProviderService.activeClient();
        @SuppressWarnings("unchecked")
        var providers = (Iterable<AiProviderService.ProviderView>) aiProviderService.summary().get("providers");
        long availableCount = 0;
        for (AiProviderService.ProviderView provider : providers) {
            if (provider.available()) {
                availableCount++;
            }
        }
        return """
                当前模型状态
                当前活动 provider: %s
                当前模型: %s
                当前可用 provider 数量: %d
                """.formatted(active.providerId(), active.model(), availableCount).trim();
    }

    private String renderActiveProviderDetail() {
        if (aiProviderService == null) {
            return "未注入模型提供方服务。";
        }
        AiProviderService.ActiveChatClient active = aiProviderService.activeClient();
        @SuppressWarnings("unchecked")
        var providers = (Iterable<AiProviderService.ProviderView>) aiProviderService.summary().get("providers");
        StringBuilder builder = new StringBuilder();
        builder.append("当前模型状态\n")
                .append("当前活动 provider: ").append(active.providerId()).append('\n')
                .append("当前模型: ").append(active.model()).append('\n')
                .append("当前 base-url: ").append(active.baseUrl()).append('\n')
                .append("可切换 provider:\n");
        for (AiProviderService.ProviderView provider : providers) {
            builder.append("- ").append(provider.providerId())
                    .append(" | model=").append(provider.model())
                    .append(" | default=").append(provider.defaultModel())
                    .append(" | ").append(provider.available() ? "AVAILABLE" : "UNAVAILABLE");
            if (StringUtils.hasText(provider.unavailableReason())) {
                builder.append(" | reason=").append(provider.unavailableReason());
            }
            if (provider.active()) {
                builder.append(" | ACTIVE");
            }
            if (provider.availableModels() != null && !provider.availableModels().isEmpty()) {
                builder.append("\n  models: ").append(String.join(", ", provider.availableModels()));
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private String renderModelAvailabilityAnswer(String question) {
        if (aiProviderService == null) {
            return "当前模型状态不可用。";
        }

        String lower = question.toLowerCase(Locale.ROOT);
        if (containsAny(lower,
                "可以换什么模型", "可以更换什么模型", "可以更换为什么模型", "能换什么模型",
                "能切换什么模型", "支持什么模型", "有哪些模型可以换", "模型列表", "可用模型")) {
            return renderSwitchableModelSummary();
        }

        String preferredProvider = resolveProviderAlias(question);
        AvailableModelMatch match = findAvailableModelMatch(question, preferredProvider);
        if (match != null) {
            return "有，当前可用的 %s 支持 %s。".formatted(match.providerId(), match.modelId());
        }

        return "当前可用 provider 里没有匹配到这个模型。你可以直接说“列出所有模型”查看当前可切换列表。";
    }

    private String switchProviderByChat(String question) {
        if (aiProviderService == null) {
            return "未注入模型提供方服务，无法切换。";
        }
        ModelSwitchTarget target = resolveModelSwitchTarget(question);
        if (target == null || !StringUtils.hasText(target.providerId())) {
            return "未识别要切换的模型。可选 provider：primary / qwen / coding-plan / deepseek。";
        }
        if (target.hasExplicitModelHint() && !StringUtils.hasText(target.modelId())) {
            return "未识别到目标模型：%s。请直接说具体模型名，例如 qwen3.5-plus、qwen3-coder-plus。"
                    .formatted(target.requestedModelHint());
        }
        try {
            AiProviderService.ProviderView provider = StringUtils.hasText(target.modelId())
                    ? aiProviderService.switchActiveModel(target.providerId(), target.modelId(), "chat-local-rule")
                    : aiProviderService.switchActiveProvider(target.providerId(), "chat-local-rule");
            return """
                    已切换模型提供方。
                    provider: %s
                    model: %s
                    base-url: %s
                    available-models: %s
                    """.formatted(
                    provider.providerId(),
                    provider.model(),
                    provider.baseUrl(),
                    provider.availableModels() == null || provider.availableModels().isEmpty()
                            ? "-"
                            : String.join(", ", provider.availableModels())
            ).trim();
        } catch (BusinessException ex) {
            return "模型切换失败：%s".formatted(ex.getMessage());
        }
    }

    private String simplifyModelSwitchAnswer(String detail) {
        if (!StringUtils.hasText(detail)) {
            return "模型切换已完成。";
        }
        String provider = extractField(detail, "provider:");
        String model = extractField(detail, "model:");
        if (StringUtils.hasText(provider) && StringUtils.hasText(model)) {
            return "已切换到 %s 的 %s。".formatted(provider, model);
        }
        if (StringUtils.hasText(provider)) {
            return "已切换到 %s。".formatted(provider);
        }
        return detail.trim();
    }

    private String extractField(String detail, String prefix) {
        for (String line : detail.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private ModelSwitchTarget resolveModelSwitchTarget(String question) {
        String explicitProviderId = resolveProviderAlias(question);
        String preferredProviderId = explicitProviderId;
        if (!StringUtils.hasText(preferredProviderId) && containsAny(question.toLowerCase(Locale.ROOT), "千问")) {
            preferredProviderId = preferredQwenProvider();
        }
        String modelHint = extractModelHint(question);
        String inferredProviderId = aiProviderService.findProviderByModelHint(
                StringUtils.hasText(modelHint) ? modelHint : question,
                preferredProviderId
        );
        String providerId = StringUtils.hasText(explicitProviderId) ? explicitProviderId : inferredProviderId;
        if (!StringUtils.hasText(providerId)) {
            return null;
        }
        String modelId = aiProviderService.resolveModelId(
                providerId,
                StringUtils.hasText(modelHint) ? modelHint : question
        );
        return new ModelSwitchTarget(providerId, modelId, modelHint);
    }

    private String resolveProviderAlias(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        if (normalized.contains("codingplan") || normalized.contains("codeplan")
                || normalized.contains("阿里") || normalized.contains("通义")) {
            return "coding-plan";
        }
        if (normalized.contains("千问")) {
            return preferredQwenProvider();
        }
        if (normalized.contains("qwen")) {
            return "qwen";
        }
        if (normalized.contains("deepseek") || normalized.contains("深度求索")
                || normalized.contains("deepseekchat") || normalized.contains("deepseekreasoner")
                || normalized.equals("ds")) {
            return "deepseek";
        }
        if (normalized.contains("claude") || normalized.contains("primary")
                || normalized.contains("主模型") || normalized.contains("默认模型")) {
            return "primary";
        }
        return null;
    }

    private boolean containsModelSwitchVerb(String text) {
        return containsAny(text, "切换", "切到", "改用", "换成", "切回", "switch", "use ", "换回");
    }

    private boolean hasKnownModelHint(String text) {
        if (aiProviderService == null) {
            return false;
        }
        String modelHint = extractModelHint(text);
        return aiProviderService.hasKnownModelHint(StringUtils.hasText(modelHint) ? modelHint : text);
    }

    private String extractModelHint(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replace("切换为", " ")
                .replace("切换到", " ")
                .replace("切到", " ")
                .replace("切换", " ")
                .replace("切回", " ")
                .replace("换为", " ")
                .replace("换成", " ")
                .replace("换回", " ")
                .replace("改用", " ")
                .replace("改成", " ")
                .replace("改为", " ")
                .replace("switch to", " ")
                .replace("switch", " ")
                .replace("use", " ")
                .replace("model", " ")
                .replace("模型", " ")
                .replace("provider", " ")
                .replace("主模型", " ")
                .replace("默认模型", " ")
                .replace("primary", " ")
                .replace("claude", " ")
                .replace("coding plan", " ")
                .replace("codeplan", " ")
                .replace("阿里", " ")
                .replace("通义", " ")
                .replace("千问", " ")
                .replace("qwen", " ")
                .replace("deepseek", " ")
                .replace("深度求索", " ")
                .replace("的", " ")
                .replace("：", " ")
                .replace(":", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String preferredQwenProvider() {
        if (aiProviderService == null) {
            return "qwen";
        }
        @SuppressWarnings("unchecked")
        var providers = (Iterable<AiProviderService.ProviderView>) aiProviderService.summary().get("providers");
        boolean codingPlanAvailable = false;
        boolean qwenAvailable = false;
        for (AiProviderService.ProviderView provider : providers) {
            if ("coding-plan".equals(provider.providerId()) && provider.available()) {
                codingPlanAvailable = true;
            }
            if ("qwen".equals(provider.providerId()) && provider.available()) {
                qwenAvailable = true;
            }
        }
        if (codingPlanAvailable) {
            return "coding-plan";
        }
        if (qwenAvailable) {
            return "qwen";
        }
        return "coding-plan";
    }

    private String renderSwitchableModelSummary() {
        @SuppressWarnings("unchecked")
        var providers = (Iterable<AiProviderService.ProviderView>) aiProviderService.summary().get("providers");
        StringBuilder builder = new StringBuilder("当前可切换的模型有：\n");
        for (AiProviderService.ProviderView provider : providers) {
            if (!provider.available() || provider.availableModels() == null || provider.availableModels().isEmpty()) {
                continue;
            }
            builder.append("- ").append(provider.providerId()).append(": ")
                    .append(String.join(", ", provider.availableModels()));
            if (provider.active()) {
                builder.append("（当前使用中）");
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private AvailableModelMatch findAvailableModelMatch(String question, String preferredProvider) {
        String normalizedQuestion = normalizeModelText(question);
        if (!StringUtils.hasText(normalizedQuestion) || aiProviderService == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        var providers = (Iterable<AiProviderService.ProviderView>) aiProviderService.summary().get("providers");

        if (StringUtils.hasText(preferredProvider)) {
            for (AiProviderService.ProviderView provider : providers) {
                AvailableModelMatch match = matchProviderModel(provider, normalizedQuestion, preferredProvider);
                if (match != null) {
                    return match;
                }
            }
        }
        for (AiProviderService.ProviderView provider : providers) {
            AvailableModelMatch match = matchProviderModel(provider, normalizedQuestion, null);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AvailableModelMatch matchProviderModel(AiProviderService.ProviderView provider,
                                                   String normalizedQuestion,
                                                   String expectedProvider) {
        if (provider == null || !provider.available()) {
            return null;
        }
        if (StringUtils.hasText(expectedProvider) && !expectedProvider.equals(provider.providerId())) {
            return null;
        }
        if (provider.availableModels() == null) {
            return null;
        }
        for (String model : provider.availableModels()) {
            String normalizedModel = normalizeModelText(model);
            if (StringUtils.hasText(normalizedModel)
                    && (normalizedQuestion.contains(normalizedModel) || normalizedModel.contains(normalizedQuestion))) {
                return new AvailableModelMatch(provider.providerId(), model);
            }
            if (normalizedQuestion.contains("千问35plus") && normalizedModel.contains("qwen35plus")) {
                return new AvailableModelMatch(provider.providerId(), model);
            }
        }
        return null;
    }

    private String normalizeModelText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("　", "")
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")
                .replace("模型", "")
                .replace("吗", "")
                .replace("么", "")
                .replace("？", "")
                .replace("?", "")
                .replace("有没有", "")
                .replace("有没", "")
                .replace("没有", "")
                .replace("支持", "")
                .replace("可用", "")
                .replace("能用", "");
    }

    private String extractCommandLine(String question) {
        String q = question.replace("执行命令", "")
                .replace("运行命令", "")
                .replace("命令行", "")
                .replace("shell", "")
                .replace("command", "")
                .replace("：", " ")
                .replace(":", " ")
                .trim();
        if (!StringUtils.hasText(q)) {
            return "pwd";
        }
        return q;
    }

    private String toChineseWeekday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private record ModelSwitchTarget(String providerId, String modelId, String requestedModelHint) {
        boolean hasExplicitModelHint() {
            return StringUtils.hasText(requestedModelHint);
        }
    }

    private record AvailableModelMatch(String providerId, String modelId) {
    }
}
