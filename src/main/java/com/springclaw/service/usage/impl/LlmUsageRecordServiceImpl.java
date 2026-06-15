package com.springclaw.service.usage.impl;

import com.springclaw.common.util.TextUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.springclaw.domain.entity.LlmUsageRecord;
import com.springclaw.mapper.LlmUsageRecordMapper;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.service.usage.LlmUsageMetricsService;
import com.springclaw.service.usage.TokenCacheUsage;
import com.springclaw.service.usage.TokenCacheUsageExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 大模型 token 用量记录实现。
 */
@Service
public class LlmUsageRecordServiceImpl extends ServiceImpl<LlmUsageRecordMapper, LlmUsageRecord>
        implements LlmUsageRecordService {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecordServiceImpl.class);
    private static final int LOCAL_MAX_RECORDS = 1000;

    private final boolean dbEnabled;
    private final LlmUsageMetricsService metricsService;
    private final Deque<LlmUsageRecord> localRecords = new ArrayDeque<>();

    @Autowired
    public LlmUsageRecordServiceImpl(@Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                                     @Autowired(required = false) LlmUsageMetricsService metricsService) {
        this.dbEnabled = dbEnabled;
        this.metricsService = metricsService;
    }

    public LlmUsageRecordServiceImpl(boolean dbEnabled) {
        this(dbEnabled, null);
    }

    @Override
    public void recordChatResponse(ChatResponseContext context, ChatResponse chatResponse) {
        if (context == null || chatResponse == null) {
            return;
        }

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        Integer promptTokens = normalize(usage == null ? null : usage.getPromptTokens());
        Integer completionTokens = normalize(usage == null ? null : usage.getCompletionTokens());
        Integer totalTokens = normalize(usage == null ? null : usage.getTotalTokens());
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        TokenCacheUsage promptCacheUsage = TokenCacheUsageExtractor.extract(promptTokens, usage);
        boolean usageKnown = usage != null
                && !(usage instanceof EmptyUsage)
                && (promptTokens != null || completionTokens != null || totalTokens != null);

        LlmUsageRecord record = new LlmUsageRecord();
        record.setRequestId(TextUtils.safe(context.requestId()));
        record.setSessionKey(TextUtils.safe(context.sessionKey()));
        record.setChannel(defaultValue(context.channel(), "unknown"));
        record.setUserId(defaultValue(context.userId(), "anonymous"));
        record.setProviderId(defaultValue(context.providerId(), "unknown"));
        record.setModel(resolveModel(metadata, context.fallbackModel()));
        record.setSource(defaultValue(context.source(), "chat"));
        record.setUsageKnown(usageKnown ? 1 : 0);
        record.setPromptTokens(promptTokens);
        record.setPromptCacheHitTokens(promptCacheUsage.hitTokens());
        record.setPromptCacheMissTokens(promptCacheUsage.missTokens());
        record.setCompletionTokens(completionTokens);
        record.setTotalTokens(totalTokens);
        record.setRawUsageJson(promptCacheUsage.rawUsageJson());
        if (metricsService != null) {
            metricsService.record(record);
        }

        if (dbEnabled) {
            try {
                save(record);
                return;
            } catch (Exception ex) {
                log.warn("LLM 用量写库失败，降级本地缓存。requestId={}, reason={}", record.getRequestId(), ex.getMessage());
            }
        }

        cacheLocalRecord(record);
    }

    @Override
    public List<LlmUsageRecord> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (dbEnabled) {
            try {
                return lambdaQuery()
                        .orderByDesc(LlmUsageRecord::getId)
                        .last("limit " + safeLimit)
                        .list();
            } catch (Exception ex) {
                log.warn("LLM 用量最近记录查询失败，降级本地缓存。reason={}", ex.getMessage());
            }
        }
        synchronized (localRecords) {
            return localRecords.stream()
                    .sorted(Comparator.comparing(LlmUsageRecord::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                    .limit(safeLimit)
                    .toList();
        }
    }

    @Override
    public Map<String, Object> summary(int recentLimit) {
        int safeRecentLimit = Math.max(20, Math.min(recentLimit, 1000));
        List<LlmUsageRecord> all = loadAllRecords();
        List<LlmUsageRecord> recent = all.stream()
                .sorted(Comparator.comparing(LlmUsageRecord::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(safeRecentLimit)
                .toList();

        long totalCalls = all.size();
        long usageKnownCount = all.stream().filter(record -> record.getUsageKnown() != null && record.getUsageKnown() == 1).count();
        long totalPromptTokens = sumTokens(all, LlmUsageRecord::getPromptTokens);
        long totalPromptCacheHitTokens = sumTokens(all, LlmUsageRecord::getPromptCacheHitTokens);
        long totalPromptCacheMissTokens = sumTokens(all, LlmUsageRecord::getPromptCacheMissTokens);
        long promptCacheKnownCount = all.stream()
                .filter(record -> record.getPromptCacheHitTokens() != null || record.getPromptCacheMissTokens() != null)
                .count();
        double promptCacheHitRate = hitRate(totalPromptCacheHitTokens, totalPromptCacheMissTokens);
        String promptCacheHealth = promptCacheHealth(promptCacheKnownCount, totalPromptCacheHitTokens, totalPromptCacheMissTokens, promptCacheHitRate);
        long totalCompletionTokens = sumTokens(all, LlmUsageRecord::getCompletionTokens);
        long totalTokens = sumTokens(all, LlmUsageRecord::getTotalTokens);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", totalCalls);
        result.put("usageKnownCount", usageKnownCount);
        result.put("usageUnknownCount", Math.max(0, totalCalls - usageKnownCount));
        result.put("totalPromptTokens", totalPromptTokens);
        result.put("totalPromptCacheHitTokens", totalPromptCacheHitTokens);
        result.put("totalPromptCacheMissTokens", totalPromptCacheMissTokens);
        result.put("promptCacheKnownCount", promptCacheKnownCount);
        result.put("promptCacheHitRate", promptCacheHitRate);
        result.put("promptCacheHealth", promptCacheHealth);
        result.put("promptCacheInsight", promptCacheInsight(promptCacheHealth, promptCacheKnownCount, promptCacheHitRate));
        result.put("promptCacheRecommendation", promptCacheRecommendation(promptCacheHealth));
        result.put("totalCompletionTokens", totalCompletionTokens);
        result.put("totalTokens", totalTokens);
        result.put("recentSampleSize", recent.size());
        result.put("latestAt", recent.isEmpty() ? "" : safeTime(recent.get(0).getCreateTime()));
        result.put("topProviderByCalls", topCountMap(all, LlmUsageRecord::getProviderId, 6));
        result.put("topProviderByTokens", topTokenMap(all, LlmUsageRecord::getProviderId, 6));
        result.put("topModelByTokens", topTokenMap(all, LlmUsageRecord::getModel, 8));
        result.put("topUserByCalls", topCountMap(all, LlmUsageRecord::getUserId, 8));
        result.put("topUserByTokens", topTokenMap(all, LlmUsageRecord::getUserId, 8));
        result.put("bySourceCalls", topCountMap(all, LlmUsageRecord::getSource, 8));
        result.put("bySourcePromptCacheHitRate", sourceCacheHitRate(all, 8));
        result.put("topProvider", topKey(topTokenMap(all, LlmUsageRecord::getProviderId, 1), topCountMap(all, LlmUsageRecord::getProviderId, 1)));
        result.put("topModel", topKey(topTokenMap(all, LlmUsageRecord::getModel, 1), topCountMap(all, LlmUsageRecord::getModel, 1)));
        return result;
    }

    private List<LlmUsageRecord> loadAllRecords() {
        if (dbEnabled) {
            try {
                return lambdaQuery().list();
            } catch (Exception ex) {
                log.warn("LLM 用量全量查询失败，降级本地缓存。reason={}", ex.getMessage());
            }
        }
        synchronized (localRecords) {
            return new ArrayList<>(localRecords);
        }
    }

    private void cacheLocalRecord(LlmUsageRecord record) {
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(record.getCreateTime());
        synchronized (localRecords) {
            localRecords.addLast(record);
            while (localRecords.size() > LOCAL_MAX_RECORDS) {
                localRecords.pollFirst();
            }
        }
    }

    private long sumTokens(List<LlmUsageRecord> records, Function<LlmUsageRecord, Integer> mapper) {
        return records.stream()
                .map(mapper)
                .filter(value -> value != null && value > 0)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private Map<String, Long> topCountMap(List<LlmUsageRecord> records,
                                          Function<LlmUsageRecord, String> keyMapper,
                                          int limit) {
        Map<String, Long> grouped = records.stream()
                .map(keyMapper)
                .map(this::normalizeBucket)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return sortDesc(grouped, limit);
    }

    private Map<String, Long> topTokenMap(List<LlmUsageRecord> records,
                                          Function<LlmUsageRecord, String> keyMapper,
                                          int limit) {
        Map<String, Long> grouped = records.stream()
                .collect(Collectors.groupingBy(
                        record -> normalizeBucket(keyMapper.apply(record)),
                        Collectors.summingLong(record -> record.getTotalTokens() == null ? 0L : record.getTotalTokens())
                ));
        return sortDesc(grouped, limit);
    }

    private Map<String, Double> sourceCacheHitRate(List<LlmUsageRecord> records, int limit) {
        return records.stream()
                .filter(record -> record.getPromptCacheHitTokens() != null || record.getPromptCacheMissTokens() != null)
                .collect(Collectors.groupingBy(
                        record -> normalizeBucket(record.getSource()),
                        Collectors.collectingAndThen(Collectors.toList(), values -> hitRate(
                                sumTokens(values, LlmUsageRecord::getPromptCacheHitTokens),
                                sumTokens(values, LlmUsageRecord::getPromptCacheMissTokens)
                        ))
                ))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private double hitRate(long hitTokens, long missTokens) {
        long total = hitTokens + missTokens;
        if (total <= 0) {
            return 0.0d;
        }
        return Math.round((hitTokens * 1.0d / total) * 10_000d) / 10_000d;
    }

    private String promptCacheHealth(long knownCount, long hitTokens, long missTokens, double hitRate) {
        if (knownCount <= 0 || hitTokens + missTokens <= 0) {
            return "UNKNOWN";
        }
        if (hitRate < 0.25d) {
            return "LOW";
        }
        if (hitRate < 0.65d) {
            return "MEDIUM";
        }
        return "HEALTHY";
    }

    private String promptCacheInsight(String health, long knownCount, double hitRate) {
        return switch (health) {
            case "LOW" -> "Prompt cache 命中率偏低（" + formatRate(hitRate) + "），通常说明请求前缀变化大、system prompt/工具说明过于动态，或 provider 未复用相同模型与会话前缀。";
            case "MEDIUM" -> "Prompt cache 命中率一般（" + formatRate(hitRate) + "），已有部分复用，但上下文前缀仍可能被动态 trace、时间、随机 session 信息打散。";
            case "HEALTHY" -> "Prompt cache 命中率健康（" + formatRate(hitRate) + "），说明模型请求中有稳定前缀可被 provider 复用。";
            default -> knownCount <= 0
                    ? "Provider 暂未返回 prompt cache hit/miss 明细，无法判断真实命中率。"
                    : "Prompt cache 数据不足，暂不能判断命中率。";
        };
    }

    private String promptCacheRecommendation(String health) {
        return switch (health) {
            case "LOW" -> "优先稳定 system prompt、工具目录和长文本说明的顺序；把动态 requestId/时间/trace 放到 prompt 后段；固定 provider+model；避免每轮拼入大量变化的历史和文件全文。";
            case "MEDIUM" -> "继续减少 prompt 前缀中的动态内容，把可复用的工具说明、策略和角色约束放在稳定前缀，把本轮上下文放后面。";
            case "HEALTHY" -> "保持稳定 prompt 前缀，并继续监控不同 source/provider 的 cache hit rate。";
            default -> "先确认当前 provider 是否返回 prompt cache usage；如果不返回，只能用 token 趋势和响应耗时辅助判断缓存效果。";
        };
    }

    private String formatRate(double rate) {
        return Math.round(rate * 10_000d) / 100d + "%";
    }

    private Map<String, Long> sortDesc(Map<String, Long> source, int limit) {
        return source.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String topKey(Map<String, Long> primary, Map<String, Long> fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary.keySet().iterator().next();
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.keySet().iterator().next();
        }
        return "";
    }

    private String resolveModel(ChatResponseMetadata metadata, String fallbackModel) {
        if (metadata != null && StringUtils.hasText(metadata.getModel())) {
            return metadata.getModel().trim();
        }
        return defaultValue(fallbackModel, "unknown");
    }

    private Integer normalize(Integer value) {
        if (value == null || value < 0) {
            return null;
        }
        return value;
    }

    private String normalizeBucket(String value) {
        return StringUtils.hasText(value) ? value.trim() : "UNKNOWN";
    }

    private String defaultValue(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String safeTime(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }
}
