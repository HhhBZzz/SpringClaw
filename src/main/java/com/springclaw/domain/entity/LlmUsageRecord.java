package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 大模型调用用量记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("llm_usage_record")
public class LlmUsageRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String requestId;

    private String sessionKey;

    private String channel;

    private String userId;

    private String providerId;

    private String model;

    private String source;

    /**
     * 1 表示当前记录拿到了 provider 返回的 usage；0 表示本次调用没有 usage 细节。
     */
    private Integer usageKnown;

    private Integer promptTokens;

    private Integer promptCacheHitTokens;

    private Integer promptCacheMissTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /**
     * Provider-native usage metadata, kept for model-specific billing/debug fields.
     */
    private String rawUsageJson;
}
