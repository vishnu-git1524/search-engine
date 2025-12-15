package com.geminisearch.server.sessions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ChatSessionStore {
  private final Map<String, ChatSessionState> sessions = new ConcurrentHashMap<>();

  public ChatSessionState createSession() {
    String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    ChatSessionState session = new ChatSessionState(sessionId);
    sessions.put(sessionId, session);
    return session;
  }

  public ChatSessionState getSession(String sessionId) {
    return sessions.get(sessionId);
  }
}
