package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.context.AssembledContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
class OparContextAwareSupport {

    private static final DateTimeFormatter HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss");
    /** 确认性词语：用户回复"好/是的/可以"等表示同意上一轮提议 */
    private static final List<String> CONFIRMATION_WORDS = List.of(
            "好", "好的", "是的", "可以", "对", "没错", "确认", "同意",
            "ok", "yes", "就这个", "这个", "打开它", "打开吧", "就它", "选这个", "第一份", "第一个"
    );
    /** 从 assistant 回答中提取文件名的正则：匹配表格行或纯文本中的文件名。
     *  编号列表和 [F] 标记分组用 [^\n。，；|]+ 而非 [^\n]+，
     *  防止贪婪捕获文件名后的附加文本（如"。文件路径：..."或"，格式：..."）。
     *  文件名不会包含句号、逗号、分号或管道符。
     */
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "(?:\\|\\s*\\d+\\s*\\|\\s*\\S+\\s*\\|\\s*)([^|\\n]+?)(?:\\s*\\|)" // 表格第三列
            + "|(?:\\d+\\.\\s*)([^\\n。，；|]+)"  // 编号列表（到句号/逗号/分号/管道截止）
            + "|(?:\\[F\\]\\s*)([^\\n。，；|]+)"   // [F] 文件标记（同上）
    );

    private final ConversationHistoryService conversationHistoryService;
    private final MessageEventService messageEventService;
    private final LocalFilesystemService localFilesystemService;

    OparContextAwareSupport(ConversationHistoryService conversationHistoryService,
                            MessageEventService messageEventService,
                            LocalFilesystemService localFilesystemService) {
        this.conversationHistoryService = conversationHistoryService;
        this.messageEventService = messageEventService;
        this.localFilesystemService = localFilesystemService;
    }

    LocalSkillFallbackService.LocalSkillResult tryContextAwareLocalResult(AssembledContext assembled) {
        if (assembled == null) {
            return null;
        }
        String lower = TextUtils.safe(assembled.question()).toLowerCase();
        // === 确认词承接：用户回复"好/是的"时，尝试承接上一轮文件候选 ===
        LocalSkillFallbackService.LocalSkillResult confirmationResult = tryFileConfirmationFollowUp(assembled, lower);
        if (confirmationResult != null) {
            return confirmationResult;
        }
        if (looksLikeFirstMessageTimeQuestion(lower)) {
            return conversationHistoryService.findFirstUserQuestionEntry(assembled.sessionKey())
                    .map(entry -> buildHistoryTimeResult(
                            "SESSION_FIRST_MESSAGE_TIME_QUERY",
                            "当前会话里，你最开始那条用户消息发送时间是：%s。内容：%s"
                                    .formatted(formatTime(entry.createdAt()), entry.question()),
                            "你最开始那条消息发送于 %s。".formatted(formatTime(entry.createdAt()))
                    ))
                    .orElse(new LocalSkillFallbackService.LocalSkillResult(
                            "SESSION_FIRST_MESSAGE_TIME_QUERY",
                            "当前还没有查到可用的第一条用户消息时间记录。",
                            "我现在还没查到这个会话里可用的第一条用户消息时间。",
                            false
                    ));
        }
        if (looksLikePreviousMessageTimeQuestion(lower)) {
            return conversationHistoryService.findLatestUserQuestionEntry(assembled.sessionKey())
                    .map(entry -> buildHistoryTimeResult(
                            "SESSION_PREVIOUS_MESSAGE_TIME_QUERY",
                            "当前会话里，你上一条用户消息发送时间是：%s。内容：%s"
                                    .formatted(formatTime(entry.createdAt()), entry.question()),
                            "你上一条消息发送于 %s。".formatted(formatTime(entry.createdAt()))
                    ))
                    .orElse(new LocalSkillFallbackService.LocalSkillResult(
                            "SESSION_PREVIOUS_MESSAGE_TIME_QUERY",
                            "当前还没有查到可用的上一条用户消息时间记录。",
                            "我现在还没查到这个会话里可用的上一条用户消息时间。",
                            false
                    ));
        }
        if (looksLikeFirstMessageQuestion(lower)) {
            String firstQuestion = conversationHistoryService.findFirstUserQuestion(assembled.sessionKey()).orElse("");
            if (StringUtils.hasText(firstQuestion)) {
                String detail = "当前会话里，你最开始问我的一句话是：" + firstQuestion;
                String shortAnswer = "你在这个会话里最开始问我的是：" + firstQuestion;
                return new LocalSkillFallbackService.LocalSkillResult("SESSION_FIRST_MESSAGE_QUERY", detail, shortAnswer, false);
            }
            return new LocalSkillFallbackService.LocalSkillResult(
                    "SESSION_FIRST_MESSAGE_QUERY",
                    "当前还没有查到可用的历史用户消息记录。",
                    "我现在还没查到这个会话里可用的第一条用户消息记录。",
                    false
            );
        }
        if (looksLikePreviousMessageQuestion(lower)) {
            String previousQuestion = conversationHistoryService.findLatestUserQuestion(assembled.sessionKey()).orElse("");
            if (StringUtils.hasText(previousQuestion)) {
                String detail = "当前会话里，你上一条用户消息是：" + previousQuestion;
                String shortAnswer = "你上一条问我的是：" + previousQuestion;
                return new LocalSkillFallbackService.LocalSkillResult("SESSION_PREVIOUS_MESSAGE_QUERY", detail, shortAnswer, false);
            }
            return new LocalSkillFallbackService.LocalSkillResult(
                    "SESSION_PREVIOUS_MESSAGE_QUERY",
                    "当前还没有查到可用的上一条用户消息记录。",
                    "我现在还没查到这个会话里可用的上一条用户消息记录。",
                    false
            );
        }
        if (looksLikeContextMemoryQuestion(lower)) {
            String detail = renderContextMemoryDetail(assembled);
            String shortAnswer = renderContextMemoryShortAnswer(assembled);
            return new LocalSkillFallbackService.LocalSkillResult("CONTEXT_MEMORY_QUERY", detail, shortAnswer, false);
        }
        if (looksLikeDiagnosticAnalysisFollowUp(lower) && contextContainsDiagnosticMaterial(assembled.eventContext())) {
            String detail = renderDiagnosticAnalysis(assembled.eventContext());
            return new LocalSkillFallbackService.LocalSkillResult("RECENT_DIAGNOSTIC_ANALYSIS", detail, detail, false);
        }
        if (looksLikeRecentFailureFollowUp(lower) && contextContainsRecentFailure(assembled.eventContext())) {
            String detail = renderRecentFailureExplanation(assembled.eventContext());
            return new LocalSkillFallbackService.LocalSkillResult("RECENT_FAILURE_QUERY", detail, detail, false);
        }
        return null;
    }

    private boolean looksLikeContextMemoryQuestion(String lower) {
        return lower.contains("上下文记忆")
                || lower.contains("记忆有多少")
                || lower.contains("你记得多少")
                || lower.contains("你能记住么")
                || lower.contains("你能记住吗")
                || lower.contains("你记得住吗")
                || lower.contains("你会记住吗")
                || lower.contains("你能记住什么")
                || lower.contains("你会记住什么")
                || lower.contains("上下文有多少");
    }

    private boolean looksLikeFirstMessageQuestion(String lower) {
        return lower.contains("第一条消息")
                || lower.contains("第一个消息")
                || lower.contains("第一句话")
                || lower.contains("最开始问")
                || lower.contains("第一个问题")
                || lower.contains("第一条问")
                || lower.contains("最早那条消息");
    }

    private boolean looksLikePreviousMessageQuestion(String lower) {
        return lower.contains("上一条消息")
                || lower.contains("上一句话")
                || lower.contains("上一个问题")
                || lower.contains("刚才问")
                || lower.contains("上一条问")
                || lower.contains("前一条消息");
    }

    private boolean looksLikeFirstMessageTimeQuestion(String lower) {
        return lower.contains("第一条消息什么时候")
                || lower.contains("第一个消息什么时候")
                || lower.contains("第一句话什么时候")
                || lower.contains("最开始问我的那句是什么时候")
                || lower.contains("第一条消息时间")
                || (lower.contains("第一条消息") && lower.contains("什么时候"))
                || (lower.contains("第一句话") && lower.contains("什么时候"));
    }

    private boolean looksLikePreviousMessageTimeQuestion(String lower) {
        return lower.contains("上一条消息什么时候")
                || lower.contains("上一句话什么时候")
                || lower.contains("上一条问是什么时候")
                || lower.contains("我是什么时候发的")
                || lower.contains("我刚才什么时候发的")
                || lower.contains("上一条消息时间")
                || ((lower.contains("上一条消息") || lower.contains("上一句话")) && lower.contains("什么时候"));
    }

    private boolean looksLikeRecentFailureFollowUp(String lower) {
        return lower.equals("怎么回事")
                || lower.equals("怎么回事？")
                || lower.equals("为什么会这样")
                || lower.equals("为什么")
                || lower.equals("啥情况")
                || lower.equals("什么情况")
                || lower.equals("现在呢")
                || lower.equals("现在呢？")
                || lower.equals("恢复了吗")
                || lower.equals("恢复了么")
                || lower.equals("还不行吗")
                || lower.equals("现在可以了吗");
    }

    private boolean looksLikeDiagnosticAnalysisFollowUp(String lower) {
        return lower.equals("分析一下")
                || lower.equals("分析一下他")
                || lower.equals("分析分析")
                || lower.equals("分析分析他")
                || lower.equals("分析下")
                || lower.equals("分析下他")
                || lower.equals("帮我分析")
                || lower.equals("帮我分析下")
                || lower.equals("帮我看看")
                || lower.equals("用代码分析")
                || lower.equals("用代码分析下")
                || lower.equals("用代码分析一下")
                || lower.equals("用代码分析分析他")
                || lower.equals("看看这个报错")
                || lower.equals("看看这个错误")
                || lower.equals("看看这段日志")
                || lower.equals("分析这段日志")
                || lower.equals("分析这个报错");
    }

    private boolean contextContainsRecentFailure(String eventContext) {
        String lower = TextUtils.safe(eventContext).toLowerCase();
        return lower.contains("模型服务当前不可用")
                || lower.contains("504")
                || lower.contains("503")
                || lower.contains("502")
                || lower.contains("read timed out")
                || lower.contains("sockettimeoutexception")
                || lower.contains("unexpected end of file")
                || lower.contains("请求超时");
    }

    private boolean contextContainsDiagnosticMaterial(String eventContext) {
        String lower = TextUtils.safe(eventContext).toLowerCase();
        return lower.contains("read timed out")
                || lower.contains("sockettimeoutexception")
                || lower.contains("unexpected end of file")
                || lower.contains("caused by:")
                || lower.contains("exception")
                || lower.contains("error")
                || lower.contains("tool=")
                || lower.contains("status=success")
                || lower.contains("status=failed")
                || lower.contains(".java:")
                || lower.contains("==> preparing:")
                || lower.contains("sqlsession")
                || lower.contains("dispatcherservlet");
    }

    private String renderContextMemoryDetail(AssembledContext assembled) {
        int recentEventCount = countContextItems(assembled.eventContext(), "（暂无短期事件流）");
        int semanticMemoryCount = countContextItems(assembled.semanticContext(), "（暂无长期语义记忆）");
        long persistedUserQuestionCount = conversationHistoryService.countRememberedUserQuestions(assembled.sessionKey());
        return """
                当前会话上下文状态
                短期事件流条目: %d
                长期语义记忆条目: %d
                已持久化用户消息条目: %d
                说明: 我会优先结合当前会话最近事件，再参考同会话/同用户的语义记忆。
                """.formatted(recentEventCount, semanticMemoryCount, persistedUserQuestionCount).trim();
    }

    private String renderContextMemoryShortAnswer(AssembledContext assembled) {
        int recentEventCount = countContextItems(assembled.eventContext(), "（暂无短期事件流）");
        int semanticMemoryCount = countContextItems(assembled.semanticContext(), "（暂无长期语义记忆）");
        long persistedUserQuestionCount = conversationHistoryService.countRememberedUserQuestions(assembled.sessionKey());
        return "我当前会结合 %d 条短期事件流、%d 条长期语义记忆，并可查询这个会话里已持久化的 %d 条用户消息。"
                .formatted(recentEventCount, semanticMemoryCount, persistedUserQuestionCount);
    }

    private int countContextItems(String context, String emptyMarker) {
        String safeText = TextUtils.safe(context);
        if (!StringUtils.hasText(safeText) || safeText.contains(emptyMarker)) {
            return 0;
        }
        return (int) safeText.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .count();
    }

    private String renderRecentFailureExplanation(String eventContext) {
        String lower = TextUtils.safe(eventContext).toLowerCase();
        if (lower.contains("504") || lower.contains("请求超时") || lower.contains("read timed out")) {
            return "刚才是上游模型超时了。本地服务、飞书和数据库链路都正常，卡在远程模型响应。";
        }
        if (lower.contains("503")) {
            return "刚才是上游模型暂时不可用，不是本地服务故障。";
        }
        if (lower.contains("502")) {
            return "刚才是上游模型网关返回了 502。项目主服务还在，问题在模型接入链路。";
        }
        if (lower.contains("unexpected end of file")) {
            return "刚才是模型服务在返回过程中断开了连接，问题在上游模型链路。";
        }
        return "刚才最近一次远程模型调用失败了，更像是模型接入链路故障，不是本地服务故障。";
    }

    private String renderDiagnosticAnalysis(String eventContext) {
        String lower = TextUtils.safe(eventContext).toLowerCase();
        boolean remoteTimeout = lower.contains("read timed out") || lower.contains("sockettimeoutexception");
        boolean toolSucceeded = lower.contains("tool=") && lower.contains("status=success");
        boolean toolContextMissing = lower.contains("tool-session") || lower.contains("tool-user");

        if (remoteTimeout && toolSucceeded) {
            String suffix = toolContextMissing
                    ? "另外，工具审计里出现了 tool-session/tool-user，这说明当时工具调用没有绑定真实会话上下文，已经需要在简化模式里补上。"
                    : "";
            return "从最近日志看，这次不是工具没执行，而是工具执行成功后，远程模型在整理最终回答时响应超时。"
                    + " 本地数据库、飞书链路和工具调用本身是通的，真正故障点在上游模型返回阶段。"
                    + suffix;
        }
        if (remoteTimeout) {
            return "从最近日志看，核心问题是上游模型响应超时。数据库查询、消息入库和主服务线程都还在，失败点在远程模型响应阶段。";
        }
        if (lower.contains("sqlsession") && !lower.contains("exception")) {
            return "这些 SqlSession 日志本身不是故障，只是在提示当前读写没有放进 Spring 事务同步。真正要看的还是后面有没有超时、异常堆栈或模型调用失败。";
        }
        if (lower.contains("caused by:") || lower.contains("exception") || lower.contains("error")) {
            return "从最近上下文看，这是一段异常/堆栈日志，真正根因要看最靠后的 Caused by 或第一个业务异常，而不是前面的框架调用栈。";
        }
        return "从最近上下文看，这是一次代码/日志诊断类追问。当前能确认是最近链路里出现了异常或工具执行记录，优先应先看最近失败原因和工具真实输出，再决定是否继续调用模型。";
    }

    private LocalSkillFallbackService.LocalSkillResult buildHistoryTimeResult(String route, String detail, String shortAnswer) {
        return new LocalSkillFallbackService.LocalSkillResult(route, detail, shortAnswer, false);
    }

    private String formatTime(LocalDateTime time) {
        if (time == null) {
            return "未知时间";
        }
        return HISTORY_TIME_FORMATTER.format(time);
    }

    // === 确认词承接逻辑 ===

    /**
     * 对话级 pending intent 承接：当用户回复确认性词语（好/是的/可以等），
     * 尝试从上一轮 assistant 回答中提取文件候选，并承接打开/读取操作。
     *
     * 这不是系统级 action proposal，而是普通多轮槽位确认。
     * 限制：只看最近 1 条 assistant 回答，最多提取 20 个文件名。
     */
    private LocalSkillFallbackService.LocalSkillResult tryFileConfirmationFollowUp(
            AssembledContext assembled, String lowerQuestion) {
        if (!looksLikeConfirmation(lowerQuestion)) {
            return null;
        }
        // 从最近 1 条 assistant 回答中提取文件候选
        List<String> fileCandidates = extractFileCandidatesFromRecentAssistant(assembled.sessionKey());
        if (fileCandidates.isEmpty()) {
            return null; // 上一轮没有文件候选，不是文件确认场景
        }
        // 检查确认词是否指向特定编号（如"第一份/第一个/第二个/2号"）
        String specificHint = extractSpecificHint(lowerQuestion);
        if (StringUtils.hasText(specificHint) && fileCandidates.size() > 1) {
            int index = parseCandidateIndex(specificHint, fileCandidates.size());
            if (index >= 0 && index < fileCandidates.size()) {
                return openSingleCandidate(fileCandidates.get(index));
            }
        }
        // 如果只有一个候选 → 直接打开
        if (fileCandidates.size() == 1) {
            return openSingleCandidate(fileCandidates.get(0));
        }
        // 多个候选 → 让用户选择编号
        String answer = "上一轮提到了多个文件，请告诉我要打开哪一份（回复编号即可）：\n\n";
        for (int i = 0; i < Math.min(fileCandidates.size(), 20); i++) {
            answer += (i + 1) + ". " + truncateFileName(fileCandidates.get(i)) + "\n";
        }
        return new LocalSkillFallbackService.LocalSkillResult(
                "FILE_CONFIRMATION_MULTIPLE", answer, answer, false);
    }

    /**
     * 确认词判断：只在短句匹配时命中。
     * 排除长句误判："这个文件在哪里"、"可以打开哪个文件"等不应被当作确认词。
     * 策略：trimmed 长度不超过确认词长度 + 2 个语气后缀字符时才命中。
     */
    private boolean looksLikeConfirmation(String lower) {
        String trimmed = lower.trim();
        for (String word : CONFIRMATION_WORDS) {
            if (trimmed.equals(word)) {
                return true;
            }
            // 允许确认词后带1-2个语气字符（如"好的呀"、"是的呢"）
            if (trimmed.startsWith(word) && trimmed.length() <= word.length() + 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从最近 1 条 ASSISTANT message_event 中提取文件名候选。
     * 只提取最近1次，最多20个文件名，单个文件名过长截断。
     *
     * 关键：直接读取 event.getContent() 原始内容，手动剥离 [REFLECT] 前缀，
     * 不调用 ConversationEventTextSupport.extractAssistantAnswer()（其中 normalize()
     * 会把换行折叠为空格，导致编号列表正则 [^\n]+ 把多个候选粘成一个脏文件名）。
     */
    private List<String> extractFileCandidatesFromRecentAssistant(String sessionKey) {
        List<MessageEvent> recentEvents = messageEventService.listSessionEvents(
                sessionKey, null, "CHAT", 4, false);
        // 从最近的开始找第一条 ASSISTANT 事件
        for (int i = recentEvents.size() - 1; i >= 0; i--) {
            MessageEvent event = recentEvents.get(i);
            if ("ASSISTANT".equalsIgnoreCase(event.getRole())) {
                String raw = event.getContent();
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // 手动剥离 [REFLECT] 前缀，保留原始换行结构
                String content = raw;
                if (content.startsWith("[REFLECT]")) {
                    content = content.substring("[REFLECT]".length()).trim();
                }
                if (!StringUtils.hasText(content)) {
                    continue;
                }
                List<String> candidates = parseFileNamesFromAnswer(content);
                if (!candidates.isEmpty()) {
                    return candidates.stream().limit(20).toList();
                }
            }
        }
        return List.of();
    }

    /**
     * 从 assistant 回答文本中提取文件名。
     * 支持表格格式（| 序号 | 类型 | 文件名 |）和编号列表（1. xxx）。
     */
    private List<String> parseFileNamesFromAnswer(String answer) {
        List<String> names = new ArrayList<>();
        Matcher matcher = FILE_NAME_PATTERN.matcher(answer);
        while (matcher.find() && names.size() < 20) {
            // 三个分组分别对应表格列、编号列表、[F]标记
            for (int g = 1; g <= 3; g++) {
                String value = matcher.group(g);
                if (StringUtils.hasText(value)) {
                    String cleaned = value.trim();
                    // 过滤掉太短或明显是类型词的（"文件"、"文件夹"）
                    if (cleaned.length() >= 3 && !"文件".equals(cleaned) && !"文件夹".equals(cleaned)) {
                        names.add(cleaned);
                    }
                }
            }
        }
        return names;
    }

    /**
     * 从确认词中提取编号提示："第一份"→"第一"，"2号"→"2"，"第二个"→"第二"。
     */
    private String extractSpecificHint(String lower) {
        if (lower.contains("第一") || lower.contains("1号") || lower.contains("1.") || lower.equals("1")) {
            return "1";
        }
        if (lower.contains("第二") || lower.contains("2号") || lower.equals("2")) {
            return "2";
        }
        if (lower.contains("第三") || lower.contains("3号") || lower.equals("3")) {
            return "3";
        }
        // 纯数字确认
        String trimmed = lower.trim();
        if (trimmed.matches("\\d+")) {
            return trimmed;
        }
        return null;
    }

    private int parseCandidateIndex(String hint, int total) {
        try {
            int num = Integer.parseInt(hint);
            if (num >= 1 && num <= total) {
                return num - 1; // 转为 0-based index
            }
        } catch (NumberFormatException ignored) {}
        // 中文数字
        if (hint.contains("第一")) return 0;
        if (hint.contains("第二")) return 1;
        if (hint.contains("第三")) return 2;
        return -1;
    }

    private LocalSkillFallbackService.LocalSkillResult openSingleCandidate(String fileName) {
        try {
            // 尝试从桌面读取文件
            DesktopFilePath filePath = resolveDesktopPathFromFileName(fileName);
            if (filePath != null) {
                String readResult = localFilesystemService.readTextFile(filePath.rootRef(), filePath.relativePath());
                String answer;
                if (readResult != null && !readResult.startsWith("找到了文件") && !readResult.startsWith("文件不存在")) {
                    answer = "我找到了文件：" + fileName + "，内容如下：\n\n" + readResult;
                } else if (readResult != null && readResult.startsWith("找到了文件")) {
                    answer = readResult; // 非文本格式的友好提示
                } else {
                    answer = "找到了文件 " + fileName + "，但无法读取内容。";
                }
                return new LocalSkillFallbackService.LocalSkillResult(
                        "FILE_CONFIRMATION_OPEN", answer, answer, true);
            }
        } catch (Exception ex) {
            return new LocalSkillFallbackService.LocalSkillResult(
                    "FILE_CONFIRMATION_OPEN_FAILED",
                    "打开文件失败: " + ex.getMessage(),
                    "打开文件失败: " + ex.getMessage(), false);
        }
        // 无法定位文件路径时，提供文件名信息
        String answer = "确认打开文件：" + truncateFileName(fileName)
                + "。但目前无法自动定位到文件路径，请提供完整路径或重新描述。";
        return new LocalSkillFallbackService.LocalSkillResult(
                "FILE_CONFIRMATION_NO_PATH", answer, answer, false);
    }

    /**
     * 从文件名尝试解析桌面路径。
     */
    private DesktopFilePath resolveDesktopPathFromFileName(String fileName) {
        try {
            String listing = localFilesystemService.listFiles("", "Desktop");
            DesktopFilePath path = findPathInListing(listing, fileName);
            if (path != null) return path;
        } catch (Exception ignored) {}
        try {
            String listing = localFilesystemService.listFiles("Desktop", "");
            DesktopFilePath path = findPathInListing(listing, fileName);
            if (path != null) return path;
        } catch (Exception ignored) {}
        return null;
    }

    private DesktopFilePath findPathInListing(String listing, String fileName) {
        for (String line : TextUtils.safe(listing).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains(fileName)) {
                int colonIdx = trimmed.indexOf(':');
                if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
                    String rootRef = trimmed.substring(0, colonIdx).replace("[F] ", "").replace("[D] ", "").trim();
                    String relativePath = trimmed.substring(colonIdx + 1).trim();
                    return new DesktopFilePath(rootRef, relativePath);
                }
            }
        }
        return null;
    }

    private String truncateFileName(String name) {
        if (name == null) return "";
        return name.length() > 80 ? name.substring(0, 80) + "..." : name;
    }

    private record DesktopFilePath(String rootRef, String relativePath) {}
}
