import { build } from "esbuild";
import { copyFile, mkdir, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
const out = new URL("../public/intelligent-ui/", import.meta.url);
await mkdir(out, { recursive: true });
await copyFile(
  new URL("node_modules/@openuidev/intelligent-ui-library/LICENSE", import.meta.url),
  new URL("Thesys-LICENSE.txt", out),
);
const result = await build({
  entryPoints: [fileURLToPath(new URL("runtime.jsx", import.meta.url))],
  bundle: true,
  minify: true,
  write: false,
  outdir: "out",
  target: "es2020",
  loader: { ".woff2": "dataurl", ".woff": "dataurl", ".ttf": "dataurl" },
  define: { "process.env.NODE_ENV": '"production"' },
  legalComments: "eof",
});
// Inline the local script with a hash rather than allowing arbitrary inline scripts.
const script = result.outputFiles
  .find((f) => f.path.endsWith(".js"))
  .text.replace(/<\/script/gi, "<\\/script");
const hash = createHash("sha256").update(script).digest("base64");
const css =
  "*{box-sizing:border-box}html,body{margin:0;background:transparent;font:16px/1.5 system-ui,sans-serif}#surface{display:flow-root;overflow-wrap:anywhere}section{margin:0 0 12px;max-width:100%;overflow-x:auto}fieldset{margin:0;border:1px solid GrayText;border-radius:12px;padding:10px 14px;min-width:0}legend{font-weight:600}input[type=range]{display:block;width:100%;min-height:40px}output{font-variant-numeric:tabular-nums}pre{white-space:pre-wrap}";
await writeFile(
  new URL("index.html", out),
  `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-${hash}'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'"><style>${css}</style></head><body><main id="surface"></main><script>${script}</script></body></html>`,
);
