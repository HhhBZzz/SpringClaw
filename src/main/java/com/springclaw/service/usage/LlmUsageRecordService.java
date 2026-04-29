package com.springclaw.service.usage;

import com.baomidou.mybatisplus.extension.service.IService;
import com.springclaw.domain.entity.LlmUsageRecord;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Map;

/**
 * 大模型 token 用量记录与统计。
 */
public interface LlmUsageRecordService extends IService<LlmUsageRecord> {

    void recordChatResponse(ChatResponseContext context, ChatResponse chatResponse);

    List<LlmUsageRecord> listRecent(int limit);

    Map<String, Object> summary(int recentLimit);

    record ChatResponseContext(String requestId,
                               String sessionKey,
                               String channel,
                               String userId,
                               String providerId,
                               String fallbackModel,
                               String source) {
    }
}
