import * as React from "react";
import { Play } from "lucide-react";
import Markdown from "~/components/markdown/markdown";
import { IntelligentUiPart } from "./intelligent-ui-part";

type KpiItem = { label: string; value: string; sub: string };
type GalleryPhoto = { src: string; caption: string; source: string };

type RichBlock =
  | { type: "markdown"; text: string }
  | { type: "widget"; html: string; complete: boolean }
  | { type: "kpi-grid"; items: KpiItem[] }
  | {
      type: "media-card";
      img: string;
      ratio: string;
      title: string;
      subtitle: string;
      href: string;
      source: string;
      body: string;
    }
  | { type: "gallery"; photos: GalleryPhoto[] }
  | { type: "link-card"; url: string; title: string; desc: string; img: string }
  | { type: "video-card"; url: string; title: string; source: string; thumb: string };

const QUICK_MARKER_RE = /```rikka-ui|<kpi-grid\b|<media-card\b|<gallery\b|<link-card\b|<video-card\b|<citation\b/i;
const CITATION_WRAPPER_RE = /<citation>\s*\(?(\[citation,[^\]]+\]\([^)]+\))\)?\s*<\/citation>/gi;
const FENCE_START_RE = /^(`{3,}|~{3,})([^\n]*)$/;
const BLOCK_OPEN_RE = /^<(kpi-grid|media-card|gallery|link-card|video-card)\b([^>]*?)(\/?)>(.*)$/i;
const YT_ID_RE = /(?:youtu\.be\/|[?&]v=|\/embed\/|\/shorts\/)([\w-]{11})/;

