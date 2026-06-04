package com.springclaw.service.event;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.springclaw.domain.entity.MessageEvent;

import java.util.List;
import java.util.Map;

/**
 * 消息事件服务。
 */
public interface MessageEventService extends IService<MessageEvent> {

    void recordSingle(String sessionKey,
                      String channel,
                      String userId,
                      String role,
                      String eventType,
                      String content,
                      String requestId);

    void recordTurn(String sessionKey,
                    String channel,
                    String userId,
                    String userMessage,
                    String assistantMessage,
                    String eventType,
                    String requestId);

    List<MessageEvent> listRecent(String sessionKey, int limit);

    List<MessageEvent> listSessionEvents(String sessionKey,
                                         String role,
                                         String eventType,
                                         int limit,
                                         boolean ascending);

    List<MessageEvent> listSessionEvents(String sessionKey,
                                         String userId,
                                         String role,
                                         String eventType,
                                         int limit,
                                         boolean ascending);

    List<MessageEvent> listRequestEvents(String requestId,
                                         String userId,
                                         String role,
                                         String eventType,
                                         int limit,
                                         boolean ascending);

    long countSessionEvents(String sessionKey,
                            String role,
                            String eventType);

    long countSessionEvents(String sessionKey,
                            String userId,
                            String role,
                            String eventType);

    IPage<MessageEvent> pageQuery(String sessionKey,
                                  String userId,
                                  String role,
                                  String eventType,
                                  int pageNo,
                                  int pageSize);

    Map<String, Object> summaryStats(int recentLimit);
}
