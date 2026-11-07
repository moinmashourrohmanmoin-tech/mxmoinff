// Local, free command parser (no AI call needed for obvious commands).
import type { PendingAction } from "./store";

export function parseLocalIntent(raw: string): PendingAction | null {
  const t = raw.trim();
  const l = t.toLowerCase();
  let m: RegExpMatchArray | null;

  if ((m = l.match(/^(?:search |find )?(.+?) (?:on|in) youtube$/)) || (m = l.match(/^youtube(?: e| te)? (.+?)(?: search| khojo| খোঁজো)?$/)))
    return { type: "youtube_search", query: m[1] ?? "" };
  if ((m = t.match(/^(.+?) ইউটিউবে (?:খোঁজো|সার্চ কর(?:ো)?|চালাও)$/))) return { type: "youtube_search", query: m[1] ?? "" };
  if ((m = l.match(/^(?:google|search google for|google search) (.+)$/))) return { type: "google_search", query: m[1] ?? "" };
  if ((m = t.match(/^(.+?) গুগলে (?:খোঁজো|সার্চ কর(?:ো)?)$/))) return { type: "google_search", query: m[1] ?? "" };
  if (/^open youtube$|^ইউটিউব খোল(?:ো)?$/.test(l)) return { type: "open_app", name: "youtube" };
  if (/^open (?:the )?camera$|^ক্যামেরা খোল(?:ো)?$/.test(l)) return { type: "open_camera" };
  if (/^open (?:phone )?settings$|^সেটিংস খোল(?:ো)?$/.test(l)) return { type: "open_settings" };
  if ((m = l.match(/^(?:open maps?|navigate to|directions to) ?(.*)$/))) return { type: "open_maps", query: m[1] ?? "" };
  if ((m = l.match(/^open (https?:\/\/\S+|[\w-]+\.[\w.]+\S*)$/))) return { type: "open_url", url: m[1]?.startsWith("http") ? m[1] : `https://${m[1]}` };
  if ((m = l.match(/^open (.+)$/)) || (m = t.match(/^(.+?) খোল(?:ো)?$/))) return { type: "open_app", name: m[1] ?? "" };
  if ((m = l.match(/^call (\+?[\d\s-]{5,})$/))) return { type: "call", phone: (m[1] ?? "").replace(/[\s-]/g, "") };
  return null;
}

export function extractAction(text: string): { clean: string; action: PendingAction | null } {
  const m = text.match(/\n?ACTION:\s*(\{.*\})\s*$/s);
  if (!m) return { clean: text, action: null };
  try {
    return { clean: text.slice(0, m.index).trim(), action: JSON.parse(m[1] ?? "null") };
  } catch {
    return { clean: text.slice(0, m.index).trim(), action: null };
  }
}

export const SENSITIVE = new Set(["sms", "call", "whatsapp"]);

export function describeAction(a: PendingAction): string {
  switch (a.type) {
    case "sms":
      return `Send SMS to ${a.phone}: “${a.text}”`;
    case "call":
      return `Call ${a.phone}`;
    case "whatsapp":
      return `Open WhatsApp chat with ${a.phone}${a.text ? ` with message “${a.text}”` : ""}`;
    case "agent":
      return `Phone task: ${a.goal ?? ""}`;
    case "reminder":
      return `Set reminder “${a.text}” in ${a.minutesFromNow ?? 10} min`;
    default:
      return `${a.type.replace(/_/g, " ")} ${(a.query ?? a.name ?? a.url ?? "") as string}`.trim();
  }
}

// Browser fallback when the Android companion is not present.
export function webFallbackUrl(a: PendingAction): string | null {
  const q = encodeURIComponent(String(a.query ?? a.name ?? ""));
  switch (a.type) {
    case "google_search":
      return `https://www.google.com/search?q=${q}`;
    case "youtube_search":
      return `https://www.youtube.com/results?search_query=${q}`;
    case "open_url":
      return String(a.url);
    case "open_maps":
      return `https://www.google.com/maps/search/${q}`;
    case "whatsapp":
      return `https://wa.me/${String(a.phone).replace(/\D/g, "")}?text=${encodeURIComponent(String(a.text ?? ""))}`;
    case "call":
      return `tel:${a.phone}`;
    case "sms":
      return `sms:${a.phone}?body=${encodeURIComponent(String(a.text ?? ""))}`;
    case "open_app":
      return String(a.name).toLowerCase().includes("youtube") ? "https://www.youtube.com" : null;
    default:
      return null;
  }
}
