import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

// HTTP endpoint used by the Android floating shadow (it runs outside the WebView, so it can't call server functions).
// POST /api/mxff-ask  { messages, language, memory }  ->  { ok, text } | { ok:false, error }
// Optional protection: set MXFF_APP_TOKEN on the server and the same value as APP_TOKEN in android/app/build.gradle.kts.

const schema = z.object({
  messages: z.array(z.object({ role: z.enum(["user", "assistant"]), content: z.string().max(8000) })).min(1).max(40),
  language: z.enum(["bn", "en", "auto"]).default("bn"),
  memory: z.array(z.string().max(500)).max(50).default([]),
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });

export const Route = createFileRoute("/api/mxff-ask")({
  server: {
    handlers: {
      POST: async ({ request }: { request: Request }) => {
        const token = process.env["MXFF_APP_TOKEN"];
        if (token && request.headers.get("x-mxff-token") !== token) return json({ ok: false, error: "Unauthorized" }, 401);

        let body: z.infer<typeof schema>;
        try {
          body = schema.parse(await request.json());
        } catch {
          return json({ ok: false, error: "Bad request" }, 400);
        }

        const { askBrain, BrainError } = await import("../lib/brain.server");
        try {
          const r = await askBrain({ ...body, mode: "fast", voice: true });
          return json({ ok: true, text: r.text });
        } catch (e) {
          const status = e instanceof BrainError ? e.status : 500;
          return json({ ok: false, error: e instanceof Error ? e.message : "Unknown error" }, status);
        }
      },
    },
  },
});
