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
     */
    public List<ConversationTurn> derive(String sessionKey, int limit) {
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
                ConversationTurn turn = toTurn(event);
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

    private ConversationTurn toTurn(RunEvent event) {
        if (event.eventType() == RunEventType.USER_MESSAGE) {
            String question = payloadField(event, "question");
            if (question == null || question.isBlank()) {
                return null;
            }
            return ConversationTurn.canonical(
                    ConversationTurn.Role.USER, question, event.runId(), event.timestamp());
        }
        if (event.eventType() == RunEventType.ASSISTANT_ANSWER) {
            String answer = payloadField(event, "answer");
            if (answer == null || answer.isBlank()) {
                return null;
            }
            return ConversationTurn.canonical(
                    ConversationTurn.Role.ASSISTANT, answer, event.runId(), event.timestamp());
        }
        return null;
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
