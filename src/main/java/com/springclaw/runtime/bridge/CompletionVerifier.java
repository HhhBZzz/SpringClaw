package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;

/**
 * 完成判定器：回答"这次 run 的产出是否有资格宣告成功"。
 *
 * <p>判定链由 Spring 注入的 {@code List<CompletionVerifier>} 组成，首个 supports 的判定生效。
 * 原则（dsh 参照系）：验证世界而非自我报告——判据基于可观察事实（modelEnabled/answer 内容），
 * 不基于模型自称"已完成"。此前系统无任何发射 run.completed 的路径，成功 run 全被
 * RunResultProjector 强制打成 RUN_DEGRADED(LEGACY_UNVERIFIED_RESULT)——本 SPI 修复该缺陷。</p>
 */
public interface CompletionVerifier {

    boolean supports(ChatContext context, ChatExecutionResult result, String answer);

    CompletionVerdict verify(ChatContext context, ChatExecutionResult result, String answer);
}
