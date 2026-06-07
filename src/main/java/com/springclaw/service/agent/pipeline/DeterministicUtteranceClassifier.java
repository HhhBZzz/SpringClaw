package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.Utterance;
import com.springclaw.service.agent.lifecycle.UtteranceType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class DeterministicUtteranceClassifier implements UtteranceClassifier {

    @Override
    public Utterance classify(TurnContext context) {
        String text = context == null || context.rawInput() == null ? "" : context.rawInput().text();
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return new Utterance(UtteranceType.UNKNOWN, 0.0, "空输入，无法分类。");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (looksLikeControl(lower)) {
            return new Utterance(UtteranceType.CONTROL, 0.93, "检测到系统/时间/运行态控制类话语。");
        }
        if (looksLikeGreeting(lower)) {
            return new Utterance(UtteranceType.CHAT, 0.92, "检测到普通闲聊问候。");
        }
        if (looksLikeConfirmation(lower)) {
            return new Utterance(UtteranceType.CONFIRMATION, 0.88, "检测到确认/取消类控制反馈。");
        }
        if (looksLikeCorrection(lower)) {
            return new Utterance(UtteranceType.CORRECTION, 0.86, "检测到修正或重试类话语，禁止直接绑定为业务槽位。");
        }
        if (looksLikeNewIntent(lower)) {
            return new Utterance(UtteranceType.NEW_INTENT, 0.86, "检测到完整的新任务意图。");
        }
        if (looksLikeFollowUp(normalized, lower)) {
            return new Utterance(UtteranceType.FOLLOW_UP, 0.78, "检测到短追问或代词式延续话语。");
        }
        return new Utterance(UtteranceType.CHAT, 0.66, "未命中任务特征，按普通对话处理。");
    }

    private boolean looksLikeControl(String lower) {
        return containsAny(lower,
                "今天日期", "日期是什么", "几月几号", "今天几号", "今天是几号", "星期几",
                "现在几点", "当前时间", "系统时间", "today", "date", "time",
                "当前模型", "切换模型", "模型状态", "provider", "token", "用量", "运行状态");
    }

    private boolean looksLikeGreeting(String lower) {
        return equalsAny(lower, "你好", "您好", "hi", "hello", "在吗", "在么", "早", "早上好", "晚上好");
    }

    private boolean looksLikeConfirmation(String lower) {
        return equalsAny(lower, "好", "好的", "可以", "确认", "继续", "取消", "不用", "不需要", "算了", "ok", "yes", "no");
    }

    private boolean looksLikeCorrection(String lower) {
        return containsAny(lower, "重新", "重试", "再试一次", "重新查", "重新找", "重新查询", "刚才不是", "改成");
    }

    private boolean looksLikeFollowUp(String normalized, String lower) {
        if (normalized.length() > 16) {
            return false;
        }
        if (containsAny(lower, "为什么", "怎么回事", "原因", "依据", "来源", "详细", "展开")) {
            return false;
        }
        if (normalized.endsWith("呢") || normalized.endsWith("吗")) {
            return true;
        }
        return normalized.matches("^(那|那么|还有|另外|顺便|换成|也查|再查|下一个).{0,12}$")
                || normalized.matches("^[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z·.\\-]{0,11}$");
    }

    private boolean looksLikeNewIntent(String lower) {
        return containsAny(lower,
                "帮我", "请", "查询", "查一下", "搜索", "分析", "审查", "生成", "检查",
                "总结", "写", "创建", "执行", "运行", "怎样", "怎么样", "如何", "多少",
                "是什么", "有什么", "打开", "看看", "看一下", "文件", "项目", "代码",
                "skill", "tool", "agent");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean equalsAny(String text, String... values) {
        for (String value : values) {
            if (text.equals(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
