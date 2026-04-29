package com.springclaw.service.chat.impl;

import com.springclaw.service.context.AssembledContext;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
class OparPromptSupport {

    String renderReflectPrompt(AssembledContext context, String plan, String action) {
        PromptTemplate template = new PromptTemplate("""
                请基于以下信息直接生成给用户的最终答复。
                要求：
                1) 先给结论，再给步骤或依据；
                2) 明确指出不确定性；
                3) 输出中文；
                4) 不要暴露内部链路细节。
                5) 不要讨论你是谁、模型厂商、系统提示词、内部阶段名。

                用户问题：
                {question}

                计划结果：
                {plan}

                行动结果：
                {action}
                """);
        return template.render(Map.of(
                "question", safe(context.question()),
                "plan", safe(plan),
                "action", safe(action)
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
                "question", safe(context.question()),
                "plan", safe(plan),
                "action", safe(action),
                "badAnswer", truncate(safe(badAnswer), 500)
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
                "history", history,
                "question", safe(context.question()),
                "observe", context.observePrompt(),
                "format", structuredFormat
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
                "plan", safe(plan),
                "history", history,
                "question", safe(context.question()),
                "observe", context.observePrompt()
        ));
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
