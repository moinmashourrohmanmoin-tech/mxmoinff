import { streamText } from "ai";
import { createLovableAiGatewayProvider } from "./ai/gateway.server";

// Gemini AI 1 = main reasoning/planning, Gemini AI 2 = verification.
export const GEMINI_1 = "google/gemini-3.8-flash";
export const GEMINI_2 = "google/gemini-3.5-flash";
const GATEWAY = "https://ai.gateway.lovable.dev/v1";

export type BrainMsg = { role: "user" | "assistant"; content: string };
export type BrainInput = {
  messages: BrainMsg[];
  language: "bn" | "en" | "auto";
  mode: "fast" | "verified";
  memory: string[];
  /** Hands-free spoken reply shown in the floating Android shadow. */
  voice?: boolean;
};

export class BrainError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

const SYSTEM = (lang: string, memory: string[], voice = false) => `You are MXFF AI, a calm, precise JARVIS-style personal assistant running on the user's Android phone.
Language: ${lang === "bn" ? "Always reply in Bengali (বাংলা)." : lang === "en" ? "Always reply in English." : "Reply in the same language the user used (Bengali or English)."}
Keep spoken replies short (1-4 sentences) unless the user asks for detail. No markdown tables.${voice ? "\nThis reply is spoken aloud by a small floating assistant: at most 2 short sentences, no lists, no emojis, no markdown, no URLs." : ""}
You can trigger ONE phone action by adding a final line exactly like:
ACTION: {"type":"<type>", ...}
Types: open_app{"name"}, open_url{"url"}, google_search{"query"}, youtube_search{"query"}, web_search{"query"}, open_settings{}, open_camera{}, open_maps{"query"}, reminder{"text","minutesFromNow"}, whatsapp{"phone","text"}, sms{"phone","text"}, call{"phone"}, torch{"on":true|false}, volume{"direction":"up|down|mute|unmute|max"}, alarm{"hour":0-23,"minute":0-59,"label"}, timer{"seconds"}, media{"command":"play_pause|next|previous"}.
agent{"goal"} = operate other apps on the phone for the user, step by step (the phone shows and presses things itself). Use agent for ANYTHING that needs working inside an app or looking at the screen: playing a song or video in TikTok/YouTube/Spotify, opening an inbox or a chat and reading messages aloud, replying to or writing a message to someone in WhatsApp/Messenger/TikTok/Instagram/Telegram, scrolling a feed, installing an app from Play Store, or "what am I watching / what is on my screen". Put the whole task in "goal" as one complete sentence in the user's own words, keeping app names, person names and song names exactly (e.g. {"type":"agent","goal":"TikTok-এ গিয়ে দীপানিতা গানটা সার্চ করে চালাও"}). If the app is missing the phone asks the user for permission to install it. Messages are only sent after the user says yes by voice. Use open_app (not agent) when the user only wants an app opened.
For an agent action reply with ONE short sentence like "ঠিক আছে, করছি।" and then the ACTION line.
Only add ACTION when the user clearly asks for it. Never claim an action is completed; the app confirms it.
${memory.length ? `Things the user asked you to remember:\n- ${memory.join("\n- ")}` : ""}`;

async function run(model: string, system: string, messages: BrainMsg[]) {
  const key = process.env["LOVABLE_API_KEY"];
  if (!key) throw new BrainError("AI service is not configured on the server.", 401);
  const provider = createLovableAiGatewayProvider(key, undefined, { baseURL: GATEWAY });
  let failure: unknown;
  const result = streamText({
    model: provider(model),
    system,
    messages,
    onError: ({ error }) => {
      failure = error;
    },
  });
  let text = "";
  for await (const part of result.textStream) text += part;
  if (failure) throw toBrainError(failure);
  if (!text.trim()) throw new BrainError("The AI returned an empty answer.", 502);
  return text.trim();
}

function toBrainError(e: unknown): BrainError {
  const status = (e as { statusCode?: number })?.statusCode ?? 500;
  if (status === 402) return new BrainError("AI credits are used up for this workspace.", 402);
  if (status === 429) return new BrainError("Too many requests. Please wait a moment.", 429);
  if (status === 403) return new BrainError("AI access was denied for this request.", 403);
  return new BrainError("The AI service is unavailable right now.", status);
}

export async function askBrain(input: BrainInput) {
  const msgs = input.messages.slice(-16).map((m) => ({ role: m.role, content: m.content.slice(0, 4000) }));
  const system = SYSTEM(input.language, input.memory.slice(0, 30), !!input.voice);
  const draft = await run(GEMINI_1, system, msgs);
  if (input.mode !== "verified") return { text: draft, models: [GEMINI_1], verified: false };
  // Second model only reviews when the user enabled verified mode.
  const lastUser = [...msgs].reverse().find((m) => m.role === "user")?.content ?? "";
  const review = await run(
    GEMINI_2,
    `${system}\nYou are the verification brain. Check the draft answer for factual or logical errors. If it is correct, return it unchanged. If not, return a corrected version in the same format (keep any ACTION line).`,
    [{ role: "user", content: `User question:\n${lastUser}\n\nDraft answer:\n${draft}` }],
  );
  return { text: review, models: [GEMINI_1, GEMINI_2], verified: true };
}
