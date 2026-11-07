export type OrbState = "idle" | "listening" | "thinking" | "speaking";

export function Orb({ state, size = 220 }: { state: OrbState; size?: number }) {
  return (
    <div data-state={state} className="relative mx-auto" style={{ width: size, height: size }} aria-label={`MXFF ${state}`}>
      <svg viewBox="0 0 200 200" className="absolute inset-0 h-full w-full">
        <defs>
          <radialGradient id="core" cx="50%" cy="40%" r="60%">
            <stop offset="0%" stopColor="var(--accent-foreground)" />
            <stop offset="35%" stopColor="var(--glow)" />
            <stop offset="100%" stopColor="var(--accent)" stopOpacity="0" />
          </radialGradient>
          <filter id="blur"><feGaussianBlur stdDeviation="3" /></filter>
        </defs>
        <g className="orb-ring-a" style={{ transformOrigin: "100px 100px" }}>
          <circle cx="100" cy="100" r="92" fill="none" stroke="var(--glow)" strokeOpacity=".35" strokeWidth="1" strokeDasharray="2 6" />
          <path d="M100 8 A92 92 0 0 1 192 100" fill="none" stroke="var(--glow)" strokeWidth="2.5" />
        </g>
        <g className="orb-ring-b" style={{ transformOrigin: "100px 100px" }}>
          <ellipse cx="100" cy="100" rx="74" ry="30" fill="none" stroke="var(--primary)" strokeOpacity=".7" strokeWidth="1.5" transform="rotate(25 100 100)" />
          <ellipse cx="100" cy="100" rx="74" ry="30" fill="none" stroke="var(--accent)" strokeOpacity=".7" strokeWidth="1.5" transform="rotate(-35 100 100)" />
        </g>
        <g className="orb-ring-c" style={{ transformOrigin: "100px 100px" }}>
          <circle cx="100" cy="100" r="60" fill="none" stroke="var(--glow)" strokeWidth="3" strokeDasharray="40 30 8 30" strokeOpacity=".8" />
        </g>
        <g className="orb-core" style={{ transformOrigin: "100px 100px" }}>
          <circle cx="100" cy="100" r="44" fill="url(#core)" filter="url(#blur)" />
          <circle cx="100" cy="100" r="30" fill="url(#core)" />
        </g>
      </svg>
      {state === "speaking" || state === "listening" ? <Waveform className="absolute bottom-2 left-1/2 -translate-x-1/2" /> : null}
    </div>
  );
}

export function Waveform({ className = "" }: { className?: string }) {
  return (
    <div className={`flex h-8 items-center gap-1 ${className}`}>
      {Array.from({ length: 9 }).map((_, i) => (
        <span key={i} className="wave-bar block h-full w-1 rounded-full bg-primary" style={{ animationDelay: `${(i % 5) * 0.12}s` }} />
      ))}
    </div>
  );
}
