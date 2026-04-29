package com.springclaw.service.chat;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    ChatResponse chat(ChatRequest request);

    SseEmitter stream(ChatRequest request);
}
