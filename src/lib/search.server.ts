// Free, key-less search: Wikipedia + DuckDuckGo instant answers, YouTube results page.
export type WebResult = { title: string; url: string; snippet: string };
export type VideoResult = { id: string; title: string; channel: string; thumbnail: string; url: string };

const UA = { "user-agent": "Mozilla/5.0 (Linux; Android 14) MXFF-AI/1.0" };

export async function webSearch(q: string): Promise<WebResult[]> {
  const out: WebResult[] = [];
  const [ddg, wiki] = await Promise.allSettled([
    fetch(`https://api.duckduckgo.com/?q=${encodeURIComponent(q)}&format=json&no_html=1&skip_disambig=1`, { headers: UA }).then((r) => r.json()),
    fetch(`https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=${encodeURIComponent(q)}`, { headers: UA }).then((r) => r.json()),
  ]);
  if (ddg.status === "fulfilled" && ddg.value?.AbstractText) {
    out.push({ title: ddg.value.Heading || q, url: ddg.value.AbstractURL, snippet: ddg.value.AbstractText });
  }
  if (wiki.status === "fulfilled") {
    for (const s of wiki.value?.query?.search ?? []) {
      out.push({
        title: s.title,
        url: `https://en.wikipedia.org/wiki/${encodeURIComponent(s.title.replace(/ /g, "_"))}`,
        snippet: String(s.snippet).replace(/<[^>]+>/g, ""),
      });
    }
  }
  if (ddg.status === "rejected" && wiki.status === "rejected") throw new Error("Search services are unreachable.");
  return out.slice(0, 6);
}

export async function youtubeSearch(q: string): Promise<VideoResult[]> {
  const res = await fetch(`https://www.youtube.com/results?search_query=${encodeURIComponent(q)}&hl=en`, { headers: UA });
  if (!res.ok) throw new Error(`YouTube returned ${res.status}`);
  const html = await res.text();
  const m = html.match(/var ytInitialData = (\{.*?\});<\/script>/s);
  if (!m) throw new Error("Could not read YouTube results.");
  const data = JSON.parse(m[1] ?? "{}");
  const vids: VideoResult[] = [];
  const walk = (n: unknown) => {
    if (!n || typeof n !== "object" || vids.length >= 8) return;
    const o = n as Record<string, any>;
    if (o["videoRenderer"]?.videoId) {
      const v = o["videoRenderer"];
      vids.push({
        id: v.videoId,
        title: v.title?.runs?.[0]?.text ?? "Video",
        channel: v.ownerText?.runs?.[0]?.text ?? "",
        thumbnail: `https://i.ytimg.com/vi/${v.videoId}/hqdefault.jpg`,
        url: `https://www.youtube.com/watch?v=${v.videoId}`,
      });
      return;
    }
    for (const k in o) walk(o[k]);
  };
  walk(data);
  return vids;
}
