package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 共享的本地兜底执行支持。
 * <p>
 * 提供对 {@link LocalSkillFallbackService} 的统一访问入口，
 * 各引擎通过 {@code enabled} 参数控制是否执行，保持各自原有的守卫语义。
 * <p>
 * 编排逻辑（调用顺序、门控条件）仍保留在各引擎内部，本类只封装单个方法体。
 */
@Service
public class LocalExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutionSupport.class);

    private final LocalSkillFallbackService fallbackService;
    private final LocalExecutionNarrator narrator;
    private final AiProviderService aiProviderService;
    private final ModelTransportGuardService modelTransportGuardService;

    public LocalExecutionSupport(LocalSkillFallbackService fallbackService,
                                  LocalExecutionNarrator narrator,
                                  AiProviderService aiProviderService,
                                  ModelTransportGuardService modelTransportGuardService) {
        this.fallbackService = fallbackService;
        this.narrator = narrator;
        this.aiProviderService = aiProviderService;
        this.modelTransportGuardService = modelTransportGuardService;
    }

    /**
     * 尝试控制面本地执行（模型切换、状态查询等）。
     *
     * @param enabled 由调用方引擎决定是否启用（OparLoopEngine 传 localFallbackEnabled，SimplifiedOparEngine 传 true）
     */
    public LocalSkillFallbackService.LocalSkillResult tryControlPlane(String question, boolean enabled) {
        if (!enabled) return null;
        try {
            return fallbackService.tryHandleControlPlane(question).orElse(null);
        } catch (Exception ex) {
            log.warn("控制面本地执行失败，reason={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 尝试完整结构化本地兜底（天气、汇率、新闻、工作区分析等）。
     */
    public LocalSkillFallbackService.LocalSkillResult tryFallback(String question, boolean enabled) {
        if (!enabled) return null;
        try {
            return fallbackService.tryHandleStructured(question).orElse(null);
        } catch (Exception ex) {
            log.warn("本地技能兜底失败，reason={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 尝试高置信度结构化技能（本地文件浏览 + 高置信度内建技能）。
     */
    public LocalSkillFallbackService.LocalSkillResult tryPriorityStructured(String question, boolean enabled) {
        if (!enabled) return null;
        try {
            return fallbackService.tryHandlePriorityStructured(question).orElse(null);
        } catch (Exception ex) {
            log.warn("高置信度结构化技能执行失败，reason={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 将确定性本地执行结果通过模型翻译为更自然的对外答复。
     * 委托给已存在的 {@link LocalExecutionNarrator}。
     */
    public String narrate(String systemPrompt,
                           AssembledContext assembled,
                           LocalSkillFallbackService.LocalSkillResult localResult) {
        AiProviderService.ActiveChatClient client = aiProviderService.activeClient();
        return narrator.narrate(
                systemPrompt,
                assembled,
                localResult,
                client,
                modelTransportGuardService.isModelCallEnabled(client)
        );
    }
}
