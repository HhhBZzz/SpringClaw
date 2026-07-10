package com.springclaw.service.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.util.TextUtils;
import com.springclaw.service.ai.AiProviderService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Uses the active model to translate a local-file write request into a concrete
 * FileToolPack.writeTextFile plan. The model generates task-specific content;
 * Java only validates the tool contract.
 */
@Service
public class ModelLocalFileWritePlanner implements LocalFileWritePlanner {

    private final AiProviderService aiProviderService;
    private final ModelCallExecutor modelCallExecutor;
    private final ObjectMapper objectMapper;

    public ModelLocalFileWritePlanner(AiProviderService aiProviderService,
                                      ModelCallExecutor modelCallExecutor,
                                      ObjectMapper objectMapper) {
        this.aiProviderService = aiProviderService;
        this.modelCallExecutor = modelCallExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<LocalFileWritePlan> plan(ChatContext context) {
        if (context == null || !StringUtils.hasText(context.effectiveUserMessage())) {
            return Optional.empty();
        }
        try {
            AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    activeClient,
                    "local-file-write-plan",
                    new ModelCallExecutor.ChatRequestContext(
                            context.requestId(),
                            context.session().getSessionKey(),
                            context.channel(),
                            context.userId()
                    ),
                    true,
                    client -> {
                        var response = client.chatClient().prompt()
                                .system(systemPrompt())
                                .user(userPrompt(context.effectiveUserMessage()))
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            return parsePlan(result.value(), objectMapper);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    static Optional<LocalFileWritePlan> parsePlan(String raw, ObjectMapper mapper) {
        if (!StringUtils.hasText(raw) || mapper == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            if (!node.path("supported").asBoolean(false)) {
                return Optional.empty();
            }
            String relativePath = textValue(node, "relativePath");
            if (!StringUtils.hasText(relativePath)) {
                return Optional.empty();
            }
            return Optional.of(new LocalFileWritePlan(
                    relativePath,
                    textValue(node, "content"),
                    node.path("overwrite").asBoolean(true),
                    textValue(node, "reason")
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private String systemPrompt() {
        return """
                你是 SpringClaw 的本地文件写入规划器。只输出 JSON，不要解释。

                你的任务是把用户请求转换成 FileToolPack.writeTextFile 的参数计划。
                只在用户明确要求创建、写入、保存、生成或覆盖本地文本文件时 supported=true。
                relativePath 必须是相对路径，不能是绝对路径，不能包含 ..。
                用户说“桌面”或 Desktop 时，relativePath 使用 Desktop/<文件名>。
                如果用户没有给扩展名但说“文档/文件”，根据内容选择 .txt 或 .md。
                如果用户要求你生成内容，例如笑话、祝福、摘要、清单，你需要生成可写入文件的正文。
                如果用户给出“内容是/内容为/写入”等明确内容，优先使用用户给定内容。

                输出格式：
                {"supported":true|false,"relativePath":"相对路径","content":"文件正文","overwrite":true,"reason":"一句中文原因"}
                """;
    }

    private String userPrompt(String message) {
        return "用户请求：\n" + TextUtils.safe(message).trim();
    }
}
