package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * MVP 判据：模型产出非空回答=COMPLETE；走本地技能 fallback=DEGRADE；无产出=FAIL。
 */
@Component
public class TrustedModelVerifier implements CompletionVerifier {

    @Override
    public boolean supports(ChatContext context, ChatExecutionResult result, String answer) {
        return true;
    }

    @Override
    public CompletionVerdict verify(ChatContext context, ChatExecutionResult result, String answer) {
        if (result == null || !result.modelEnabled()) {
            return new CompletionVerdict(
                    CompletionDecision.Outcome.DEGRADE,
                    "MODEL_UNAVAILABLE_LOCAL_FALLBACK",
                    "模型不可用，回答由本地技能降级路径产出，未经模型验证。"
            );
        }
        if (!StringUtils.hasText(answer)) {
            return new CompletionVerdict(
                    CompletionDecision.Outcome.FAIL,
                    "EMPTY_ANSWER",
                    "模型路径未产出任何回答内容。"
            );
        }
        return new CompletionVerdict(
                CompletionDecision.Outcome.COMPLETE,
                "MODEL_VERIFIED_ANSWER",
                "模型产出非空回答，判定为可信完成。"
        );
    }
}
