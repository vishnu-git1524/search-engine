package com.geminisearch.server.api;

import com.geminisearch.server.format.ResponseFormatter;
import com.geminisearch.server.gemini.GeminiClient;
import com.geminisearch.server.sessions.ChatSessionState;
import com.geminisearch.server.sessions.ChatSessionStore;
import com.geminisearch.server.sessions.SearchResponse;
import com.geminisearch.server.sessions.Source;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
  private final GeminiClient geminiClient;
  private final ChatSessionStore sessionStore;
  private final ResponseFormatter responseFormatter;

  public SearchController(
      GeminiClient geminiClient, ChatSessionStore sessionStore, ResponseFormatter responseFormatter) {
    this.geminiClient = geminiClient;
    this.sessionStore = sessionStore;
    this.responseFormatter = responseFormatter;
  }

  @GetMapping(path = "/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> search(
      @RequestParam(name = "q", required = false) String query,
      HttpServletResponse response) {
    if (query == null || query.trim().isEmpty()) {
      response.setStatus(400);
      return Map.of("message", "Query parameter 'q' is required");
    }

    ChatSessionState session = sessionStore.createSession();

    GeminiClient.GeminiResult result;
    try {
      result = geminiClient.generateFirstAnswer(query, session);
    } catch (GeminiClient.MissingApiKeyException e) {
      response.setStatus(500);
      return Map.of("message", e.getMessage());
    } catch (GeminiClient.RateLimitedException rl) {
      if (rl.retryAfterSeconds() != null) {
        response.setHeader("Retry-After", String.valueOf(rl.retryAfterSeconds()));
      }
      response.setStatus(429);
      return rl.retryAfterSeconds() == null
          ? Map.of("message", "Rate limit/quota exceeded. Please retry shortly.")
          : Map.of(
              "message",
              "Rate limit/quota exceeded. Retry in ~" + rl.retryAfterSeconds() + "s.",
              "retryAfterSeconds",
              rl.retryAfterSeconds());
    } catch (Exception e) {
      response.setStatus(500);
      return Map.of("message", "An error occurred while processing your search");
    }

    String html = responseFormatter.formatToHtml(result.text());
    List<Source> sources = result.sources();

    SearchResponse body = new SearchResponse(session.sessionId(), html, sources);
    return body.toMap();
  }

  public record FollowUpRequest(String sessionId, String query) {}

  @PostMapping(path = "/api/follow-up", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> followUp(
      @RequestBody FollowUpRequest request,
      HttpServletResponse response) {
    if (request == null || request.sessionId() == null || request.query() == null
        || request.sessionId().trim().isEmpty() || request.query().trim().isEmpty()) {
      response.setStatus(400);
      return Map.of("message", "Both sessionId and query are required");
    }

    ChatSessionState session = sessionStore.getSession(request.sessionId());
    if (session == null) {
      response.setStatus(404);
      return Map.of("message", "Chat session not found");
    }

    GeminiClient.GeminiResult result;
    try {
      result = geminiClient.generateFollowUp(request.query(), session);
    } catch (GeminiClient.MissingApiKeyException e) {
      response.setStatus(500);
      return Map.of("message", e.getMessage());
    } catch (GeminiClient.RateLimitedException rl) {
      if (rl.retryAfterSeconds() != null) {
        response.setHeader("Retry-After", String.valueOf(rl.retryAfterSeconds()));
      }
      response.setStatus(429);
      return rl.retryAfterSeconds() == null
          ? Map.of("message", "Rate limit/quota exceeded. Please retry shortly.")
          : Map.of(
              "message",
              "Rate limit/quota exceeded. Retry in ~" + rl.retryAfterSeconds() + "s.",
              "retryAfterSeconds",
              rl.retryAfterSeconds());
    } catch (Exception e) {
      response.setStatus(500);
      return Map.of("message", "An error occurred while processing your follow-up question");
    }

    String html = responseFormatter.formatToHtml(result.text());
    return Map.of(
        "summary", html,
        "sources", result.sources());
  }
}
