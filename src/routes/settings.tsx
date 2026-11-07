import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { AppNav } from "@/components/AppNav";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";
import { useSettings, type Settings } from "@/lib/store";
import { native, syncNativeState, type NativeStatus } from "@/lib/native";

export const Route = createFileRoute("/settings")({
  head: () => ({
    meta: [
      { title: "Settings — MXFF AI" },
      { name: "description", content: "Floating assistant, Hey MXFF wake word, language, AI mode, memory and permissions." },
      { property: "og:title", content: "Settings — MXFF AI" },
      { property: "og:description", content: "Configure MXFF AI voice, brains, privacy and Android permissions." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: SettingsPage,
});

function Row({ title, desc, children }: { title: string; desc?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-3">
      <div>
        <p className="font-medium">{title}</p>
        {desc && <p className="text-xs text-muted-foreground">{desc}</p>}
      </div>
      {children}
    </div>
  );
}

function Seg<T extends string>({ value, options, onChange }: { value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div className="flex rounded-full border p-0.5">
      {options.map(([v, l]) => (
        <button key={v} onClick={() => onChange(v)} className={`rounded-full px-3 py-1 text-xs ${value === v ? "bg-primary text-primary-foreground" : "text-muted-foreground"}`}>
          {l}
        </button>
      ))}
    </div>
  );
}

function SettingsPage() {
  const [s, set] = useSettings();
  const [st, setSt] = useState<NativeStatus | null>(null);
  const refresh = () => setSt(native.status());
  useEffect(() => {
    refresh();
    const off = native.on((e) => e.type === "status" && refresh());
    const t = setInterval(refresh, 3000);
    return () => {
      off();
      clearInterval(t);
    };
  }, []);

  const toggleNative = (key: keyof Pick<Settings, "overlay" | "wake">, on: boolean) => {
    const b = native.bridge();
    if (!b) {
      toast.error("Install the MXFF Android app to use this. Browsers cannot show overlays or listen in the background.");
      return;
    }
    const res = JSON.parse(key === "overlay" ? b.setOverlay(on) : b.setWake(on, s.language === "en" ? "en-US" : "bn-BD"));
    if (res.ok) set({ [key]: on });
    else toast.error(res.message);
    refresh();
  };

  const perm = (label: string, ok: boolean | undefined, name: string, why: string) => (
    <Row title={label} desc={why}>
      {ok ? <span className="text-xs text-success">Granted</span> : <Button size="sm" variant="outline" onClick={() => native.bridge()?.requestPermission(name)} disabled={!st}>Grant</Button>}
    </Row>
  );

  return (
    <div className="mx-auto max-w-2xl px-4 pb-24 pt-4">
      <h1 className="font-display text-lg text-primary">SETTINGS</h1>
      {!st && <p className="glass mt-3 rounded-lg p-3 text-sm text-muted-foreground">Running in a browser. Floating assistant, Hey MXFF and phone actions need the MXFF Android app.</p>}

      <section className="glass mt-4 divide-y rounded-xl px-4">
        <Row title="Floating Assistant" desc="Shadow orb above every app. Say “Hey MXFF” (or “Jarvis”) and it listens, then replies with a hologram (needs “Display over other apps” and the microphone).">
          <Switch checked={st ? st.overlayOn : s.overlay} onCheckedChange={(v) => toggleNative("overlay", v)} />
        </Row>
        <Row title="Hey MXFF" desc="Wake-word listening via a foreground service with a visible notification.">
          <Switch checked={st ? st.wakeOn : s.wake} onCheckedChange={(v) => toggleNative("wake", v)} />
        </Row>
        <Row title="Voice responses"><Switch checked={s.voiceReply} onCheckedChange={(v) => set({ voiceReply: v })} /></Row>
        <Row title="Continuous conversation" desc="Listen again after each spoken answer."><Switch checked={s.continuous} onCheckedChange={(v) => set({ continuous: v })} /></Row>
        <Row title="Language"><Seg value={s.language} options={[["bn", "বাংলা"], ["en", "English"], ["auto", "Auto"]]} onChange={(v) => set({ language: v })} /></Row>
      </section>

      <section className="glass mt-4 divide-y rounded-xl px-4">
        <Row title="AI mode" desc="Fast: Gemini AI 1 only. Verified: Gemini AI 2 double-checks (2 calls).">
          <Seg value={s.aiMode} options={[["fast", "Fast"], ["verified", "Verified"]]} onChange={(v) => set({ aiMode: v })} />
        </Row>
        <Row title="Gemini AI 1" desc="Main reasoning · google/gemini-3.8-flash · key held on server"><span className="text-xs text-success">Server</span></Row>
        <Row title="Gemini AI 2" desc="Verification · google/gemini-3.5-flash · key held on server"><span className="text-xs text-success">Server</span></Row>
      </section>

      <section className="glass mt-4 divide-y rounded-xl px-4">
        <Row title="Long-term memory" desc="Use saved facts in answers."><Switch checked={s.memoryEnabled} onCheckedChange={(v) => set({ memoryEnabled: v })} /></Row>
        <Row title="Let AI see screenshots during phone tasks" desc="Helps with videos and pictures. Off = only the screen's text is sent. Nothing is sent when no task is running."><Switch checked={s.screenVision} onCheckedChange={(v) => { set({ screenVision: v }); setTimeout(syncNativeState, 50); }} /></Row>
        <Row title="Save conversation history" desc="Stored only on this device."><Switch checked={s.saveHistory} onCheckedChange={(v) => set({ saveHistory: v })} /></Row>
      </section>

      <h2 className="mt-6 font-display text-sm text-muted-foreground">PERMISSIONS</h2>
      <section className="glass mt-2 divide-y rounded-xl px-4">
        {perm("Microphone", st?.micPermission, "mic", "Voice input and Hey MXFF.")}
        {perm("Display over other apps", st?.overlayPermission, "overlay", "Floating Assistant.")}
        {perm("Notifications", st?.postNotifications, "notifications", "Foreground service notice (Android 13+).")}
        {perm("Send SMS", st?.smsPermission, "sms", "Only after you confirm a message.")}
        {perm("Phone calls", st?.callPermission, "call", "Only after you confirm a call.")}
        {perm("Phone control (Accessibility)", st?.accessibilityOn, "accessibility", "Lets MXFF see the screen and tap, type and scroll in other apps (TikTok, WhatsApp, Play Store …). It asks before sending a message, installing an app or paying. On Android 13+: if the switch is greyed out, open App info → ⋮ → “Allow restricted settings” first.")}
        {!st?.accessibilityOn && <Row title="App info" desc="Needed once on Android 13+ for apps installed from an APK."><Button size="sm" variant="outline" onClick={() => native.bridge()?.requestPermission("app_info")} disabled={!st}>Open</Button></Row>}
        {perm("Notification access", st?.notificationAccess, "notification_access", "Read notifications when you ask.")}
      </section>
      <AppNav />
    </div>
  );
}
