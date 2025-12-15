package com.geminisearch.server.format;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ResponseFormatter {
  private final Parser parser;
  private final HtmlRenderer renderer;

  public ResponseFormatter() {
    MutableDataSet options = new MutableDataSet();
    options.set(Parser.EXTENSIONS,
        List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()));
    this.parser = Parser.builder(options).build();
    this.renderer = HtmlRenderer.builder(options).softBreak("\n").build();
  }

  public String formatToHtml(String rawText) {
    if (rawText == null) return "";

    String processed = rawText.replace("\r\n", "\n");

    // Main sections (lines that start with word(s) followed by colon)
    processed = processed.replaceAll("(?m)^([A-Za-z][A-Za-z\\s]+):(\\s*)", "## $1$2");

    // Sub-sections (remaining word(s) followed by colon within text)
    processed = processed.replaceAll("(?m)(?<=\\n|^)([A-Za-z][A-Za-z\\s]+):(?!\\d)", "### $1");

    // Bullet points
    processed = processed.replaceAll("(?m)^[•●○]\\s*", "* ");

    // Keep paragraph structure roughly similar to the Node implementation
    String[] paragraphs = processed.split("\\n\\n+");
    StringBuilder formatted = new StringBuilder();
    for (int i = 0; i < paragraphs.length; i++) {
      String p = paragraphs[i].trim();
      if (p.isEmpty()) continue;
      if (p.startsWith("#") || p.startsWith("*") || p.startsWith("-")) {
        formatted.append(p);
      } else {
        formatted.append(p).append("\n");
      }
      if (i < paragraphs.length - 1) {
        formatted.append("\n\n");
      }
    }

    return renderer.render(parser.parse(formatted.toString()));
  }
}
