package com.openclaw.service.chat.impl;

import com.openclaw.service.chat.LocalSkillFallbackService;
import com.openclaw.service.context.AssembledContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
class OparContextAwareSupport {

    private static final DateTimeFormatter HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss");

    private final ConversationHistoryService conversationHistoryService;

    OparContextAwareSupport(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
    }

    LocalSkillFallbackService.LocalSkillResult tryContextAwareLocalResult(AssembledContext assembled) {
        String lower = safe(assembled.question()).toLowerCase();
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

    private boolean contextContainsRecentFailure(String eventContext) {
        String lower = safe(eventContext).toLowerCase();
        return lower.contains("模型服务当前不可用")
                || lower.contains("504")
                || lower.contains("503")
                || lower.contains("502")
                || lower.contains("unexpected end of file")
                || lower.contains("请求超时");
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
        String safeText = safe(context);
        if (!StringUtils.hasText(safeText) || safeText.contains(emptyMarker)) {
            return 0;
        }
        return (int) safeText.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .count();
    }

    private String renderRecentFailureExplanation(String eventContext) {
        String lower = safe(eventContext).toLowerCase();
        if (lower.contains("504") || lower.contains("请求超时")) {
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

    private String safe(String text) {
        return text == null ? "" : text;
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
}
