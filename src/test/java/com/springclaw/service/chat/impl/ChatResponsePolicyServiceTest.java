package com.springclaw.service.chat.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponsePolicyServiceTest {

    private final ChatResponsePolicyService service = new ChatResponsePolicyService("");

    @Test
    void shouldReturnShortReplyForGreetingWhenModelIsDown() {
        String reply = service.buildUserFacingFailureReply("HTTP 504 Gateway Timeout", "在么");

        assertThat(reply).contains("在。我这边服务还在");
        assertThat(reply).contains("504");
        assertThat(reply).doesNotContain("结论");
        assertThat(reply).doesNotContain("建议：");
    }

    @Test
    void shouldReturnShortReplyForStatusFollowUpWhenModelIsDown() {
        String reply = service.buildUserFacingFailureReply("HTTP 504 Gateway Timeout", "现在呢");

        assertThat(reply).contains("本地服务还正常");
        assertThat(reply).contains("504");
    }

    @Test
    void shouldReturnPartialAnswerWhenActionAlreadyContainsUsefulResult() {
        String reply = service.buildPartialAnswerFromAction("""
                [STEP 1]
                **结论：项目中存在完整的service层架构。**
                
                ## Service层核心类信息：
                - ChatService.java
                - ChatServiceImpl.java
                """, "Read timed out");

        assertThat(reply).contains("模型总结阶段超时");
        assertThat(reply).contains("ChatServiceImpl.java");
        assertThat(reply).doesNotContain("[STEP 1]");
    }

    @Test
    void shouldIgnorePureDegradedActionTrace() {
        String reply = service.buildPartialAnswerFromAction("行动降级：工具执行失败或不可用。reason=Read timed out", "Read timed out");

        assertThat(reply).isBlank();
    }
}
