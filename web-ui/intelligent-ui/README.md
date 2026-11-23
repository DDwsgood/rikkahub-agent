# Intelligent UI in RikkaHub

The Android and web message hosts use the same offline Thesys renderer. No Thesys account, remote assets or additional model request is needed. Normal Markdown, user messages and code fences are not interpreted as UI. Interactive chat gets an optional formatting hint; Telegram, cron and sub-agent calls are not asked to generate this format.

Only an assistant text part beginning with `RIKKA_UI_V1` on its own line opts in. Subsequent lines contain one component and JSON literal arguments, not JavaScript. For example:

```text
RIKKA_UI_V1
Title("周末野餐")
Table(["时间","安排"],[["10:00","北门集合"],["12:00","午餐"]])
Checklist(["水","野餐垫","垃圾袋"])
Budget("元",60,25,2,8,4)
```

`protocol.mjs` validates each declaration independently. Completed components mount while later lines are still arriving. Bad declarations do not suppress valid siblings; at completion the original text is available if anything could not render. The catalog also includes `Text`, responsive `Cards`, and `Chart` (bar/line). The model cannot emit unbound checkboxes, callbacks, scripts, network requests or device actions. Interactive state belongs to the mounted surface, not chat history.

`pnpm build` or `pnpm dev` in `web-ui` builds the version-locked kit into `public/intelligent-ui/index.html`; Gradle packages it in `static/intelligent-ui/index.html`. Android intercepts one local document URL, while web uses an opaque sandboxed iframe. The build embeds a hash-authorized script; there is no JS server in the running application. To test validation locally run `node --test intelligent-ui/protocol.test.mjs` from `web-ui`.
