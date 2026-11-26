package me.rerere.rikkahub.data.ai

/**
 * Concise English prompt for Rich UI + Sandboxed `rikka-ui` interactive cards.
 * Only advertised to interactive RikkaHub clients, never to headless jobs.
 */
internal object IntelligentUi {
    fun buildPrompt(webSearchEnabled: Boolean): String = buildString {
        appendLine("Rich UI: Use it when your direct output is the expected result of the task (e.g., web search or research); otherwise (e.g., coding tasks), use plain Markdown.")
        appendLine("- Status: `<badge tone=\"ok|warn|err|info|muted\">short</badge>`; `✓`/`⚠`/`×` auto-color.")
        if (webSearchEnabled) {
            appendLine("- Citations: `[citation,domain.com](id)` with exact 6-char `id` from `search_web` (no outer parens/tags).")
        }
        appendLine("- KPIs: `<kpi-grid><kpi label=\"...\" value=\"...\" sub=\"...\" /></kpi-grid>` (2 or 4 items).")
        if (webSearchEnabled) {
            appendLine("- Media/links (copy URLs verbatim from tool results; never fabricate; may set `img=\"\"` + `query=\"...\"`):")
            appendLine("  `<media-card img=\"\" query=\"...\" ratio=\"2:3|4:3|16:9|1:1\" title=\"...\" subtitle=\"...\" href=\"...\" source=\"...\">1-2 lines</media-card>`")
            appendLine("  `<gallery><photo src=\"...\" query=\"...\" caption=\"...\" source=\"...\" /></gallery>`")
            appendLine("  `<link-card url=\"...\" title=\"...\" desc=\"...\" img=\"...\" />`")
            appendLine("  `<video-card url=\"youtube-or-bilibili-url\" title=\"...\" source=\"YouTube|Bilibili\" thumb=\"...\" />`")
        } else {
            appendLine("- Never fabricate external URLs; only use `<link-card>`/`<media-card>`/`<video-card>` with real URLs provided by user or local tools.")
        }
        appendLine("- Interactive widget (` ```rikka-ui `): ONLY for calculators, sliders, filters, dynamic charts, or rotatable 3D. Offline CSP (no external URLs/CDN/fetch). Write HTML fragment (no `<html>`/`<body>`) + inline `<script>` (50-120 lines); add `data-capture-touch=\"true\"` on draggable `<canvas>`/sliders; reuse host CSS vars (`--surface`, `--surface-2`, `--border`, `--text`, `--muted`, `--accent`, `--ok`/`--warn`/`--err`/`--info`) and classes (`.w-card`, `.w-subcard`, `.w-grid-2`, `.w-grid-3`, `.w-row`, `.w-title`, `.w-big`, `.w-muted`, `.w-slider`, `.w-tabs`, `.w-tab`, `.w-pill`, `.w-st`).")
        append("- Never use raw `\"` inside XML attribute values; use `'` or curly quotes.")
    }
}
