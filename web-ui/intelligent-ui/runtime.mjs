// Zero-dependency glassmorphic micro-runtime for RikkaHub interactive cards (`rikka-ui` & HTML preview).
// Runs inside an offline, network-blocked WebView (Android) or opaque sandboxed iframe (Web UI).

export const WIDGET_CSS = `
:root{
  --bg:transparent;
  --surface:rgba(255,255,255,.065);
  --surface-2:rgba(255,255,255,.048);
  --border:rgba(255,255,255,.13);
  --text:#eef3fb;
  --muted:#9aa8bf;
  --accent:#52a8ff;
  --ok:#34d399;--ok-bg:rgba(16,185,129,.16);
  --warn:#fbbf24;--warn-bg:rgba(245,158,11,.16);
  --err:#fb7185;--err-bg:rgba(244,63,94,.16);
  --info:#60a5fa;--info-bg:rgba(59,130,246,.16);
  --radius:16px;
}
:root[data-theme="light"]{
  --surface:rgba(255,255,255,.72);
  --surface-2:rgba(15,23,42,.045);
  --border:rgba(15,23,42,.12);
  --text:#0f172a;
  --muted:#475569;
  --accent:#2563eb;
  --ok:#059669;--ok-bg:rgba(16,185,129,.12);
  --warn:#d97706;--warn-bg:rgba(245,158,11,.14);
  --err:#dc2626;--err-bg:rgba(220,38,38,.12);
  --info:#2563eb;--info-bg:rgba(37,99,235,.12);
}
*{box-sizing:border-box}
html,body{
  margin:0;padding:0;
  background:transparent;
  color:var(--text);
  font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"PingFang SC","Noto Sans SC",sans-serif;
  overflow:hidden;
  -webkit-font-smoothing:antialiased;
}
#surface{display:flow-root;padding:2px 0;max-width:100%;overflow-wrap:anywhere}
.w-card{
  background:var(--surface);
  -webkit-backdrop-filter:blur(18px) saturate(135%);
  backdrop-filter:blur(18px) saturate(135%);
  border:1px solid var(--border);
  border-radius:var(--radius);
  padding:14px;
  box-shadow:0 8px 24px rgba(0,0,0,.18),inset 0 1px 0 rgba(255,255,255,.09);
  max-width:100%;
  min-width:0;
}
html[data-host="android"] .w-card{-webkit-backdrop-filter:none;backdrop-filter:none;box-shadow:none}
.w-subcard{
  background:var(--surface-2);
  border:1px solid var(--border);
  border-radius:12px;
  padding:11px;
  min-width:0;
}
.w-grid-2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
.w-grid-3{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}
.w-grid-3 .w-subcard{padding:8px 6px}
.w-grid-3 .w-big{font-size:clamp(13px,4.6vw,15.5px)!important;letter-spacing:-.02em}
.w-row{display:flex;align-items:center;justify-content:space-between;gap:8px;min-width:0}
.w-title{font-size:16px;font-weight:650;margin:0 0 10px;color:var(--text)}
.w-muted{color:var(--muted);font-size:12.5px}
.w-big{
  font-size:clamp(18px,5.6vw,24px);
  font-weight:700;
  font-variant-numeric:tabular-nums;
  letter-spacing:-.02em;
  overflow-wrap:anywhere;
}
.w-slider,input[type="range"]{
  display:block;width:100%;min-height:36px;
  accent-color:var(--accent);
  cursor:pointer;
  touch-action:none;
}
.w-tabs{
  display:inline-flex;align-items:center;gap:2px;
  padding:3px;border-radius:10px;
  background:var(--surface-2);
  border:1px solid var(--border);
  max-width:100%;overflow-x:auto;
}
.w-tab{
  border:0;background:transparent;color:var(--muted);
  padding:5px 10px;border-radius:8px;font-size:13px;cursor:pointer;white-space:nowrap;
  transition:background .15s,color .15s;
}
.w-tab[aria-selected="true"],.w-tab.is-active{
  background:var(--surface);color:var(--text);font-weight:600;
  box-shadow:0 1px 4px rgba(0,0,0,.18);
}
.w-pill{
  display:inline-flex;align-items:center;gap:4px;
  padding:3px 9px;border-radius:999px;font-size:12px;font-weight:550;
  max-width:100%;white-space:normal;overflow-wrap:anywhere;
  border:1px solid transparent;
}
.w-pill.ok{color:var(--ok);background:var(--ok-bg);border-color:rgba(52,211,153,.28)}
.w-pill.warn{color:var(--warn);background:var(--warn-bg);border-color:rgba(251,191,36,.28)}
.w-pill.err{color:var(--err);background:var(--err-bg);border-color:rgba(251,113,133,.28)}
.w-pill.info{color:var(--info);background:var(--info-bg);border-color:rgba(96,165,250,.28)}
.w-pill.muted{color:var(--muted);background:var(--surface-2);border-color:var(--border)}
.w-st{display:inline-flex;align-items:flex-start;gap:5px}
.w-st.ok::before{content:"✓";color:var(--ok);font-weight:700}
.w-st.warn::before{content:"⚠";color:var(--warn);font-weight:700}
.w-st.err::before{content:"×";color:var(--err);font-weight:700}
.w-st.muted::before{content:"–";color:var(--muted);font-weight:700}
table{width:100%;border-collapse:collapse;font-size:13.5px}
th,td{padding:8px 6px;border-bottom:1px solid var(--border);text-align:left;overflow-wrap:anywhere}
th{color:var(--muted);font-weight:550}
tr:last-child td{border-bottom:none}
button{font-family:inherit}
canvas{max-width:100%;display:block;touch-action:none}
svg{max-width:100%;height:auto}
[data-capture-touch]{touch-action:none;user-select:none;-webkit-user-select:none}
.w-error{
  margin-top:8px;padding:8px 10px;border-radius:10px;
  font-size:12px;color:var(--warn);background:var(--warn-bg);border:1px solid rgba(251,191,36,.28);
}
`;

