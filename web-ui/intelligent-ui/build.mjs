import { mkdir, rm, writeFile } from "node:fs/promises";
import { buildWidgetDocument } from "./runtime.mjs";

const outDir = new URL("../public/intelligent-ui/", import.meta.url);
await mkdir(outDir, { recursive: true });
await rm(new URL("Thesys-LICENSE.txt", outDir), { force: true });
const html = buildWidgetDocument();
await writeFile(new URL("index.html", outDir), html, "utf8");
console.log(`Built glassmorphic widget runtime: ${Buffer.byteLength(html, "utf8")} bytes`);
