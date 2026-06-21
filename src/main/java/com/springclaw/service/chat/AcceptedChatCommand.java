package com.springclaw.service.chat;

import com.springclaw.dto.chat.ChatRequest;

import java.util.Objects;

public record AcceptedChatCommand(String runId, ChatRequest request) {

    public AcceptedChatCommand {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(request, "request");
    }
}
