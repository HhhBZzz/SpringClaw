package com.openclaw.service.chat;

import com.openclaw.dto.chat.ChatRequest;
import com.openclaw.dto.chat.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    ChatResponse chat(ChatRequest request);

    SseEmitter stream(ChatRequest request);
}
