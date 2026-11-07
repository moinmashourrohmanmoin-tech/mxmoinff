import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { Mic, MicOff, Send, Square, Trash2, Volume2, ExternalLink, ShieldAlert } from "lucide-react";
import { toast } from "sonner";
import { Orb, type OrbState } from "@/components/Orb";
import { AppNav } from "@/components/AppNav";
import { Button } from "@/components/ui/button";
import { askMxff, searchMxff } from "@/lib/mxff.functions";
import { appendLog, uid, useChat, useMemory, useSettings, type ChatMsg, type PendingAction } from "@/lib/store";
import { describeAction, extractAction, parseLocalIntent, SENSITIVE, webFallbackUrl } from "@/lib/intents";
import { langCode, speak, startListening, stopListening, stopSpeaking } from "@/lib/voice";
import { native } from "@/lib/native";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "MXFF AI — Your holographic assistant" },
      { name: "description", content: "Talk to MXFF AI in Bengali or English: voice chat, phone actions and web search." },
      { property: "og:title", content: "MXFF AI — Your holographic assistant" },
      { property: "og:description", content: "Bengali and English voice assistant with phone automation." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: ChatPage,
});

function ChatPage() {
  const [settings] = useSettings();
  const [chat, setChat] = useChat();
  const [memory] = useMemory();
  const [input, setInput] = useState("");
  const [state, setState] = useState<OrbState>("idle");
  const [partial, setPartial] = useState("");
  const endRef = useRef<HTMLDivElement>(null);
  const sendRef = useRef<(t: string) => void>(() => {});

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [chat.length, state]);

  const push = (m: ChatMsg) => setChat((c) => (settings.saveHistory ? [...c, m] : [...c, m].slice(-30)));
  const say = (text: string) => {
    if (!settings.voiceReply) return;
    setState("speaking");
    speak(text, langCode(settings.language, text), (s) => {
      setState(s ? "speaking" : "idle");
      if (!s && settings.continuous) listen();
    });
  };

  // Native events (Android companion)
  useEffect(
    () =>
      { const off = native.on((e) => {
        if (e.type === "stt_partial") setPartial(e.text);
        if (e.type === "stt_result") {
          setPartial("");
          sendRef.current(e.text);
        }
        if (e.type === "stt_state") setState((s) => (e.listening ? "listening" : s === "listening" ? "idle" : s));
        if (e.type === "stt_error") {
          setState("idle");
          toast.error(e.message);
        }
        if (e.type === "tts_state") {
          setState(e.speaking ? "speaking" : "idle");
          if (!e.speaking && settings.continuous) listen();
        }
        if (e.type === "wake") listen();
        if (e.type === "action_result") {
          (e.ok ? toast.success : toast.error)(e.message);
          appendLog("tasks", `${e.ok ? "✓" : "✗"} ${e.message}`);
        }
      }); return () => { off(); }; },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [settings.continuous],
  );

  function listen() {
    stopSpeaking();
    startListening(langCode(settings.language), {
      partial: setPartial,
      final: (t) => {
        setPartial("");
        sendRef.current(t);
      },
      state: (on) => setState((s) => (on ? "listening" : s === "listening" ? "idle" : s)),
      error: (m) => {
        setState("idle");
        toast.error(m);
      },
    });
  }

  async function runAction(a: PendingAction, confirmed = false): Promise<string> {
    if (SENSITIVE.has(a.type) && !confirmed) return "confirm";
    appendLog("tasks", describeAction(a));
    if (a.type === "web_search" || a.type === "youtube_search") {
      const kind = a.type === "youtube_search" ? "video" : "web";
      appendLog("searches", String(a.query));
      const r = await searchMxff({ data: { query: String(a.query), kind } });
      if (!r.ok) return `Search failed: ${r.error}`;
      push({ id: uid(), role: "assistant", content: kind === "video" ? `YouTube results for “${a.query}”` : `Web results for “${a.query}”`, at: Date.now(), web: r.web, videos: r.videos });
      return "";
    }
    const b = native.bridge();
    if (b) {
      const res = JSON.parse(b.action(JSON.stringify({ ...a, confirmed })));
      if (!res.ok) toast.error(res.message);
      return "";
    }
    const url = webFallbackUrl(a);
    if (!url) {
      toast.error("This action needs the MXFF Android app.");
      return "";
    }
    window.open(url, "_blank");
    return "";
  }

  async function send(text: string) {
    const t = text.trim();
    if (!t || state === "thinking") return;
    setInput("");
    const user: ChatMsg = { id: uid(), role: "user", content: t, at: Date.now() };
    push(user);

    const local = parseLocalIntent(t);
    if (local) {
      if (SENSITIVE.has(local.type)) {
        push({ id: uid(), role: "assistant", content: "Please confirm this action:", at: Date.now(), pending: local });
        return;
      }
      await runAction(local);
      if (local.type !== "youtube_search") push({ id: uid(), role: "assistant", content: `▶ ${describeAction(local)}`, at: Date.now() });
      return;
    }

    setState("thinking");
    const history = [...chat, user].filter((m) => !m.meta?.error).slice(-16).map((m) => ({ role: m.role, content: m.content }));
    const res = await askMxff({
      data: { messages: history, language: settings.language, mode: settings.aiMode, memory: settings.memoryEnabled ? memory.map((m) => m.text) : [] },
    }).catch(() => ({ ok: false as const, status: 0, error: "Network error. Check your internet connection." }));
    if (!res.ok) {
      setState("idle");
      push({ id: uid(), role: "assistant", content: res.error, at: Date.now(), meta: { error: true } });
      return;
    }
    const { clean, action } = extractAction(res.text);
    push({ id: uid(), role: "assistant", content: clean, at: Date.now(), meta: { models: res.models, verified: res.verified }, pending: action && SENSITIVE.has(action.type) ? action : undefined });
    setState("idle");
    if (action && !SENSITIVE.has(action.type)) await runAction(action);
    if (clean) say(clean);
  }
  sendRef.current = send;

  const confirm = async (m: ChatMsg) => {
    if (!m.pending) return;
    await runAction(m.pending, true);
    setChat((c) => c.map((x) => (x.id === m.id ? { ...x, pending: undefined, content: `${x.content}\n✔ Confirmed: ${describeAction(m.pending!)}` } : x)));
  };
  const cancel = (m: ChatMsg) => setChat((c) => c.map((x) => (x.id === m.id ? { ...x, pending: undefined, content: `${x.content}\n✖ Cancelled` } : x)));

  const stopAll = () => {
    stopSpeaking();
    stopListening();
    native.bridge()?.emergencyStop();
    setState("idle");
    toast("Emergency stop: voice and automation halted.");
  };

  return (
    <div className="mx-auto flex min-h-dvh max-w-2xl flex-col pb-36">
      <header className="flex items-center justify-between px-4 pt-4">
        <h1 className="font-display text-lg text-primary">MXFF AI</h1>
        <div className="flex gap-1">
          <Button size="icon" variant="ghost" onClick={stopSpeaking} aria-label="Stop speaking"><Volume2 /></Button>
          <Button size="icon" variant="ghost" onClick={() => setChat([])} aria-label="Clear chat"><Trash2 /></Button>
          <Button size="icon" variant="ghost" onClick={stopAll} aria-label="Emergency stop" className="text-destructive"><ShieldAlert /></Button>
        </div>
      </header>

      <section className="py-4">
        <Orb state={state} size={chat.length ? 140 : 230} />
        <p className="mt-2 text-center font-display text-xs text-muted-foreground uppercase">
          {state === "idle" ? (settings.wake ? "Say “Hey MXFF”" : "Ready") : state}
        </p>
        {partial && <p className="mt-1 text-center text-primary">{partial}</p>}
      </section>

      <main className="flex-1 space-y-3 px-4">
        {chat.map((m) => (
          <div key={m.id} className={m.role === "user" ? "ml-10 rounded-2xl rounded-br-sm bg-secondary px-4 py-2" : `glass mr-6 rounded-2xl rounded-bl-sm px-4 py-2 ${m.meta?.error ? "border-destructive text-destructive" : ""}`}>
            <p className="whitespace-pre-wrap text-[15px] leading-snug">{m.content}</p>
            {m.meta?.models && <p className="mt-1 text-[10px] text-muted-foreground">{m.meta.verified ? "Gemini 1 + verified by Gemini 2" : "Gemini 1"}</p>}
            {m.web?.length ? (
              <ul className="mt-2 space-y-2">
                {m.web.map((w) => (
                  <li key={w.url}><a href={w.url} target="_blank" rel="noreferrer" className="text-primary underline">{w.title}</a><p className="text-xs text-muted-foreground">{w.snippet}</p></li>
                ))}
              </ul>
            ) : m.web && !m.videos?.length ? <p className="text-xs text-muted-foreground">No results.</p> : null}
            {m.videos?.length ? (
              <div className="mt-2 grid grid-cols-2 gap-2">
                {m.videos.map((v) => (
                  <button key={v.id} onClick={() => runAction({ type: "open_url", url: v.url })} className="text-left">
                    <img src={v.thumbnail} alt={v.title} className="aspect-video w-full rounded-md object-cover" loading="lazy" />
                    <p className="mt-1 line-clamp-2 text-xs">{v.title}</p>
                    <p className="text-[10px] text-muted-foreground">{v.channel}</p>
                  </button>
                ))}
              </div>
            ) : null}
            {m.pending && (
              <div className="mt-2 rounded-lg border border-primary/40 p-2">
                <p className="text-sm">{describeAction(m.pending)}</p>
                <div className="mt-2 flex gap-2">
                  <Button size="sm" onClick={() => confirm(m)}>Confirm</Button>
                  <Button size="sm" variant="outline" onClick={() => cancel(m)}>Cancel</Button>
                </div>
              </div>
            )}
            {m.role === "assistant" && /https?:\/\//.test(m.content) && <ExternalLink className="mt-1 h-3 w-3 text-muted-foreground" />}
          </div>
        ))}
        <div ref={endRef} />
      </main>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          send(input);
        }}
        className="glass fixed inset-x-0 bottom-14 z-10 mx-auto flex max-w-2xl gap-2 p-3"
      >
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={settings.language === "en" ? "Ask MXFF anything…" : "MXFF-কে কিছু জিজ্ঞেস করুন…"}
          className="flex-1 rounded-full border bg-input/20 px-4 py-2 outline-none focus:ring-2 focus:ring-ring"
        />
        {state === "listening" ? (
          <Button type="button" size="icon" variant="destructive" className="rounded-full" onClick={stopListening} aria-label="Stop listening"><MicOff /></Button>
        ) : (
          <Button type="button" size="icon" variant="secondary" className="rounded-full" onClick={listen} aria-label="Voice input"><Mic /></Button>
        )}
        {state === "speaking" ? (
          <Button type="button" size="icon" className="rounded-full" onClick={stopSpeaking} aria-label="Stop"><Square /></Button>
        ) : (
          <Button type="submit" size="icon" className="glow rounded-full" disabled={state === "thinking"} aria-label="Send"><Send /></Button>
        )}
      </form>
      <AppNav />
    </div>
  );
}