export function unwrapDocument(raw) {
  if (typeof raw !== "string") return { html: "", styles: [] };
  let src = raw.trim();
  const styles = [];
  if (/<!doctype\s+html|<html[\s>]/i.test(src)) {
    const headMatch = /<head\b[^>]*>([\s\S]*?)<\/head>/i.exec(src);
    if (headMatch) {
      const styleRe = /<style\b[^>]*>([\s\S]*?)<\/style>/gi;
      let sm;
      while ((sm = styleRe.exec(headMatch[1])) !== null) styles.push(sm[1]);
    }
    const bodyMatch = /<body\b[^>]*>([\s\S]*?)<\/body>/i.exec(src);
    if (bodyMatch) {
      src = bodyMatch[1];
    } else {
      src = src
        .replace(/<!doctype[^>]*>/gi, "")
        .replace(/<\/?(html|head|body)\b[^>]*>/gi, "");
    }
  }
  return { html: src, styles };
}

export function stripPendingTags(html) {
  return String(html || "")
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, "")
    .replace(/<script\b[^>]*>[\s\S]*$/gi, "")
    .replace(/<style\b[^>]*>[\s\S]*$/gi, "")
    .replace(/<[^>]*$/, "");
}

export const WIDGET_JS = `
(function(){
  // Defensive shim: narrow mobile bar charts may compute negative roundRect radii.
  if (typeof CanvasRenderingContext2D !== "undefined" && CanvasRenderingContext2D.prototype.roundRect) {
    var origRoundRect = CanvasRenderingContext2D.prototype.roundRect;
    CanvasRenderingContext2D.prototype.roundRect = function(x, y, w, h, radii) {
      var clamp = function(v) {
        if (typeof v === "number") return Number.isFinite(v) && v > 0 ? v : 0;
        if (v && typeof v === "object") {
          return { x: v.x > 0 ? v.x : 0, y: v.y > 0 ? v.y : 0 };
        }
        return 0;
      };
      var safe = Array.isArray(radii) ? radii.map(clamp) : clamp(radii);
      return origRoundRect.call(this, x, y, w, h, safe);
    };
  }

  var surface = document.getElementById("surface");
  var styleHost = document.getElementById("widget-styles");
  var hostPort = null;
  var lastHeight = 0;
  var executedSignature = null;

  function notify(type, value) {
    var v = typeof value === "number" ? value : 0;
    if (hostPort) hostPort.postMessage({ type: type, height: v, value: value });
    else if (window.RikkaSize && typeof window.RikkaSize.status === "function") {
      window.RikkaSize.status(type, v);
    }
  }

  function unwrapDocument(raw) {
    var src = String(raw || "").trim();
    var styles = [];
    if (/<!doctype\\s+html|<html[\\s>]/i.test(src)) {
      var headMatch = /<head\\b[^>]*>([\\s\\S]*?)<\\/head>/i.exec(src);
      if (headMatch) {
        var styleRe = /<style\\b[^>]*>([\\s\\S]*?)<\\/style>/gi;
        var sm;
        while ((sm = styleRe.exec(headMatch[1])) !== null) styles.push(sm[1]);
      }
      var bodyMatch = /<body\\b[^>]*>([\\s\\S]*?)<\\/body>/i.exec(src);
      if (bodyMatch) src = bodyMatch[1];
      else src = src.replace(/<!doctype[^>]*>/gi, "").replace(/<\\/?(html|head|body)\\b[^>]*>/gi, "");
    }
    return { html: src, styles: styles };
  }

  function stripPending(html, done) {
    var s = String(html || "")
      .replace(/<script\\b[^>]*>[\\s\\S]*?<\\/script>/gi, "")
      .replace(/<script\\b[^>]*>[\\s\\S]*$/gi, "");
    if (!done) {
      s = s.replace(/<style\\b[^>]*>[\\s\\S]*$/gi, "").replace(/<[^>]*$/, "");
    }
    return s;
  }

  function extractScripts(html) {
    var out = [];
    var re = /<script\\b([^>]*)>([\\s\\S]*?)<\\/script>/gi;
    var m;
    while ((m = re.exec(html)) !== null) {
      if (/\\bsrc\\s*=/i.test(m[1] || "")) continue;
      if (m[2] && m[2].trim()) out.push(m[2]);
    }
    return out;
  }

  function morphChildren(target, source) {
    var tNodes = Array.from(target.childNodes);
    var sNodes = Array.from(source.childNodes);
    var max = Math.max(tNodes.length, sNodes.length);
    for (var i = 0; i < max; i++) {
      var t = tNodes[i], s = sNodes[i];
      if (!s) { if (t) t.remove(); continue; }
      if (!t) { target.appendChild(s.cloneNode(true)); continue; }
      if (t.nodeType !== s.nodeType || t.nodeName !== s.nodeName) {
        t.replaceWith(s.cloneNode(true));
        continue;
      }
      if (t.nodeType === 3) {
        if (t.nodeValue !== s.nodeValue) t.nodeValue = s.nodeValue;
      } else if (t.nodeType === 1) {
        var tAttrs = Array.from(t.attributes);
        for (var j = 0; j < tAttrs.length; j++) {
          if (!s.hasAttribute(tAttrs[j].name)) t.removeAttribute(tAttrs[j].name);
        }
        var sAttrs = Array.from(s.attributes);
        for (var k = 0; k < sAttrs.length; k++) {
          if (t.getAttribute(sAttrs[k].name) !== sAttrs[k].value) {
            t.setAttribute(sAttrs[k].name, sAttrs[k].value);
          }
        }
        if (t.tagName !== "CANVAS") morphChildren(t, s);
      }
    }
  }

  function tagInteractiveTouchTargets() {
    var els = surface.querySelectorAll('canvas, input[type="range"]');
    for (var i = 0; i < els.length; i++) {
      if (!els[i].hasAttribute("data-capture-touch")) {
        els[i].setAttribute("data-capture-touch", "true");
      }
    }
  }

  function showScriptWarning(msg) {
    if (!surface.querySelector(".w-error")) {
      var box = document.createElement("div");
      box.className = "w-error";
      box.textContent = (document.documentElement.lang === "zh" ? "交互脚本执行异常：" : "Widget script error: ") + msg;
      surface.appendChild(box);
    }
    notify("partial", 1);
  }

  function runScripts(scripts) {
    for (var i = 0; i < scripts.length; i++) {
      var el = document.createElement("script");
      el.textContent = "(function(){\\n" + scripts[i] + "\\n})();";
      document.body.appendChild(el);
      el.remove();
    }
  }

  function reportHeight() {
    var rect = surface.getBoundingClientRect();
    var h = Math.ceil(Math.max(rect.height, surface.scrollHeight));
    if (h > 0 && Math.abs(h - lastHeight) > 1) {
      lastHeight = h;
      notify("height", h);
    }
  }

  function update(data) {
    if (!data || typeof data.source !== "string") return;
    var done = Boolean(data.done);
    var dark = data.dark !== false;
    var zh = String(data.lang || "").toLowerCase().startsWith("zh");
    if (data.host) document.documentElement.dataset.host = String(data.host);
    document.documentElement.lang = zh ? "zh" : "en";
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.documentElement.style.colorScheme = dark ? "dark" : "light";

    var unwrapped = unwrapDocument(data.source.slice(0, 65536));
    if (styleHost) styleHost.textContent = unwrapped.styles.join("\\n");

    var visual = stripPending(unwrapped.html, done);
    var sig = unwrapped.html.length + ":" + unwrapped.html.slice(-48);
    if (!done || executedSignature !== sig) {
      var tpl = document.createElement("template");
      tpl.innerHTML = visual;
      morphChildren(surface, tpl.content);
      tagInteractiveTouchTargets();
    }

    if (done && executedSignature !== sig) {
      executedSignature = sig;
      var scripts = extractScripts(unwrapped.html);
      if (scripts.length) {
        try {
          runScripts(scripts);
        } catch (err) {
          showScriptWarning(err && err.message ? err.message : String(err));
        }
      }
      tagInteractiveTouchTargets();
      reportHeight();
      setTimeout(reportHeight, 60);
      notify("final", Number(data.revision || 0));
    } else {
      reportHeight();
      if (done) notify("final", Number(data.revision || 0));
    }
  }

  document.addEventListener("pointerdown", function(e) {
    if (e.target && e.target.closest && e.target.closest("[data-capture-touch]")) {
      notify("capture-touch", 1);
    }
  }, true);
  ["pointerup", "pointercancel"].forEach(function(ev) {
    document.addEventListener(ev, function() { notify("capture-touch", 0); }, true);
  });

  document.addEventListener("click", function(e) {
    var anchor = e.target && e.target.closest ? e.target.closest("a") : null;
    if (anchor) e.preventDefault();
  }, true);

  window.addEventListener("error", function(e) {
    showScriptWarning((e && e.message) || "Runtime error");
  });
  window.addEventListener("unhandledrejection", function(e) {
    showScriptWarning((e && e.reason && e.reason.message) || String(e && e.reason || "Unhandled rejection"));
  });

  new ResizeObserver(reportHeight).observe(surface);

  window.rikkaUpdate = function(data) {
    update(data);
    return true;
  };

  window.addEventListener("message", function(e) {
    if (
      window.parent === window ||
      e.source !== window.parent ||
      e.data !== "rikka-ui:connect" ||
      !e.ports ||
      !e.ports[0]
    ) return;
    if (hostPort) hostPort.close();
    hostPort = e.ports[0];
    hostPort.onmessage = function(event) {
      try {
        update(event.data);
      } catch (err) {
        showScriptWarning(err && err.message ? err.message : String(err));
      }
    };
    notify("ready", 1);
  });
})();
`;

export function buildWidgetDocument() {
  const csp = [
    "default-src 'none'",
    "script-src 'unsafe-inline'",
    "style-src 'unsafe-inline'",
    "img-src data: blob:",
    "connect-src 'none'",
    "frame-src 'none'",
    "form-action 'none'",
    "base-uri 'none'",
  ].join("; ");
  const safeScript = WIDGET_JS.replace(/<\/script/gi, "<\\/script");
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="${csp}"><style>${WIDGET_CSS}</style><style id="widget-styles"></style></head><body><main id="surface"></main><script>${safeScript}</script></body></html>`;
}
