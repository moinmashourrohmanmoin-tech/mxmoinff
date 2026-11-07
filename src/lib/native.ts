// Interface between the MXFF web app and the Android Kotlin companion.
// Android injects `window.MXFFNative` (see android/app/src/main/java/com/mxff/ai/NativeBridge.kt)
// and pushes events by calling `window.MXFF_onNative(jsonString)`.

export type NativeEvent =
  | { type: "stt_partial" | "stt_result"; text: string }
  | { type: "stt_state"; listening: boolean }
  | { type: "stt_error"; message: string }
  | { type: "tts_state"; speaking: boolean }
  | { type: "wake"; text?: string }
  | { type: "action_result"; ok: boolean; message: string }
  | { type: "status" };

export type NativeStatus = {
  overlayPermission: boolean;
  micPermission: boolean;
  smsPermission: boolean;
  callPermission: boolean;
  notificationAccess: boolean;
  accessibilityOn: boolean;
  postNotifications: boolean;
  overlayOn: boolean;
  wakeOn: boolean;
  androidVersion: number;
};

type Bridge = {
  action(json: string): string;
  getStatus(): string;
  setOverlay(on: boolean): string;
  setWake(on: boolean, lang: string): string;
  startListening(lang: string): void;
  stopListening(): void;
  speak(text: string, lang: string): void;
  stopSpeaking(): void;
  requestPermission(name: string): void;
  getNotifications(): string;
  emergencyStop(): void;
  syncState?(json: string): void;
};

declare global {
  interface Window {
    MXFFNative?: Bridge;
    MXFF_onNative?: (json: string) => void;
  }
}

const listeners = new Set<(e: NativeEvent) => void>();
if (typeof window !== "undefined") {
  window.MXFF_onNative = (json: string) => {
    try {
      const e = JSON.parse(json) as NativeEvent;
      listeners.forEach((l) => l(e));
    } catch {
      /* ignore malformed */
    }
  };
}

export const native = {
  get available() {
    return typeof window !== "undefined" && !!window.MXFFNative;
  },
  on(l: (e: NativeEvent) => void) {
    listeners.add(l);
    return () => listeners.delete(l);
  },
  bridge(): Bridge | undefined {
    return typeof window !== "undefined" ? window.MXFFNative : undefined;
  },
  status(): NativeStatus | null {
    const b = this.bridge();
    if (!b) return null;
    try {
      return JSON.parse(b.getStatus());
    } catch {
      return null;
    }
  },
};

/** Push language / voice-reply / memory to the Android shadow (it runs outside the WebView). */
export function syncNativeState() {
  const b = native.bridge();
  if (!b?.syncState) return;
  try {
    const st = JSON.parse(localStorage.getItem("mxff.settings") ?? "{}");
    const mem = JSON.parse(localStorage.getItem("mxff.memory") ?? "[]") as { text?: string }[];
    b.syncState(
      JSON.stringify({
        language: st.language ?? "bn",
        voiceReply: st.voiceReply ?? true,
        screenVision: st.screenVision ?? true,
        memory: st.memoryEnabled === false ? [] : mem.slice(0, 30).map((m) => String(m.text ?? "")).filter(Boolean),
      }),
    );
  } catch {
    /* ignore */
  }
}
