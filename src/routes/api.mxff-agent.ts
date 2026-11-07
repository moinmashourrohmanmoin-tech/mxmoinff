import { createFileRoute } from "@tanstack/react-router";

// HTTP endpoint for the Android phone-operating agent (ScreenAgent.kt).
// POST /api/mxff-agent { goal, language, step, pkg, screen, history, image? } -> { ok, step } | { ok:false, error }
// Protected by the same optional MXFF_APP_TOKEN as /api/mxff-ask.

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });

export const Route = createFileRoute("/api/mxff-agent")({
  server: {
    handlers: {
      POST: async ({ request }: { request: Request }) => {
        const token = process.env["MXFF_APP_TOKEN"];
        if (token && request.headers.get("x-mxff-token") !== token) return json({ ok: false, error: "Unauthorized" }, 401);

        const { agentInputSchema, askAgent } = await import("../lib/agent.server");
        const { BrainError } = await import("../lib/brain.server");
        let body;
        try {
          body = agentInputSchema.parse(await request.json());
        } catch {
          return json({ ok: false, error: "Bad request" }, 400);
        }
        try {
          return json({ ok: true, step: await askAgent(body) });
        } catch (e) {
          const status = e instanceof BrainError ? e.status : 500;
          return json({ ok: false, error: e instanceof Error ? e.message : "Unknown error" }, status);
        }
      },
    },
  },
});
