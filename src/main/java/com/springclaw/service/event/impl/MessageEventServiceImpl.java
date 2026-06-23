package com.springclaw.service.event.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.mapper.MessageEventMapper;
import com.springclaw.service.event.MessageEventReceipt;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.event.MessageEventWrite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 消息事件服务实现。
 *
 * 设计说明：
 * 1. 支持 DB 持久化与本地降级双写策略，保证审计链路在依赖故障时不中断。
 * 2. 对上层暴露记录“单条事件/一轮事件”能力，减少业务层样板代码。
 */
@Service
public class MessageEventServiceImpl extends ServiceImpl<MessageEventMapper, MessageEvent>
        implements MessageEventService {

    private static final Logger log = LoggerFactory.getLogger(MessageEventServiceImpl.class);
    private static final int LOCAL_MAX_EVENTS_PER_SESSION = 100;

    private final boolean dbEnabled;
    private final Map<String, Deque<MessageEvent>> localEventCache = new ConcurrentHashMap<>();
    private final Map<String, MessageEvent> localEventByKey = new ConcurrentHashMap<>();
    private final AtomicLong localIdGenerator = new AtomicLong(1);

    public MessageEventServiceImpl(@Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled) {
        this.dbEnabled = dbEnabled;
    }

    @Override
    public MessageEventReceipt append(MessageEventWrite write) {
        if (write == null || !StringUtils.hasText(write.sessionKey())
                || !StringUtils.hasText(write.content())) {
            return null;
        }
        MessageEvent event = new MessageEvent();
        event.setEventKey(write.eventKey());
        event.setSessionKey(write.sessionKey());
        event.setChannel(write.channel() == null ? "unknown" : write.channel());
        event.setUserId(write.userId() == null ? "anonymous" : write.userId());
        event.setRole(write.role() == null ? "SYSTEM" : write.role());
        event.setEventType(write.eventType() == null ? "SYSTEM" : write.eventType());
        event.setRequestId(write.requestId() == null ? "" : write.requestId());
        event.setContent(write.content());

        if (dbEnabled) {
            try {
                MessageEvent existing = findByEventKey(write.eventKey());
                if (existing != null) {
                    return toReceipt(existing);
                }
                try {
                    saveEvent(event);
                } catch (DuplicateKeyException duplicate) {
                    MessageEvent duplicateWinner = findByEventKey(write.eventKey());
                    if (duplicateWinner != null) {
                        return toReceipt(duplicateWinner);
                    }
                    throw duplicate;
                }
                return toReceipt(event);
            } catch (Exception ex) {
                log.warn("消息事件写库失败，降级本地缓存。sessionKey={}, reason={}",
                        write.sessionKey(), ex.getMessage());
            }
        }

        synchronized (localEventByKey) {
            MessageEvent cached = localEventByKey.get(write.eventKey());
            if (cached != null) {
                return toReceipt(cached);
            }
            cacheLocalEvent(write.sessionKey(), event);
            localEventByKey.put(write.eventKey(), event);
            return toReceipt(event);
        }
    }

    protected MessageEvent findByEventKey(String eventKey) {
        return lambdaQuery()
                .eq(MessageEvent::getEventKey, eventKey)
                .last("LIMIT 1")
                .one();
    }

    protected void saveEvent(MessageEvent event) {
        save(event);
    }

    private static MessageEventReceipt toReceipt(MessageEvent event) {
        long id = event.getId() == null ? 0L : event.getId();
        Instant occurredAt = Instant.now();
        return new MessageEventReceipt(id, event.getEventKey(), occurredAt);
    }

    @Override
    public void recordSingle(String sessionKey,
                             String channel,
                             String userId,
                             String role,
                             String eventType,
                             String content,
                             String requestId) {
        if (!StringUtils.hasText(sessionKey) || !StringUtils.hasText(content)) {
            return;
        }
        // 兼容入口：生成唯一非 chat: 事件键，不参与 memory 收据幂等。
        String eventKey = "evt:" + UUID.randomUUID();
        append(new MessageEventWrite(
                eventKey, sessionKey, channel, userId, role, eventType, content, requestId));
    }

    @Override
    public void recordTurn(String sessionKey,
                           String channel,
                           String userId,
                           String userMessage,
                           String assistantMessage,
                           String eventType,
                           String requestId) {
        if (StringUtils.hasText(userMessage)) {
            recordSingle(sessionKey, channel, userId, "USER", eventType, userMessage, requestId);
        }
        if (StringUtils.hasText(assistantMessage)) {
            recordSingle(sessionKey, channel, userId, "ASSISTANT", eventType, assistantMessage, requestId);
        }
    }

    @Override
    public List<MessageEvent> listRecent(String sessionKey, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));

        if (dbEnabled) {
            try {
                List<MessageEvent> list = lambdaQuery()
                        .eq(MessageEvent::getSessionKey, sessionKey)
                        .orderByDesc(MessageEvent::getId)
                        .page(new Page<MessageEvent>(1, safeLimit, false))
                        .getRecords();
                Collections.reverse(list);
                return list;
            } catch (Exception ex) {
                log.warn("查询事件流失败，降级读取本地缓存。sessionKey={}, reason={}", sessionKey, ex.getMessage());
            }
        }

        Deque<MessageEvent> deque = localEventCache.get(sessionKey);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }

        List<MessageEvent> list = new ArrayList<>(deque);
        if (list.size() <= safeLimit) {
            return list;
        }
        return list.subList(list.size() - safeLimit, list.size());
    }

    @Override
    public List<MessageEvent> listSessionEvents(String sessionKey,
                                                String role,
                                                String eventType,
                                                int limit,
                                                boolean ascending) {
        return listSessionEvents(sessionKey, null, role, eventType, limit, ascending);
    }

    @Override
    public List<MessageEvent> listSessionEvents(String sessionKey,
                                                String userId,
                                                String role,
                                                String eventType,
                                                int limit,
                                                boolean ascending) {
        int safeLimit = Math.max(1, Math.min(limit, 5000));

        if (dbEnabled) {
            try {
                var query = lambdaQuery()
                        .eq(MessageEvent::getSessionKey, sessionKey);
                if (StringUtils.hasText(userId)) {
                    query.eq(MessageEvent::getUserId, userId.trim());
                }
                if (StringUtils.hasText(role)) {
                    query.eq(MessageEvent::getRole, role.trim().toUpperCase());
                }
                if (StringUtils.hasText(eventType)) {
                    query.eq(MessageEvent::getEventType, eventType.trim().toUpperCase());
                }
                if (ascending) {
                    query.orderByAsc(MessageEvent::getId);
                } else {
                    query.orderByDesc(MessageEvent::getId);
                }
                return query.page(new Page<MessageEvent>(1, safeLimit, false)).getRecords();
            } catch (Exception ex) {
                log.warn("按条件查询事件流失败，降级读取本地缓存。sessionKey={}, reason={}", sessionKey, ex.getMessage());
            }
        }

        return localAllEventsSnapshot().stream()
                .filter(evt -> sessionKey.equals(evt.getSessionKey()))
                .filter(evt -> !StringUtils.hasText(userId) || userId.trim().equals(evt.getUserId()))
                .filter(evt -> !StringUtils.hasText(role) || role.trim().equalsIgnoreCase(evt.getRole()))
                .filter(evt -> !StringUtils.hasText(eventType) || eventType.trim().equalsIgnoreCase(evt.getEventType()))
                .sorted(ascending
                        ? Comparator.comparingLong((MessageEvent evt) -> evt.getId() == null ? 0L : evt.getId())
                        : Comparator.comparingLong((MessageEvent evt) -> evt.getId() == null ? 0L : evt.getId()).reversed())
                .limit(safeLimit)
                .toList();
    }

    @Override
    public List<MessageEvent> listRequestEvents(String requestId,
                                                String userId,
                                                String role,
                                                String eventType,
                                                int limit,
                                                boolean ascending) {
        if (!StringUtils.hasText(requestId)) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 5000));

        if (dbEnabled) {
            try {
                var query = lambdaQuery()
                        .eq(MessageEvent::getRequestId, requestId.trim());
                if (StringUtils.hasText(userId)) {
                    query.eq(MessageEvent::getUserId, userId.trim());
                }
                if (StringUtils.hasText(role)) {
                    query.eq(MessageEvent::getRole, role.trim().toUpperCase());
                }
                if (StringUtils.hasText(eventType)) {
                    query.eq(MessageEvent::getEventType, eventType.trim().toUpperCase());
                }
                if (ascending) {
                    query.orderByAsc(MessageEvent::getId);
                } else {
                    query.orderByDesc(MessageEvent::getId);
                }
                return query.page(new Page<MessageEvent>(1, safeLimit, false)).getRecords();
            } catch (Exception ex) {
                log.warn("按 requestId 查询事件流失败，降级读取本地缓存。requestId={}, reason={}", requestId, ex.getMessage());
            }
        }

        return localAllEventsSnapshot().stream()
                .filter(evt -> requestId.trim().equals(evt.getRequestId()))
                .filter(evt -> !StringUtils.hasText(userId) || userId.trim().equals(evt.getUserId()))
                .filter(evt -> !StringUtils.hasText(role) || role.trim().equalsIgnoreCase(evt.getRole()))
                .filter(evt -> !StringUtils.hasText(eventType) || eventType.trim().equalsIgnoreCase(evt.getEventType()))
                .sorted(ascending
                        ? Comparator.comparingLong((MessageEvent evt) -> evt.getId() == null ? 0L : evt.getId())
                        : Comparator.comparingLong((MessageEvent evt) -> evt.getId() == null ? 0L : evt.getId()).reversed())
                .limit(safeLimit)
                .toList();
    }

    @Override
    public long countSessionEvents(String sessionKey, String role, String eventType) {
        return countSessionEvents(sessionKey, null, role, eventType);
    }

    @Override
    public long countSessionEvents(String sessionKey, String userId, String role, String eventType) {
        if (!StringUtils.hasText(sessionKey)) {
            return 0L;
        }

        if (dbEnabled) {
            try {
                var query = lambdaQuery()
                        .eq(MessageEvent::getSessionKey, sessionKey);
                if (StringUtils.hasText(userId)) {
                    query.eq(MessageEvent::getUserId, userId.trim());
                }
                if (StringUtils.hasText(role)) {
                    query.eq(MessageEvent::getRole, role.trim().toUpperCase());
                }
                if (StringUtils.hasText(eventType)) {
                    query.eq(MessageEvent::getEventType, eventType.trim().toUpperCase());
                }
                return query.count();
            } catch (Exception ex) {
                log.warn("统计事件流失败，降级读取本地缓存。sessionKey={}, reason={}", sessionKey, ex.getMessage());
            }
        }

        return localAllEventsSnapshot().stream()
                .filter(evt -> sessionKey.equals(evt.getSessionKey()))
                .filter(evt -> !StringUtils.hasText(userId) || userId.trim().equals(evt.getUserId()))
                .filter(evt -> !StringUtils.hasText(role) || role.trim().equalsIgnoreCase(evt.getRole()))
                .filter(evt -> !StringUtils.hasText(eventType) || eventType.trim().equalsIgnoreCase(evt.getEventType()))
                .count();
    }

    @Override
    public IPage<MessageEvent> pageQuery(String sessionKey,
                                         String userId,
                                         String role,
                                         String eventType,
                                         int pageNo,
                                         int pageSize) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        Page<MessageEvent> page = new Page<>(safePageNo, safePageSize);

        if (dbEnabled) {
            try {
                var query = lambdaQuery();
                if (StringUtils.hasText(sessionKey)) {
                    query.eq(MessageEvent::getSessionKey, sessionKey.trim());
                }
                if (StringUtils.hasText(userId)) {
                    query.eq(MessageEvent::getUserId, userId.trim());
                }
                if (StringUtils.hasText(role)) {
                    query.eq(MessageEvent::getRole, role.trim().toUpperCase());
                }
                if (StringUtils.hasText(eventType)) {
                    query.eq(MessageEvent::getEventType, eventType.trim().toUpperCase());
                }
                query.orderByDesc(MessageEvent::getId);
                return query.page(page);
            } catch (Exception ex) {
                log.warn("审计分页查询失败，降级本地缓存。reason={}", ex.getMessage());
            }
        }

        List<MessageEvent> filtered = localAllEventsSnapshot().stream()
                .filter(evt -> !StringUtils.hasText(sessionKey) || sessionKey.trim().equals(evt.getSessionKey()))
                .filter(evt -> !StringUtils.hasText(userId) || userId.trim().equals(evt.getUserId()))
                .filter(evt -> !StringUtils.hasText(role) || role.trim().equalsIgnoreCase(evt.getRole()))
                .filter(evt -> !StringUtils.hasText(eventType) || eventType.trim().equalsIgnoreCase(evt.getEventType()))
                .sorted(Comparator.comparingLong((MessageEvent evt) -> evt.getId() == null ? 0L : evt.getId()).reversed())
                .toList();

        int from = (safePageNo - 1) * safePageSize;
        int to = Math.min(filtered.size(), from + safePageSize);
        if (from >= filtered.size()) {
            page.setRecords(List.of());
            page.setTotal(filtered.size());
            return page;
        }
        page.setRecords(filtered.subList(from, to));
        page.setTotal(filtered.size());
        return page;
    }

    @Override
    public Map<String, Object> summaryStats(int recentLimit) {
        int safeRecentLimit = Math.max(10, Math.min(recentLimit, 2000));
        List<MessageEvent> sample;
        long total;

        if (dbEnabled) {
            try {
                total = lambdaQuery().count();
                sample = lambdaQuery()
                        .orderByDesc(MessageEvent::getId)
                        .page(new Page<MessageEvent>(1, safeRecentLimit, false))
                        .getRecords();
                return buildStats(total, sample);
            } catch (Exception ex) {
                log.warn("审计统计查询失败，降级本地缓存。reason={}", ex.getMessage());
            }
        }

        sample = localAllEventsSnapshot().stream()
                .sorted(Comparator.comparingLong((MessageEvent evt) -> evt.getId() == null ? 0L : evt.getId()).reversed())
                .limit(safeRecentLimit)
                .toList();
        total = localAllEventsSnapshot().size();
        return buildStats(total, sample);
    }

    private void cacheLocalEvent(String sessionKey, MessageEvent event) {
        event.setId(-localIdGenerator.getAndIncrement());
        Deque<MessageEvent> deque = localEventCache.computeIfAbsent(sessionKey, key -> new ConcurrentLinkedDeque<>());
        deque.addLast(event);
        while (deque.size() > LOCAL_MAX_EVENTS_PER_SESSION) {
            MessageEvent evicted = deque.pollFirst();
            if (evicted != null && evicted.getEventKey() != null) {
                localEventByKey.remove(evicted.getEventKey(), evicted);
            }
        }
    }

    private List<MessageEvent> localAllEventsSnapshot() {
        List<MessageEvent> all = new ArrayList<>();
        for (Deque<MessageEvent> deque : localEventCache.values()) {
            all.addAll(deque);
        }
        return all;
    }

    private Map<String, Object> buildStats(long total, List<MessageEvent> sample) {
        Map<String, Long> byEventType = topMap(sample, MessageEvent::getEventType);
        Map<String, Long> byRole = topMap(sample, MessageEvent::getRole);
        Map<String, Long> byChannel = topMap(sample, MessageEvent::getChannel);
        Map<String, Long> byTool = sample.stream()
                .filter(evt -> "TOOL".equalsIgnoreCase(evt.getEventType()))
                .map(MessageEvent::getContent)
                .map(this::extractToolName)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("recentSampleSize", sample.size());
        result.put("byEventType", byEventType);
        result.put("byRole", byRole);
        result.put("byChannel", byChannel);
        result.put("topTools", sortDesc(byTool, 8));
        return result;
    }

    private Map<String, Long> topMap(List<MessageEvent> sample, Function<MessageEvent, String> keyMapper) {
        Map<String, Long> grouped = sample.stream()
                .map(keyMapper)
                .map(value -> StringUtils.hasText(value) ? value.trim() : "UNKNOWN")
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return sortDesc(grouped, 8);
    }

    private Map<String, Long> sortDesc(Map<String, Long> source, int limit) {
        return source.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private String extractToolName(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String marker = "tool=";
        int start = content.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int comma = content.indexOf(',', start);
        if (comma < 0) {
            comma = content.length();
        }
        return content.substring(start + marker.length(), comma).trim();
    }
}
