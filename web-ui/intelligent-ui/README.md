# RikkaHub Glassmorphic Rich UI & Interactive Widget Runtime

Replaces the legacy 3.27 MB Thesys OpenUI (`RIKKA_UI_V1`) bundle with a two-layer Markdown-compatible UI system:

1. **Layer 1 (Native Glassmorphic Rich Markup)**: Rendered natively in Android Compose (`RikkaRichBlocks.kt` / `MarkdownNew.kt`) and Web UI (`rich-content-part.tsx`) with zero WebView/iframe overhead — colored status glyphs (`✓`/`⚠`/`×`/`–`), `<badge tone="...">` pills, inline `[citation,domain](id)` chips, `<kpi-grid>`, Cradle-style `<media-card>`, horizontal `<gallery>`, `<link-card>`, and `<video-card>`.
2. **Layer 2 (` ```rikka-ui ` & HTML Interactive Card Preview)**: Uses a ~9 KB zero-dependency offline sandboxed micro-runtime built by `node intelligent-ui/build.mjs` into `public/intelligent-ui/index.html` (packaged by Gradle into `static/intelligent-ui/index.html`). It enforces strict offline CSP (`default-src 'none'; connect-src 'none'`), transparent glassmorphic theme tokens, streaming DOM morphing without script flicker, touch-capture arbitration (`data-capture-touch`) for sliders/3D canvases, and defensive `CanvasRenderingContext2D.roundRect` clamping.

Run `node --test intelligent-ui/runtime.test.mjs` from `web-ui/` to test the runtime builder.
