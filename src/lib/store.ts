import { useEffect, useState } from "react";

export type Settings = {
  language: "bn" | "en" | "auto";
  voiceReply: boolean;
  aiMode: "fast" | "verified";
  memoryEnabled: boolean;
  overlay: boolean;
  wake: boolean;
  continuous: boolean;
  saveHistory: boolean;
};

export const DEFAULT_SETTINGS: Settings = {
  language: "bn",
  voiceReply: true,
  aiMode: "fast",
  memoryEnabled: true,
  overlay: false,
  wake: false,
  continuous: false,
  saveHistory: true,
};

export type ChatMsg = {
  id: string;
  role: "user" | "assistant";
  content: string;
  at: number;
  meta?: { models?: string[]; verified?: boolean; error?: boolean };
  web?: { title: string; url: string; snippet: string }[];
  videos?: { id: string; title: string; channel: string; thumbnail: string; url: string }[];
  pending?: PendingAction | undefined;
};

export type PendingAction = {
  type: string;
  query?: string | undefined;
  name?: string | undefined;
  url?: string | undefined;
  phone?: string | undefined;
  text?: string | undefined;
  minutesFromNow?: number | undefined;
};
export type LogItem = { id: string; text: string; at: number };

const K = {
  settings: "mxff.settings",
  chat: "mxff.chat",
  memory: "mxff.memory",
  searches: "mxff.searches",
  tasks: "mxff.tasks",
};

function read<T>(k: string, d: T): T {
  if (typeof window === "undefined") return d;
  try {
    const v = localStorage.getItem(k);
    return v ? (JSON.parse(v) as T) : d;
  } catch {
    return d;
  }
}
function write(k: string, v: unknown) {
  localStorage.setItem(k, JSON.stringify(v));
  window.dispatchEvent(new CustomEvent("mxff-store", { detail: k }));
}

function useKey<T>(k: string, d: T) {
  const [v, setV] = useState<T>(d);
  useEffect(() => {
    setV(read(k, d));
    const h = (e: Event) => (e as CustomEvent).detail === k && setV(read(k, d));
    window.addEventListener("mxff-store", h);
    return () => window.removeEventListener("mxff-store", h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [k]);
  const set = (nv: T | ((p: T) => T)) => {
    const next = typeof nv === "function" ? (nv as (p: T) => T)(read(k, d)) : nv;
    write(k, next);
  };
  return [v, set] as const;
}

export const useSettings = () => {
  const [s, set] = useKey<Settings>(K.settings, DEFAULT_SETTINGS);
  return [{ ...DEFAULT_SETTINGS, ...s }, (p: Partial<Settings>) => set((o) => ({ ...DEFAULT_SETTINGS, ...o, ...p }))] as const;
};
export const useChat = () => useKey<ChatMsg[]>(K.chat, []);
export const useMemory = () => useKey<LogItem[]>(K.memory, []);
export const useSearches = () => useKey<LogItem[]>(K.searches, []);
export const useTasks = () => useKey<LogItem[]>(K.tasks, []);

export function appendLog(key: "searches" | "tasks", text: string) {
  const arr = read<LogItem[]>(K[key], []);
  write(K[key], [{ id: crypto.randomUUID(), text, at: Date.now() }, ...arr].slice(0, 200));
}
export const uid = () => crypto.randomUUID();
