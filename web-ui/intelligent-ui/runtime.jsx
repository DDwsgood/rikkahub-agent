import React, { useState } from "react";
import { createRoot } from "react-dom/client";
import { createLibrary, Renderer } from "@openuidev/react-lang";
import { intelligentComponents, ThemeProvider } from "@openuidev/intelligent-ui-library";
import { parseAnswer } from "./protocol.mjs";

const allowed = new Set(["Col", "Grid", "Card", "Title", "Text", "Table", "Chart", "Checkbox"]);
const kit = createLibrary({
  root: "Col",
  components: intelligentComponents.filter((c) => allowed.has(c.name)),
});
const q = JSON.stringify;
function Row({ name, args, zh }) {
  const [count, setCount] = useState(null);
  if (name === "Budget") {
    const [unit, fixed, per, min, max, initial] = args;
    const n = count ?? initial,
      total = fixed + n * per;
    const money = (x) =>
      `${new Intl.NumberFormat(zh ? "zh" : "en", { maximumFractionDigits: 2 }).format(x)} ${unit}`;
    return (
      <fieldset>
        <legend>{zh ? "费用估算" : "Budget"}</legend>
        <label>
          {zh ? "人数" : "People"}: {n}
          <input
            type="range"
            min={min}
            max={max}
            value={n}
            onChange={(e) => setCount(e.target.valueAsNumber)}
          />
        </label>
        <output>
          {zh ? "总价" : "Total"}: {money(total)} · {zh ? "人均" : "Per person"}: {money(total / n)}
        </output>
      </fieldset>
    );
  }
  let code;
  if (name === "Checklist") {
    const ids = args[0].map((_, i) => `item${i}`);
    code = [
      `root = Col([${ids}])`,
      ...args[0].flatMap((label, i) => [
        `$c${i} = false`,
        `${ids[i]} = Checkbox($c${i}, ${q(label)}, true)`,
      ]),
    ].join("\n");
  } else if (name === "Cards") {
    const cards = args[0].map(
      ([title, body]) => `Card([Title(${q(title)}, "sm"), Text(${q(body)})])`,
    );
    code = `root = Col([Grid([${cards}], 2, 180)])`;
  } else if (name === "Chart") {
    const [kind, labels, values] = args;
    const data = labels.map((label, i) => ({ label, value: values[i] }));
    code = `root = Col([Chart(${q(kind)}, ${q(data)}, "label", ["value"])])`;
  } else {
    code = `root = Col([${name}(${args.map((v) => q(v)).join(",")})])`;
  }
  return <Renderer library={kit} response={code} isStreaming={false} onError={reportErrors} />;
}

let hostPort;
function notify(type, height = 0) {
  if (hostPort) hostPort.postMessage({ type, height });
  else window.RikkaSize?.status(type, height);
}
function reportErrors(errors) {
  if (errors.length) notify("partial");
}
class RowBoundary extends React.Component {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch() {
    notify("partial");
  }
  render() {
    return this.state.failed ? null : this.props.children;
  }
}
function Committed({ partial, done, revision }) {
  React.useLayoutEffect(() => {
    if (partial) notify("partial");
    if (done) notify("final", revision);
  });
  return null;
}
const root = createRoot(document.getElementById("surface"));
function update({ source, done, dark, lang, revision }) {
  if (typeof source !== "string") return;
  const { rows, rejected, truncated } = parseAnswer(source, done);
  const zh = String(lang).startsWith("zh");
  document.documentElement.lang = zh ? "zh" : "en";
  document.documentElement.style.colorScheme = dark ? "dark" : "light";
  root.render(
    <ThemeProvider mode={dark ? "dark" : "light"}>
      {rows.map((r) => (
        <section key={`${r.index}:${r.name}:${JSON.stringify(r.args)}`}>
          <RowBoundary>
            <Row {...r} zh={zh} />
          </RowBoundary>
        </section>
      ))}
      <Committed done={done} revision={revision} partial={done && (rejected.length > 0 || truncated || !rows.length)} />
    </ThemeProvider>,
  );
}
// Only the trusted host supplies data; there is no provider, tool bridge or model JS.
new ResizeObserver(() => {
  const height = Math.ceil(document.getElementById("surface").getBoundingClientRect().height);
  notify("height", height);
}).observe(document.getElementById("surface"));
// Text/Markdown links from model content must never navigate the sandbox.
document.addEventListener(
  "click",
  (e) => {
    if (e.target.closest?.("a")) e.preventDefault();
  },
  true,
);
window.addEventListener("error", () => notify("failed"));
window.addEventListener("unhandledrejection", () => notify("failed"));
window.rikkaUpdate = (data) => {
  update(data);
  return true;
};
window.addEventListener("message", (e) => {
  if (
    window.parent === window ||
    e.source !== window.parent ||
    e.data !== "rikka-ui:connect" ||
    !e.ports[0]
  )
    return;
  hostPort?.close();
  hostPort = e.ports[0];
  hostPort.onmessage = (event) => {
    try {
      update(event.data);
    } catch {
      notify("failed");
    }
  };
  notify("ready");
});
