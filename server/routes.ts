import type { Express } from "express";
import { createServer, type Server } from "http";
import {
  GoogleGenerativeAI,
  type ChatSession,
  type GenerateContentResult,
} from "@google/generative-ai";
import { marked } from "marked";
import { setupEnvironment } from "./env";

const env = setupEnvironment();
const genAI = new GoogleGenerativeAI(env.GOOGLE_API_KEY);
const DEFAULT_MODEL = "gemini-2.5-flash";
const model = genAI.getGenerativeModel({
  model: env.GEMINI_MODEL || DEFAULT_MODEL,
  generationConfig: {
    temperature: 0.9,
    topP: 1,
    topK: 1,
    maxOutputTokens: 2048,
  },
});

// Store chat sessions in memory
const chatSessions = new Map<string, ChatSession>();

// Format raw text into proper markdown
async function formatResponseToMarkdown(
  text: string | Promise<string>
): Promise<string> {
  // Ensure we have a string to work with
  const resolvedText = await Promise.resolve(text);

  // First, ensure consistent newlines
  let processedText = resolvedText.replace(/\r\n/g, "\n");

  // Process main sections (lines that start with word(s) followed by colon)
  processedText = processedText.replace(
    /^([A-Za-z][A-Za-z\s]+):(\s*)/gm,
    "## $1$2"
  );

  // Process sub-sections (any remaining word(s) followed by colon within text)
  processedText = processedText.replace(
    /(?<=\n|^)([A-Za-z][A-Za-z\s]+):(?!\d)/gm,
    "### $1"
  );

  // Process bullet points
  processedText = processedText.replace(/^[•●○]\s*/gm, "* ");

  // Split into paragraphs
  const paragraphs = processedText.split("\n\n").filter(Boolean);

  // Process each paragraph
  const formatted = paragraphs
    .map((p) => {
      // If it's a header or list item, preserve it
      if (p.startsWith("#") || p.startsWith("*") || p.startsWith("-")) {
        return p;
      }
      // Add proper paragraph formatting
      return `${p}\n`;
    })
    .join("\n\n");

  // Configure marked options for better header rendering
  marked.setOptions({
    gfm: true,
    breaks: true,
  });

  // Convert markdown to HTML using marked
  return marked.parse(formatted);
}

interface WebSource {
  uri: string;
  title: string;
}

interface GroundingChunk {
  web?: WebSource;
}

interface TextSegment {
  startIndex: number;
  endIndex: number;
  text: string;
}

interface GroundingSupport {
  segment: TextSegment;
  groundingChunkIndices: number[];
  confidenceScores: number[];
}

interface GroundingMetadata {
  groundingChunks: GroundingChunk[];
  groundingSupports: GroundingSupport[];
  searchEntryPoint?: any;
  webSearchQueries?: string[];
}

function coerceUpstreamStatus(error: any): number | undefined {
  const candidates = [
    error?.status,
    error?.statusCode,
    error?.response?.status,
    error?.response?.statusCode,
  ];
  for (const value of candidates) {
    if (typeof value === "number" && value >= 400 && value <= 599) return value;
  }
  return undefined;
}

