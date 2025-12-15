package com.geminisearch.server.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DotenvLoader {
  private DotenvLoader() {}

  /**
   * Loads key/value pairs from a .env file into System properties.
   *
   * <p>Precedence:
   * <ul>
   *   <li>If an environment variable exists for the key, it is left untouched.</li>
   *   <li>If a System property already exists for the key, it is left untouched.</li>
   * </ul>
   */
  public static void loadToSystemProperties(Path envFile) {
    if (envFile == null || !Files.exists(envFile)) {
      return;
    }

    List<String> lines;
    try {
      lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }

    for (String rawLine : lines) {
      if (rawLine == null) continue;
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;

      int eq = line.indexOf('=');
      if (eq <= 0) continue;

      String key = line.substring(0, eq).trim();
      String value = line.substring(eq + 1).trim();

      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
        value = value.substring(1, value.length() - 1);
      }

      if (key.isEmpty()) continue;
      if (System.getenv(key) != null) continue;
      if (System.getProperty(key) != null) continue;

      System.setProperty(key, value);
    }
  }
}
