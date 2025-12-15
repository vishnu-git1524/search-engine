package com.geminisearch.server.sessions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatSessionState {
  private final String sessionId;
  private boolean toolsEnabled;
  private final List<Message> history;

  public ChatSessionState(String sessionId) {
    this.sessionId = sessionId;
    this.toolsEnabled = true;
    this.history = new ArrayList<>();
  }

  public String sessionId() {
    return sessionId;
  }

  public boolean toolsEnabled() {
    return toolsEnabled;
  }

  public void setToolsEnabled(boolean toolsEnabled) {
    this.toolsEnabled = toolsEnabled;
  }

  public List<Message> history() {
    return Collections.unmodifiableList(history);
  }

  public void addUserMessage(String text) {
    history.add(new Message("user", text));
  }

  public void addModelMessage(String text) {
    history.add(new Message("model", text));
  }

  public record Message(String role, String text) {}
}