function normalizeUrl(raw?: string): string {
  const s = (raw || "").trim();
  if (!/^https?:\/\//i.test(s)) return "";
  if (/hdslb\.com/i.test(s)) {
    return s.replace(/@\d+w_\d+h(?:_1c)?\.[a-z]+$/i, "@672w_378h_1c.webp");
  }
  return s;
}

function extractDomain(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

function resolveVideoThumb(url: string, thumb?: string): string {
  const explicit = normalizeUrl(thumb);
  if (explicit) return explicit;
  const m = YT_ID_RE.exec(url);
  if (m) return `https://i.ytimg.com/vi/${m[1]}/hqdefault.jpg`;
  return "";
}

function decodeEntities(s: string): string {
  return s
    .replaceAll("&quot;", '"')
    .replaceAll("&#39;", "'")
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">");
}

function parseAttrs(raw: string): Record<string, string> {
  const out: Record<string, string> = {};
  const s = raw.trim();
  let i = 0;
  const n = s.length;
  while (i < n) {
    while (i < n && /\s/.test(s[i])) i++;
    if (i >= n) break;
    const start = i;
    while (i < n && /[\w-]/.test(s[i])) i++;
    if (i === start) {
      i++;
      continue;
    }
    const key = s.slice(start, i).toLowerCase();
    while (i < n && /\s/.test(s[i])) i++;
    if (i >= n || s[i] !== "=") {
      out[key] = "true";
      continue;
    }
    i++;
    while (i < n && /\s/.test(s[i])) i++;
    if (i >= n) break;
    const q = s[i];
    if (q === '"' || q === "'") {
      i++;
      const valStart = i;
      let endIdx = -1;
      for (let scan = i; scan < n; scan++) {
        if (s[scan] === q) {
          const rest = s.slice(scan + 1).trimStart();
          if (!rest || rest.startsWith("/") || rest.startsWith(">") || /^[a-zA-Z_][\w-]*\s*=/.test(rest)) {
            endIdx = scan;
            break;
          }
        }
      }
      if (endIdx < 0) {
        const nextQ = s.indexOf(q, valStart);
        endIdx = nextQ >= 0 ? nextQ : n;
      }
      out[key] = decodeEntities(s.slice(valStart, endIdx).trim());
      i = Math.min(endIdx + 1, n);
    } else {
      const valStart = i;
      while (i < n && !/\s|>/.test(s[i])) i++;
      out[key] = decodeEntities(s.slice(valStart, i).trim());
    }
  }
  return out;
}

function parseRichBlocks(rawInput: string, streaming: boolean): RichBlock[] {
  const cleaned = rawInput.replace(CITATION_WRAPPER_RE, "$1");
  if (!QUICK_MARKER_RE.test(cleaned)) {
    return cleaned ? [{ type: "markdown", text: cleaned }] : [];
  }

  const lines = cleaned.split("\n");
  const blocks: RichBlock[] = [];
  const mdLines: string[] = [];

  const flushMd = () => {
    const joined = mdLines.join("\n").replace(/^\n+|\n+$/g, "");
    if (joined.trim()) blocks.push({ type: "markdown", text: joined });
    mdLines.length = 0;
  };

  let idx = 0;
  while (idx < lines.length) {
    const line = lines[idx];
    const trimmed = line.trim();

    const fenceMatch = FENCE_START_RE.exec(trimmed);
    if (fenceMatch) {
      const fenceChars = fenceMatch[1];
      const fenceChar = fenceChars[0];
      const fenceLen = fenceChars.length;
      const info = fenceMatch[2].trim().toLowerCase();

      if (info === "rikka-ui" || info.startsWith("rikka-ui ")) {
        flushMd();
        const widgetLines: string[] = [];
        let closed = false;
        idx++;
        while (idx < lines.length) {
          const cur = lines[idx];
          const curTrim = cur.trim();
          if (curTrim.length >= fenceLen && [...curTrim].every((c) => c === fenceChar)) {
            closed = true;
            idx++;
            break;
          }
          widgetLines.push(cur);
          idx++;
        }
        blocks.push({ type: "widget", html: widgetLines.join("\n"), complete: closed || !streaming });
        continue;
      }

      mdLines.push(line);
      idx++;
      while (idx < lines.length) {
        const cur = lines[idx];
        mdLines.push(cur);
        const curTrim = cur.trim();
        idx++;
        if (curTrim.length >= fenceLen && [...curTrim].every((c) => c === fenceChar)) break;
      }
      continue;
    }

    const openMatch = BLOCK_OPEN_RE.exec(trimmed);
    if (openMatch) {
      const tag = openMatch[1].toLowerCase();
      const attrs = parseAttrs(openMatch[2]);
      const selfClosing = openMatch[3] === "/";
      const tail = openMatch[4];

      if (selfClosing || tag === "link-card" || tag === "video-card") {
        const closingTag = `</${tag}>`;
        let nextIdx = idx + 1;
        if (!selfClosing && !tail.toLowerCase().includes(closingTag)) {
          if (nextIdx < lines.length && lines[nextIdx].trim().toLowerCase() === closingTag) {
            nextIdx++;
          }
        }
        if (tag === "link-card") {
          const url = normalizeUrl(attrs.url || attrs.href);
          if (url) {
            flushMd();
            blocks.push({
              type: "link-card",
              url,
              title: attrs.title || extractDomain(url),
              desc: attrs.desc || attrs.description || "",
              img: normalizeUrl(attrs.img || attrs.thumb),
            });
            idx = nextIdx;
            continue;
          }
        } else if (tag === "video-card") {
          const url = normalizeUrl(attrs.url || attrs.href);
          if (url) {
            flushMd();
            blocks.push({
              type: "video-card",
              url,
              title: attrs.title || extractDomain(url),
              source: attrs.source || extractDomain(url),
              thumb: resolveVideoThumb(url, attrs.thumb || attrs.img),
            });
            idx = nextIdx;
            continue;
          }
        }
      } else {
        const closeToken = `</${tag}>`;
        const inlineClose = tail.toLowerCase().indexOf(closeToken);
        const innerParts: string[] = [];
        let closed = false;
        let nextIdx = idx + 1;

        if (inlineClose >= 0) {
          innerParts.push(tail.slice(0, inlineClose));
          closed = true;
        } else {
          if (tail) innerParts.push(tail);
          while (nextIdx < lines.length) {
            const cur = lines[nextIdx];
            const pos = cur.toLowerCase().indexOf(closeToken);
            if (pos >= 0) {
              innerParts.push(cur.slice(0, pos));
              closed = true;
              nextIdx++;
              break;
            }
            innerParts.push(cur);
            nextIdx++;
          }
        }

        if (!closed && streaming) {
          idx = nextIdx;
          continue;
        }

        const inner = innerParts.join("\n").trim();
        if (tag === "kpi-grid") {
          const items: KpiItem[] = [];
          for (const m of inner.matchAll(/<kpi\b([^>]*?)\/?>/gi)) {
            const a = parseAttrs(m[1]);
            if (a.label || a.value) {
              items.push({ label: a.label || "", value: a.value || "", sub: a.sub || "" });
            }
          }
          if (items.length) {
            flushMd();
            blocks.push({ type: "kpi-grid", items });
            idx = nextIdx;
            continue;
          }
        } else if (tag === "gallery") {
          const photos: GalleryPhoto[] = [];
          for (const m of inner.matchAll(/<photo\b([^>]*?)\/?>/gi)) {
            const a = parseAttrs(m[1]);
            const src = normalizeUrl(a.src || a.img);
            if (src) {
              photos.push({
                src,
                caption: a.caption || a.title || "",
                source: a.source || extractDomain(src),
              });
            }
          }
          if (photos.length) {
            flushMd();
            blocks.push({ type: "gallery", photos });
            idx = nextIdx;
            continue;
          }
        } else if (tag === "media-card") {
          const href = normalizeUrl(attrs.href || attrs.url);
          const img = normalizeUrl(attrs.img || attrs.src);
          if (attrs.title || inner || img) {
            flushMd();
            blocks.push({
              type: "media-card",
              img,
              ratio: attrs.ratio || "4:3",
              title: attrs.title || "",
              subtitle: attrs.subtitle || "",
              href,
              source: attrs.source || extractDomain(href),
              body: inner,
            });
            idx = nextIdx;
            continue;
          }
        }
      }
    }

    mdLines.push(line);
    idx++;
  }

  flushMd();
  return blocks;
}

function MediaCardView({
  block,
  onClickCitation,
}: {
  block: Extract<RichBlock, { type: "media-card" }>;
  onClickCitation?: (id: string) => void;
}) {
  const [imgFailed, setImgFailed] = React.useState(false);
  const showImg = Boolean(block.img) && !imgFailed;
  const isWide = block.ratio === "16:9";
  const aspectClass =
    block.ratio === "2:3"
      ? "aspect-[2/3]"
      : block.ratio === "1:1"
        ? "aspect-square"
        : block.ratio === "16:9"
          ? "aspect-video"
          : "aspect-[4/3]";
  const showFooterSource =
    Boolean(block.source) && !block.body.toLowerCase().includes(`citation,${block.source.toLowerCase()}`);

  return (
    <div className="rikka-glass-card my-3 p-3.5">
      <div className={isWide ? "flex flex-col gap-3" : "flex items-start gap-3.5"}>
        {showImg && (
          <div
            className={
              isWide
                ? `w-full overflow-hidden rounded-xl bg-muted/30 ${aspectClass}`
                : `w-28 shrink-0 overflow-hidden rounded-xl bg-muted/30 ${aspectClass}`
            }
          >
            <img
              src={block.img}
              alt={block.title || "media"}
              loading="lazy"
              referrerPolicy="no-referrer"
              onError={() => setImgFailed(true)}
              className="!my-0 h-full w-full !rounded-none !border-0 object-cover"
            />
          </div>
        )}
        <div className="min-w-0 flex-1 space-y-1.5">
          {block.title &&
            (block.href ? (
              <a
                href={block.href}
                target="_blank"
                rel="noopener noreferrer"
                className="block text-base font-semibold text-foreground underline decoration-dotted underline-offset-4 hover:text-primary"
              >
                {block.title}
              </a>
            ) : (
              <div className="text-base font-semibold text-foreground">{block.title}</div>
            ))}
          {block.subtitle && <div className="text-xs text-muted-foreground">{block.subtitle}</div>}
          {block.body && (
            <div className="text-sm">
              <Markdown content={block.body} onClickCitation={onClickCitation} allowCodePreview={false} />
            </div>
          )}
          {showFooterSource && (
            <div className="pt-1">
              <a
                href={block.href || `https://${block.source}`}
                target="_blank"
                rel="noopener noreferrer"
                className="rikka-source-pill"
              >
                {block.source}
              </a>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function VideoCardView({ block }: { block: Extract<RichBlock, { type: "video-card" }> }) {
  const [thumbFailed, setThumbFailed] = React.useState(false);
  return (
    <a
      href={block.url}
      target="_blank"
      rel="noopener noreferrer"
      className="rikka-glass-card group my-3 block overflow-hidden no-underline transition-transform hover:scale-[1.005]"
    >
      <div className="relative aspect-video w-full overflow-hidden bg-slate-900/70">
        {block.thumb && !thumbFailed && (
          <img
            src={block.thumb}
            alt={block.title}
            loading="lazy"
            referrerPolicy="no-referrer"
            onError={() => setThumbFailed(true)}
            className="!my-0 h-full w-full !rounded-none !border-0 object-cover"
          />
        )}
        <div className="absolute inset-0 flex items-center justify-center bg-black/25">
          <div className="flex size-12 items-center justify-center rounded-full border border-white/50 bg-black/55 text-white backdrop-blur-md transition-transform group-hover:scale-110">
            <Play className="ml-0.5 size-5 fill-current" />
          </div>
        </div>
        {block.source && (
          <span className="absolute right-2.5 top-2.5 rounded-full border border-white/20 bg-black/55 px-2.5 py-0.5 text-[11px] font-medium text-white backdrop-blur-md">
            {block.source}
          </span>
        )}
      </div>
      <div className="p-3">
        <div className="line-clamp-2 text-sm font-semibold text-foreground">{block.title}</div>
        <div className="mt-0.5 text-xs text-muted-foreground">{extractDomain(block.url) || block.source}</div>
      </div>
    </a>
  );
}

export function RichContentPart({
  text,
  isAnimating = false,
  onClickCitation,
}: {
  text: string;
  isAnimating?: boolean;
  onClickCitation?: (id: string) => void;
}) {
  const blocks = React.useMemo(() => parseRichBlocks(text, isAnimating), [text, isAnimating]);

  if (blocks.length === 1 && blocks[0].type === "markdown") {
    return (
      <div data-part="text">
        <Markdown content={blocks[0].text} isAnimating={isAnimating} onClickCitation={onClickCitation} />
      </div>
    );
  }

  return (
    <div data-part="text" className="space-y-3">
      {blocks.map((block, index) => {
        switch (block.type) {
          case "markdown":
            return (
              <Markdown
                key={`md-${index}`}
                content={block.text}
                isAnimating={isAnimating}
                onClickCitation={onClickCitation}
              />
            );
          case "widget":
            return <IntelligentUiPart key={`ui-${index}`} source={block.html} done={block.complete} />;
          case "kpi-grid":
            return (
              <div key={`kpi-${index}`} className="grid grid-cols-2 gap-2.5 my-2">
                {block.items.map((kpi, i) => (
                  <div key={i} className="rikka-glass-card p-3">
                    {kpi.label && <div className="text-xs text-muted-foreground">{kpi.label}</div>}
                    <div className="mt-1 text-lg font-bold tabular-nums text-foreground">{kpi.value}</div>
                    {kpi.sub && <div className="mt-0.5 text-[11.5px] text-muted-foreground">{kpi.sub}</div>}
                  </div>
                ))}
              </div>
            );
          case "media-card":
            return <MediaCardView key={`mc-${index}`} block={block} onClickCitation={onClickCitation} />;
          case "gallery":
            return (
              <div key={`gal-${index}`} className="flex gap-3 overflow-x-auto pb-1 snap-x">
                {block.photos.map((photo, i) => (
                  <figure
                    key={i}
                    className="rikka-glass-card w-56 shrink-0 snap-start overflow-hidden"
                  >
                    <div className="aspect-[4/3] w-full overflow-hidden bg-muted/30">
                      <img
                        src={photo.src}
                        alt={photo.caption || "photo"}
                        loading="lazy"
                        referrerPolicy="no-referrer"
                        className="!my-0 h-full w-full !rounded-none !border-0 object-cover"
                      />
                    </div>
                    {(photo.caption || photo.source) && (
                      <figcaption className="p-2.5">
                        {photo.caption && (
                          <div className="line-clamp-2 text-xs text-foreground">{photo.caption}</div>
                        )}
                        {photo.source && (
                          <div className="mt-0.5 text-[11px] text-muted-foreground">{photo.source}</div>
                        )}
                      </figcaption>
                    )}
                  </figure>
                ))}
              </div>
            );
          case "link-card":
            return (
              <a
                key={`lc-${index}`}
                href={block.url}
                target="_blank"
                rel="noopener noreferrer"
                className="rikka-glass-card my-2 flex items-center gap-3 p-3 no-underline transition-colors hover:border-primary/40"
              >
                <div className="min-w-0 flex-1">
                  <div className="text-[11px] text-muted-foreground">{extractDomain(block.url)}</div>
                  <div className="mt-0.5 line-clamp-2 text-sm font-semibold text-foreground">{block.title}</div>
                  {block.desc && (
                    <div className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{block.desc}</div>
                  )}
                </div>
                {block.img && (
                  <img
                    src={block.img}
                    alt={block.title}
                    loading="lazy"
                    referrerPolicy="no-referrer"
                    className="!my-0 size-16 shrink-0 rounded-lg !border-0 object-cover"
                  />
                )}
              </a>
            );
          case "video-card":
            return <VideoCardView key={`vc-${index}`} block={block} />;
        }
      })}
    </div>
  );
}
