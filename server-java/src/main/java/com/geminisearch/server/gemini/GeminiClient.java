package com.geminisearch.server.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geminisearch.server.sessions.ChatSessionState;
import com.geminisearch.server.sessions.Source;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiClient {
  private static final String DEFAULT_MODEL = "gemini-2.5-flash";
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String apiKey;
  private final String model;

  public GeminiClient(
      ObjectMapper mapper,
      @Value("${GOOGLE_API_KEY:${GEMINI_API_KEY:}}") String apiKey,
      @Value("${GEMINI_MODEL:}") String configuredModel) {
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.mapper = mapper;
    this.apiKey = apiKey == null ? "" : apiKey;
    this.model = (configuredModel == null || configuredModel.isBlank()) ? DEFAULT_MODEL : configuredModel;
  }

  private void ensureApiKeyPresent() {
    if (this.apiKey.isBlank()) {
      throw new MissingApiKeyException();
    }
  }

  public GeminiResult generateFirstAnswer(String query, ChatSessionState session)
      throws IOException, InterruptedException {
    ensureApiKeyPresent();
    session.addUserMessage(query);

    // Try with tools first.
    GeminiCallResult withTools = callGenerateContent(session.history(), true);
    if (withTools.statusCode() / 100 != 2) {
      if (withTools.statusCode() == 429) {
        throw new RateLimitedException(extractRetryAfterSeconds(withTools.body()));
      }
      // Retry without tools.
      GeminiCallResult withoutTools = callGenerateContent(session.history(), false);
      if (withoutTools.statusCode() == 429) {
        throw new RateLimitedException(extractRetryAfterSeconds(withoutTools.body()));
      }
      if (withoutTools.statusCode() / 100 != 2) {
        throw new IOException("Gemini API request failed (status " + withoutTools.statusCode() + ")");
      }
      session.setToolsEnabled(false);
      GeminiResult parsed = parseResult(withoutTools.body());
      session.addModelMessage(parsed.text());
      return parsed;
    }

    session.setToolsEnabled(true);
    GeminiResult parsed = parseResult(withTools.body());
    session.addModelMessage(parsed.text());
    return parsed;
  }

  public GeminiResult generateFollowUp(String query, ChatSessionState session)
      throws IOException, InterruptedException {
    ensureApiKeyPresent();
    session.addUserMessage(query);

    boolean tools = session.toolsEnabled();
    GeminiCallResult res = callGenerateContent(session.history(), tools);
    if (res.statusCode() == 429) {
      throw new RateLimitedException(extractRetryAfterSeconds(res.body()));
    }
    if (res.statusCode() / 100 != 2) {
      throw new IOException("Gemini API request failed (status " + res.statusCode() + ")");
    }

    GeminiResult parsed = parseResult(res.body());
    session.addModelMessage(parsed.text());
    return parsed;
  }

  public record GeminiResult(String text, List<Source> sources) {}

  public static final class RateLimitedException extends RuntimeException {
    private final Integer retryAfterSeconds;

    public RateLimitedException(Integer retryAfterSeconds) {
      super("Rate limit/quota exceeded");
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  public static final class MissingApiKeyException extends RuntimeException {
    public MissingApiKeyException() {
      super("Set GOOGLE_API_KEY or GEMINI_API_KEY in your .env or environment");
    }
  }

  private record GeminiCallResult(int statusCode, String body) {}

  private GeminiCallResult callGenerateContent(List<ChatSessionState.Message> history, boolean withTools)
      throws IOException, InterruptedException {
    Objects.requireNonNull(history, "history");

    Map<String, Object> payload = new LinkedHashMap<>();

    List<Object> contents = new ArrayList<>();
    for (ChatSessionState.Message msg : history) {
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("role", msg.role());
      content.put("parts", List.of(Map.of("text", msg.text())));
      contents.add(content);
    }

    payload.put("contents", contents);

    payload.put(
        "generationConfig",
        Map.of(
            "temperature", 0.9,
            "topP", 1,
            "topK", 1,
            "maxOutputTokens", 2048));

    if (withTools) {
      payload.put("tools", List.of(Map.of("google_search", Map.of())));
    }

    String json = mapper.writeValueAsString(payload);

    URI uri = URI.create(
        "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);

    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(TIMEOUT)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    return new GeminiCallResult(resp.statusCode(), resp.body());
  }

  private GeminiResult parseResult(String body) throws IOException {
    JsonNode root = mapper.readTree(body);

    String text = "";
    JsonNode candidates = root.path("candidates");
    if (candidates.isArray() && candidates.size() > 0) {
      JsonNode parts = candidates.get(0).path("content").path("parts");
      if (parts.isArray()) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
          JsonNode t = p.get("text");
          if (t != null && t.isTextual()) {
            sb.append(t.asText());
          }
        }
        text = sb.toString();
      }
    }

    List<Source> sources = extractSources(candidates);

    return new GeminiResult(text, sources);
  }

  private List<Source> extractSources(JsonNode candidates) {
    // Match the Node behavior: look at candidates[0].groundingMetadata
    if (!candidates.isArray() || candidates.size() == 0) return List.of();

    JsonNode metadata = candidates.get(0).path("groundingMetadata");
    if (metadata.isMissingNode() || metadata.isNull()) return List.of();

    JsonNode chunks = metadata.path("groundingChunks");
    JsonNode supports = metadata.path("groundingSupports");

    // Preserve insertion order and uniqueness by URL.
    Map<String, SourceBuilder> sourceMap = new LinkedHashMap<>();

    if (chunks.isArray()) {
      for (int i = 0; i < chunks.size(); i++) {
        JsonNode chunk = chunks.get(i);
        String url = chunk.path("web").path("uri").asText(null);
        String title = chunk.path("web").path("title").asText(null);
        if (url == null || url.isBlank() || title == null || title.isBlank()) continue;

        sourceMap.putIfAbsent(url, new SourceBuilder(title, url));

        if (supports.isArray()) {
          for (JsonNode support : supports) {
            JsonNode indices = support.path("groundingChunkIndices");
            if (!indices.isArray()) continue;

            boolean referencesChunk = false;
            for (JsonNode idx : indices) {
              if (idx.isInt() && idx.asInt() == i) {
                referencesChunk = true;
                break;
              }
            }
            if (!referencesChunk) continue;

            String snippet = support.path("segment").path("text").asText("");
            if (!snippet.isBlank()) {
              sourceMap.get(url).appendSnippet(snippet);
            }
          }
        }
      }
    }

    List<Source> sources = new ArrayList<>();
    for (SourceBuilder b : sourceMap.values()) {
      sources.add(b.toSource());
    }
    return sources;
  }

  private static final class SourceBuilder {
    private final String title;
    private final String url;
    private final StringBuilder snippet = new StringBuilder();

    private SourceBuilder(String title, String url) {
      this.title = title;
      this.url = url;
    }

    private void appendSnippet(String s) {
      if (snippet.length() > 0) snippet.append(' ');
      snippet.append(s);
    }

    private Source toSource() {
      return new Source(title, url, snippet.toString());
    }
  }

  private static Integer extractRetryAfterSeconds(String body) {
    if (body == null || body.isBlank()) return null;

    // Common Gemini error formats include: "Please retry in 16.028201274s."
    var m1 = java.util.regex.Pattern.compile("retry in\\s+(\\d+(?:\\.\\d+)?)s", java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(body);
    if (m1.find()) {
      double seconds = Double.parseDouble(m1.group(1));
      return (int) Math.ceil(seconds);
    }

    // Or embedded detail like: "\"retryDelay\":\"16s\""
    var m2 = java.util.regex.Pattern.compile("retryDelay\\\"\\s*:\\s*\\\"(\\d+)s\\\"", java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(body);
    if (m2.find()) {
      return Integer.parseInt(m2.group(1));
    }

    return null;
  }
}
