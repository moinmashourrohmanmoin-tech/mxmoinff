import { native } from "./native";

export const langCode = (l: "bn" | "en" | "auto", sample = "") =>
  l === "en" ? "en-US" : l === "bn" ? "bn-BD" : /[\u0980-\u09FF]/.test(sample) ? "bn-BD" : "en-US";

type SR = { lang: string; interimResults: boolean; continuous: boolean; start(): void; stop(): void; onresult: ((e: any) => void) | null; onend: (() => void) | null; onerror: ((e: any) => void) | null };

let webRec: SR | null = null;

export function startListening(
  lang: string,
  cb: { partial: (t: string) => void; final: (t: string) => void; state: (on: boolean) => void; error: (m: string) => void },
) {
  const b = native.bridge();
  if (b) {
    b.startListening(lang);
    return;
  }
  const Ctor = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
  if (!Ctor) return cb.error("Speech recognition is not supported in this browser.");
  webRec = new Ctor() as SR;
  webRec.lang = lang;
  webRec.interimResults = true;
  webRec.continuous = false;
  webRec.onresult = (e) => {
    const r = e.results[e.results.length - 1];
    (r.isFinal ? cb.final : cb.partial)(r[0].transcript);
  };
  webRec.onerror = (e) => cb.error(e.error === "not-allowed" ? "Microphone permission denied." : `Speech error: ${e.error}`);
  webRec.onend = () => cb.state(false);
  cb.state(true);
  webRec.start();
}

export function stopListening() {
  native.bridge()?.stopListening();
  webRec?.stop();
}

export function speak(text: string, lang: string, onState: (s: boolean) => void) {
  const clean = text.replace(/[*_#`>]/g, "");
  const b = native.bridge();
  if (b) return b.speak(clean, lang);
  if (!("speechSynthesis" in window)) return;
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(clean);
  u.lang = lang;
  const v = speechSynthesis.getVoices().find((x) => x.lang.startsWith(lang.slice(0, 2)));
  if (v) u.voice = v;
  u.onstart = () => onState(true);
  u.onend = u.onerror = () => onState(false);
  speechSynthesis.speak(u);
}

export function stopSpeaking() {
  native.bridge()?.stopSpeaking();
  if (typeof window !== "undefined" && "speechSynthesis" in window) speechSynthesis.cancel();
}
