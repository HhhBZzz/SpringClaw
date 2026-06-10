package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.context.AssembledContext;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
class OparPromptSupport {

    String renderReflectPrompt(AssembledContext context, String plan, String action) {
        PromptTemplate template = new PromptTemplate("""
                请基于以下信息，直接给用户一个自然、简洁、有帮助的答复。
                
                输出规范（严格遵守）：
                1) 用自然语言对话，不要复制粘贴原始数据行
                2) 不要加"结论："、"依据："等标题前缀，直接回答
                3) 如果是价格查询，用一句话总结：品种 + 当前价格（USD/CNY）+ 涨跌幅
                   例如：比特币当前约 $61,803 USD / ¥419,942 CNY，24h 下跌 2.71%
                4) 如果是天气查询，用一句话总结：城市 + 天气 + 温度 + 体感建议
                   例如：北京现在局部多云，19.6℃，湿度 63%，比较舒适
                5) 如果是分析/审查类，先说结论要点，再展开
                6) 如果证据不足，简要说明并建议怎么改问
                7) 绝对不要出现：工具名、执行路径、exitCode、qualityScore、verification、来源API名
                8) 绝对不要出现"结论："、"依据："、"执行状态："这类报告标题
                9) 中文输出，简洁直接

                用户问题：
                {question}

                计划结果：
                {plan}

                行动结果（仅供参考，不要复制原始格式）：
                {action}
                """);
        return template.render(Map.of(
                "question", TextUtils.safe(context == null ? null : context.question()),
                "plan", TextUtils.safe(plan),
                "action", TextUtils.safe(action)
        ));
    }

    String renderMetaRepairPrompt(AssembledContext context, String plan, String action, String badAnswer) {
        PromptTemplate template = new PromptTemplate("""
                你上一版回答包含了与任务无关的“身份/系统/阶段”内容，请重写。
                重写要求：
                1) 只回答用户问题本身；
                2) 禁止出现 Claude、Anthropic、Reflect、系统指令、不能扮演/不能执行等表述；
                3) 保持中文、结论先行、简洁可执行。

                用户问题：
                {question}

                计划结果：
                {plan}

                行动结果：
                {action}

                上一版无效回答（仅供修正参考）：
                {badAnswer}
                """);
        return template.render(Map.of(
                "question", TextUtils.safe(context == null ? null : context.question()),
                "plan", TextUtils.safe(plan),
                "action", TextUtils.safe(action),
                "badAnswer", TextUtils.truncate(TextUtils.safe(badAnswer), 500)
        ));
    }

    String renderPlanPrompt(AssembledContext context, String history, int stepNo, String structuredFormat) {
        PromptTemplate template = new PromptTemplate("""
                你是 Agent Planner，请根据当前问题、会话历史和已知上下文，判断是否已经可以回答，或是否需要继续行动。
                要求：
                1) 简单问候、解释型问题、无需查证的问题，优先直接 READY；
                2) 只有在确实需要工具、检索或实时信息时才 CONTINUE；
                3) 总结 1-3 个最小必要步骤；
                4) toolHints 只写工具类别，不要写内部实现细节；
                5) 输出必须严格遵循下面格式说明，不能附加额外文本。

                当前步骤：
                Step {stepNo}

                历史步骤：
                {history}

                当前问题：
                {question}

                观察摘要：
                {observe}

                输出格式说明：
                {format}
                """);
        return template.render(Map.of(
                "stepNo", stepNo,
                "history", TextUtils.safe(history),
                "question", TextUtils.safe(context == null ? null : context.question()),
                "observe", TextUtils.safe(context == null ? null : context.observePrompt()),
                "format", TextUtils.safe(structuredFormat)
        ));
    }

    String renderActionPrompt(AssembledContext context, String plan, String history, int stepNo) {
        PromptTemplate template = new PromptTemplate("""
                根据以下计划执行当前步骤的行动。
                如果计划需要工具，请调用工具并基于工具结果输出行动结论；
                如果不需要工具，直接给出行动结论。
                当用户未提供明确文件路径时，优先使用 WORKSPACE 检索再决定是否读写文件。
                对于“项目里有没有某个类/配置/文件/实现”的问题，必须先用 WORKSPACE 工具找证据，再回答。
                输出只保留“本步行动结果”，不要输出额外的身份说明。

                当前步骤：
                Step {stepNo}

                计划：
                {plan}

                历史步骤：
                {history}

                当前问题：
                {question}

                观察摘要：
                {observe}
                """);
        return template.render(Map.of(
                "stepNo", stepNo,
                "plan", TextUtils.safe(plan),
                "history", TextUtils.safe(history),
                "question", TextUtils.safe(context == null ? null : context.question()),
                "observe", TextUtils.safe(context == null ? null : context.observePrompt())
        ));
    }

}