function extractRetryAfterSeconds(message: string | undefined): number | undefined {
  if (!message) return undefined;
  // Common formats we see from Gemini API errors.
  // Example: "Please retry in 16.028201274s."
  const m1 = message.match(/retry in\s+(\d+(?:\.\d+)?)s/i);
  if (m1?.[1]) return Math.ceil(Number(m1[1]));

  // Example embedded detail: "\"retryDelay\":\"16s\""
  const m2 = message.match(/retryDelay\"\s*:\s*\"(\d+)s\"/i);
  if (m2?.[1]) return Number(m2[1]);

  return undefined;
}

export function registerRoutes(app: Express): Server {
  // Search endpoint - creates a new chat session
  app.get("/api/search", async (req, res) => {
    try {
      const query = req.query.q as string;

      if (!query) {
        return res.status(400).json({
          message: "Query parameter 'q' is required",
        });
      }

      const startChat = (withSearchTool: boolean) =>
        model.startChat(
          withSearchTool
            ? {
                tools: [
                  {
                    // @ts-ignore - google_search is a valid tool but not typed in the SDK yet
                    google_search: {},
                  },
                ],
              }
            : undefined
        );

      // Prefer grounding via Google Search; if not available (model/tool permissions), retry without tools.
      let chat: ChatSession = startChat(true);
      let response: GenerateContentResult["response"];
      try {
        const result = await chat.sendMessage(query);
        response = await result.response;
      } catch (err) {
        console.warn(
          "Search with google_search tool failed; retrying without tools.",
          err
        );
        chat = startChat(false);
        const result = await chat.sendMessage(query);
        response = await result.response;
      }

      const text = response.text();

      // Format the response text to proper markdown/HTML
      const formattedText = await formatResponseToMarkdown(text);

      // Extract sources from grounding metadata
      const sourceMap = new Map<
        string,
        { title: string; url: string; snippet: string }
      >();

      // Get grounding metadata from response (may be missing when tools are unavailable)
      const metadata = response.candidates?.[0]?.groundingMetadata as any;
      if (metadata) {
        const chunks = Array.isArray(metadata.groundingChunks)
          ? metadata.groundingChunks
          : [];
        const supports = Array.isArray(metadata.groundingSupports)
          ? metadata.groundingSupports
          : [];

        chunks.forEach((chunk: any, index: number) => {
          const url = chunk?.web?.uri;
          const title = chunk?.web?.title;
          if (url && title) {
            if (!sourceMap.has(url)) {
              // Find snippets that reference this chunk
              const snippets = supports
                .filter(
                  (support: any) =>
                    Array.isArray(support?.groundingChunkIndices) &&
                    support.groundingChunkIndices.includes(index)
                )
                .map((support: any) => support?.segment?.text)
                .filter(Boolean)
                .join(" ");

              sourceMap.set(url, {
                title,
                url: url,
                snippet: snippets || "",
              });
            }
          }
        });
      }

      const sources = Array.from(sourceMap.values());

      // Generate a session ID and store the chat
      const sessionId = Math.random().toString(36).substring(7);
      chatSessions.set(sessionId, chat);

      res.json({
        sessionId,
        summary: formattedText,
        sources,
      });
    } catch (error: any) {
      const upstreamStatus = coerceUpstreamStatus(error);
      const status = upstreamStatus ?? 500;
      const message =
        error?.message || "An error occurred while processing your search";

      if (status === 429) {
        const retryAfterSeconds = extractRetryAfterSeconds(message);
        const clientMessage = retryAfterSeconds
          ? `Rate limit/quota exceeded. Retry in ~${retryAfterSeconds}s.`
          : "Rate limit/quota exceeded. Please retry shortly.";

        console.error("Search rate limited:", {
          status,
          retryAfterSeconds,
          message,
        });

        if (retryAfterSeconds) {
          res.setHeader("Retry-After", String(retryAfterSeconds));
        }
        return res.status(429).json({
          message: clientMessage,
          retryAfterSeconds,
        });
      }

      console.error("Search error:", {
        status,
        message,
        stack: error?.stack,
      });

      res.status(status).json({ message });
    }
  });

  // Follow-up endpoint - continues existing chat session
  app.post("/api/follow-up", async (req, res) => {
    try {
      const { sessionId, query } = req.body;

      if (!sessionId || !query) {
        return res.status(400).json({
          message: "Both sessionId and query are required",
        });
      }

      const chat = chatSessions.get(sessionId);
      if (!chat) {
        return res.status(404).json({
          message: "Chat session not found",
        });
      }

      // Send follow-up message in existing chat
      const result = await chat.sendMessage(query);
      const response = await result.response;
      console.log(
        "Raw Google API Follow-up Response:",
        JSON.stringify(
          {
            text: response.text(),
            candidates: response.candidates,
            groundingMetadata: response.candidates?.[0]?.groundingMetadata,
          },
          null,
          2
        )
      );
      const text = response.text();

      // Format the response text to proper markdown/HTML
      const formattedText = await formatResponseToMarkdown(text);

      // Extract sources from grounding metadata
      const sourceMap = new Map<
        string,
        { title: string; url: string; snippet: string }
      >();

      // Get grounding metadata from response
      const metadata = response.candidates?.[0]?.groundingMetadata as any;
      if (metadata) {
        const chunks = Array.isArray(metadata.groundingChunks)
          ? metadata.groundingChunks
          : [];
        const supports = Array.isArray(metadata.groundingSupports)
          ? metadata.groundingSupports
          : [];

        chunks.forEach((chunk: any, index: number) => {
          const url = chunk?.web?.uri;
          const title = chunk?.web?.title;
          if (url && title) {
            if (!sourceMap.has(url)) {
              // Find snippets that reference this chunk
              const snippets = supports
                .filter(
                  (support: any) =>
                    Array.isArray(support?.groundingChunkIndices) &&
                    support.groundingChunkIndices.includes(index)
                )
                .map((support: any) => support?.segment?.text)
                .filter(Boolean)
                .join(" ");

              sourceMap.set(url, {
                title,
                url: url,
                snippet: snippets || "",
              });
            }
          }
        });
      }

      const sources = Array.from(sourceMap.values());

      res.json({
        summary: formattedText,
        sources,
      });
    } catch (error: any) {
      const upstreamStatus = coerceUpstreamStatus(error);
      const status = upstreamStatus ?? 500;
      const message =
        error?.message ||
        "An error occurred while processing your follow-up question";

      if (status === 429) {
        const retryAfterSeconds = extractRetryAfterSeconds(message);
        const clientMessage = retryAfterSeconds
          ? `Rate limit/quota exceeded. Retry in ~${retryAfterSeconds}s.`
          : "Rate limit/quota exceeded. Please retry shortly.";

        console.error("Follow-up rate limited:", {
          status,
          retryAfterSeconds,
          message,
        });

        if (retryAfterSeconds) {
          res.setHeader("Retry-After", String(retryAfterSeconds));
        }
        return res.status(429).json({
          message: clientMessage,
          retryAfterSeconds,
        });
      }

      console.error("Follow-up error:", {
        status,
        message,
        stack: error?.stack,
      });

      res.status(status).json({ message });
    }
  });

  const httpServer = createServer(app);
  return httpServer;
}
