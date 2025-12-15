import dotenv from "dotenv";
import path from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const envPath = path.resolve(__dirname, "../.env");

export function setupEnvironment() {
  const result = dotenv.config({ path: envPath });
  if (result.error) {
    throw new Error(
      `Failed to load .env file from ${envPath}: ${result.error.message}`
    );
  }

  const apiKey = process.env.GOOGLE_API_KEY || process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error(
      "Set GOOGLE_API_KEY or GEMINI_API_KEY in your .env file"
    );
  }

  return {
    GOOGLE_API_KEY: apiKey,
    GEMINI_MODEL: process.env.GEMINI_MODEL,
    NODE_ENV: process.env.NODE_ENV || "development",
  };
}
