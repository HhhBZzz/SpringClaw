package com.springclaw.service.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.event.MessageEventReceipt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Phase 3A1 Task 6：把终端/挂起事件影子写入 Redis 短期记忆。
 *
 * <p>terminal intent 追加 user + assistant 两条；suspension intent 只追加 user。
 * Redis 失败由 {@link ShortTermMemoryStore#append} 内部吞掉并降级 MySQL 恢复源，
 * 不阻断已持久化的对话。
 */
@Component
public class ShortTermMemoryWriter {

    private final ObjectProvider<ShortTermMemoryStore> storeProvider;

    public ShortTermMemoryWriter(ObjectProvider<ShortTermMemoryStore> storeProvider) {
        this.storeProvider = storeProvider;
    }

    /** 终端回复：追加 user 与 assistant 两条短期记忆。 */
    public void appendTerminal(ChatContext context,
                               MessageEventReceipt userReceipt,
                               String userContent,
                               MessageEventReceipt assistantReceipt,
                               String assistantContent) {
        if (!isDurable(userReceipt) || !isDurable(assistantReceipt)) {
            return;
        }
        MemoryScope scope = writeScope(context);
        ShortTermMemoryStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        store.append(scope, entry(userReceipt, context, "USER", userContent));
        store.append(scope, entry(assistantReceipt, context, "ASSISTANT", assistantContent));
    }

    /** 挂起：只追加 user 一条短期记忆，不写终端 assistant。 */
    public void appendSuspension(ChatContext context,
                                 MessageEventReceipt userReceipt,
                                 String userContent) {
        if (!isDurable(userReceipt)) {
            return;
        }
        MemoryScope scope = writeScope(context);
        ShortTermMemoryStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        store.append(scope, entry(userReceipt, context, "USER", userContent));
    }

    private static MemoryScope writeScope(ChatContext context) {
        ContextSnapshot snapshot = context.contextSnapshot();
        if (snapshot != null && snapshot.memoryFrame() != null) {
            return snapshot.memoryFrame().scope();
        }
        return MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                context.channel(),
                context.session().getSessionKey(),
                context.userId()
        ));
    }

    private static boolean isDurable(MessageEventReceipt receipt) {
        return receipt != null
                && receipt.isDurable()
                && receipt.eventKey() != null
                && !receipt.eventKey().isBlank()
                && receipt.occurredAt() != null;
    }

    private static ShortTermMemoryEntry entry(MessageEventReceipt receipt,
                                              ChatContext context,
                                              String role,
                                              String content) {
        return new ShortTermMemoryEntry(
                receipt.eventId(),
                receipt.eventKey(),
                context.requestId(),
                role,
                context.userId(),
                content,
                receipt.occurredAt()
        );
    }
}
