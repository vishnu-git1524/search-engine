package com.geminisearch.server;

import com.geminisearch.server.env.DotenvLoader;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GeminiSearchApplication {
  public static void main(String[] args) {
    // Load .env from common working directories.
    // When running via `mvn -f server-java/pom.xml ...`, Spring's user.dir is often `server-java`.
    DotenvLoader.loadToSystemProperties(Path.of(".env"));
    DotenvLoader.loadToSystemProperties(Path.of("..", ".env"));
    DotenvLoader.loadToSystemProperties(Path.of("..", "..", ".env"));
    SpringApplication.run(GeminiSearchApplication.class, args);
  }
}
