import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { Trash2 } from "lucide-react";
import { AppNav } from "@/components/AppNav";
import { Button } from "@/components/ui/button";
import { uid, useChat, useMemory, useSearches, useTasks, type LogItem } from "@/lib/store";
import { native } from "@/lib/native";

export const Route = createFileRoute("/memory")({
  head: () => ({
    meta: [
      { title: "Memory — MXFF AI" },
      { name: "description", content: "Review and delete what MXFF AI remembers, your search and task history." },
      { property: "og:title", content: "Memory — MXFF AI" },
      { property: "og:description", content: "Saved instructions, preferences, searches and tasks." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: MemoryPage,
});

function List({ title, items, onDelete, onClear }: { title: string; items: LogItem[]; onDelete: (id: string) => void; onClear: () => void }) {
  return (
    <section className="glass mt-4 rounded-xl p-4">
      <div className="flex items-center justify-between">
        <h2 className="font-display text-sm text-primary">{title}</h2>
        {items.length > 0 && <Button size="sm" variant="ghost" onClick={onClear}>Clear all</Button>}
      </div>
      {items.length === 0 ? <p className="mt-2 text-sm text-muted-foreground">Nothing yet.</p> : (
        <ul className="mt-2 divide-y">
          {items.map((i) => (
            <li key={i.id} className="flex items-center justify-between gap-2 py-2 text-sm">
              <span>{i.text}<span className="block text-[10px] text-muted-foreground">{new Date(i.at).toLocaleString()}</span></span>
              <Button size="icon" variant="ghost" onClick={() => onDelete(i.id)} aria-label="Delete"><Trash2 className="h-4 w-4" /></Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function MemoryPage() {
  const [mem, setMem] = useMemory();
  const [searches, setSearches] = useSearches();
  const [tasks, setTasks] = useTasks();
  const [chat, setChat] = useChat();
  const [text, setText] = useState("");
  const [notes, setNotes] = useState<{ app: string; title: string; text: string }[] | null>(null);

  return (
    <div className="mx-auto max-w-2xl px-4 pb-24 pt-4">
      <h1 className="font-display text-lg text-primary">MEMORY</h1>
      <form className="mt-3 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (text.trim()) { setMem((m) => [{ id: uid(), text: text.trim(), at: Date.now() }, ...m]); setText(""); } }}>
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder="e.g. My name is Moin. Prefer short answers." className="flex-1 rounded-full border bg-input/20 px-4 py-2 outline-none" />
        <Button type="submit">Save</Button>
      </form>
      <List title="SAVED PREFERENCES & INSTRUCTIONS" items={mem} onDelete={(id) => setMem((m) => m.filter((x) => x.id !== id))} onClear={() => setMem([])} />
      <List title="SEARCH HISTORY" items={searches} onDelete={(id) => setSearches((m) => m.filter((x) => x.id !== id))} onClear={() => setSearches([])} />
      <List title="TASK HISTORY" items={tasks} onDelete={(id) => setTasks((m) => m.filter((x) => x.id !== id))} onClear={() => setTasks([])} />
      <section className="glass mt-4 flex items-center justify-between rounded-xl p-4">
        <p className="text-sm">Conversation history: {chat.length} messages</p>
        <Button size="sm" variant="outline" onClick={() => setChat([])}>Delete</Button>
      </section>
      <section className="glass mt-4 rounded-xl p-4">
        <div className="flex items-center justify-between">
          <h2 className="font-display text-sm text-primary">RECENT NOTIFICATIONS</h2>
          <Button size="sm" variant="outline" disabled={!native.available} onClick={() => setNotes(JSON.parse(native.bridge()!.getNotifications()))}>Read</Button>
        </div>
        {!native.available && <p className="mt-2 text-xs text-muted-foreground">Android app with notification access required.</p>}
        {notes && (notes.length ? notes.map((n, i) => <p key={i} className="mt-2 text-sm"><b>{n.app}</b> · {n.title}: {n.text}</p>) : <p className="mt-2 text-sm text-muted-foreground">No notifications (or access not granted).</p>)}
      </section>
      <AppNav />
    </div>
  );
}
