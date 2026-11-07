import { Link } from "@tanstack/react-router";
import { Brain, MessageSquare, Settings } from "lucide-react";

export function AppNav() {
  const item = "flex flex-1 flex-col items-center gap-0.5 py-2 text-xs text-muted-foreground";
  const active = { className: `${item} text-primary` };
  return (
    <nav className="glass fixed inset-x-0 bottom-0 z-20 flex border-t pb-[env(safe-area-inset-bottom)]">
      <Link to="/" className={item} activeProps={active} activeOptions={{ exact: true }}>
        <MessageSquare className="h-5 w-5" /> Chat
      </Link>
      <Link to="/memory" className={item} activeProps={active}>
        <Brain className="h-5 w-5" /> Memory
      </Link>
      <Link to="/settings" className={item} activeProps={active}>
        <Settings className="h-5 w-5" /> Settings
      </Link>
    </nav>
  );
}
