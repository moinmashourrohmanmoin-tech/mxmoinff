import { createServerFn } from "@tanstack/react-start";
import { z } from "zod";

const askSchema = z.object({
  messages: z.array(z.object({ role: z.enum(["user", "assistant"]), content: z.string().max(8000) })).min(1).max(40),
  language: z.enum(["bn", "en", "auto"]),
  mode: z.enum(["fast", "verified"]),
  memory: z.array(z.string().max(500)).max(50),
});

export const askMxff = createServerFn({ method: "POST" })
  .inputValidator((d: unknown) => askSchema.parse(d))
  .handler(async ({ data }) => {
    const { askBrain, BrainError } = await import("./brain.server");
    try {
      return { ok: true as const, ...(await askBrain(data)) };
    } catch (e) {
      const status = e instanceof BrainError ? e.status : 500;
      return { ok: false as const, status, error: e instanceof Error ? e.message : "Unknown error" };
    }
  });

export const searchMxff = createServerFn({ method: "POST" })
  .inputValidator((d: unknown) => z.object({ query: z.string().min(1).max(300), kind: z.enum(["web", "video"]) }).parse(d))
  .handler(async ({ data }) => {
    const { webSearch, youtubeSearch } = await import("./search.server");
    try {
      if (data.kind === "video") return { ok: true as const, videos: await youtubeSearch(data.query), web: [] };
      return { ok: true as const, web: await webSearch(data.query), videos: [] };
    } catch (e) {
      return { ok: false as const, error: e instanceof Error ? e.message : "Search failed", web: [], videos: [] };
    }
  });
