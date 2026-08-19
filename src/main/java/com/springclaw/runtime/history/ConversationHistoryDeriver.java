package com.springclaw.runtime.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.lifecycle.RunLifecycleStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 canonical run 事件日志派生 LLM 对话历史
 * (spec 2026-08-17-canonical-conversation-history §3.2)。
 *
 * <p>纯读——不回写任何库;user.message/assistant.answer 事件是唯一输入,
 * 按时间线(run updated_at 倒序扫描、run 内 sequence 正序、全局时间升序)输出。
 * 派生失败(事件缺/payload 坏)跳过该条,不抛异常——读侧兜底由调用方开关回退承担。</p>
 */
@Component
public class ConversationHistoryDeriver {

    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    private final RunLifecycleStore store;

    public ConversationHistoryDeriver(RunLifecycleStore store) {
        this.store = store;
    }

    /**
     * 派生 session 最近 limit 条对话 turn(USER/ASSISTANT)。
     * 取最近 limit 条、按时间正序返回(最旧在前,与 legacy listRecent 语义一致)。
     * 内容按 legacy renderEventLine 规则截断至 220——LLM 上下文用。
     */
    public List<ConversationTurn> derive(String sessionKey, int limit) {
        return deriveInternal(sessionKey, limit, true);
    }

    /**
     * 不截断变体(spec 2026-08-18 §3.3):展示(/api/chat/history)、
     * 精确查询(第一条/上一条消息)、帧构建(MemoryCoordinator)用。
     */
    public List<ConversationTurn> deriveFull(String sessionKey, int limit) {
        return deriveInternal(sessionKey, limit, false);
    }

    private List<ConversationTurn> deriveInternal(String sessionKey, int limit, boolean truncate) {
        if (sessionKey == null || sessionKey.isBlank() || limit <= 0) {
            return List.of();
        }
        // run 上限:每 run 至多 2 条对话 turn,多取一些 run 保证凑满 limit
        int runScanLimit = Math.min(Math.max(limit, 1) * 2, 200);
        List<RunState> runs = recentRunsBySession(sessionKey, runScanLimit);

        List<ConversationTurn> turns = new ArrayList<>();
        for (RunState run : runs) {
            List<RunEvent> events = store.findEventsByRunId(run.runId());
            for (RunEvent event : events) {
                ConversationTurn turn = toTurn(event, run, truncate);
                if (turn != null) {
                    turns.add(turn);
                }
            }
        }
        turns.sort(Comparator.comparing(ConversationTurn::at));
        if (turns.size() > limit) {
            turns = new ArrayList<>(turns.subList(turns.size() - limit, turns.size()));
        }
        return turns;
    }

    private List<RunState> recentRunsBySession(String sessionKey, int scanLimit) {
        List<RunState> recent = store.findRecent(Math.min(scanLimit * 4, 500));
        List<RunState> bySession = new ArrayList<>();
        for (RunState state : recent) {
            if (sessionKey.equals(state.sessionKey())) {
                bySession.add(state);
                if (bySession.size() >= scanLimit) {
                    break;
                }
            }
        }
        return bySession;
    }

    private ConversationTurn toTurn(RunEvent event, RunState run, boolean truncate) {
        if (event.eventType() == RunEventType.USER_MESSAGE) {
            String question = payloadField(event, "question");
            if (question == null || question.isBlank()) {
                return null;
            }
            return toTurn(ConversationTurn.Role.USER, question, event, run, truncate);
        }
        if (event.eventType() == RunEventType.ASSISTANT_ANSWER) {
            String answer = payloadField(event, "answer");
            if (answer == null || answer.isBlank()) {
                return null;
            }
            return toTurn(ConversationTurn.Role.ASSISTANT, answer, event, run, truncate);
        }
        return null;
    }

    /** 归属(channel/userId)取自 RunState——派生纯读,不回写、不需要事件 payload 带归属。 */
    private ConversationTurn toTurn(
            ConversationTurn.Role role, String content, RunEvent event, RunState run, boolean truncate) {
        return truncate
                ? ConversationTurn.canonical(
                        role, content, event.runId(), run.channel(), run.userId(), event.timestamp())
                : ConversationTurn.canonicalUntruncated(
                        role, content, event.runId(), run.channel(), run.userId(), event.timestamp());
    }

    private String payloadField(RunEvent event, String field) {
        String payload = event.payload();
        if (payload == null || payload.isBlank() || !payload.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<String, Object> map = PAYLOAD_MAPPER.readValue(payload, new TypeReference<>() {
            });
            Object value = map.get(field);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }
}
