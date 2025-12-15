package com.geminisearch.server.sessions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record SearchResponse(String sessionId, String summary, List<Source> sources) {
  public Map<String, Object> toMap() {
    Map<String, Object> m = new HashMap<>();
    m.put("sessionId", sessionId);
    m.put("summary", summary);
    m.put("sources", sources);
    return m;
  }
}
