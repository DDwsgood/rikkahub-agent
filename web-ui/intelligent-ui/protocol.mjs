// The wire format is intentionally smaller than general OpenUI Lang. Model output is
// data, never a script, URL, query, or action. Complete rows can be rendered immediately.
const string = (v) => typeof v === "string" && v.length > 0 && v.length <= 4000;
const strings = (v, max) => Array.isArray(v) && v.length > 0 && v.length <= max && v.every(string);
const scalar = (v) => string(v) || Number.isFinite(v);
const validators = {
  Title: (a) => a.length === 1 && string(a[0]),
  Text: (a) => a.length === 1 && string(a[0]),
  Checklist: (a) => a.length === 1 && strings(a[0], 32),
  Table: ([heads, rows, ...extra]) =>
    !extra.length &&
    strings(heads, 8) &&
    Array.isArray(rows) &&
    rows.length > 0 &&
    rows.length <= 48 &&
    rows.every((r) => Array.isArray(r) && r.length === heads.length && r.every(scalar)),
  Cards: (a) =>
    a.length === 1 &&
    Array.isArray(a[0]) &&
    a[0].length > 0 &&
    a[0].length <= 12 &&
    a[0].every((r) => Array.isArray(r) && r.length === 2 && r.every(string)),
  Chart: ([kind, labels, values, ...extra]) =>
    !extra.length &&
    ["bar", "line"].includes(kind) &&
    strings(labels, 40) &&
    Array.isArray(values) &&
    values.length === labels.length &&
    values.every(Number.isFinite),
  Budget: ([unit, fixed, per, min, max, initial, ...extra]) =>
    !extra.length &&
    string(unit) &&
    unit.length <= 12 &&
    [fixed, per].every((v) => Number.isFinite(v) && v >= 0 && v <= 1e9) &&
    [min, max, initial].every(Number.isSafeInteger) &&
    min >= 1 &&
    max <= 10000 &&
    min < max &&
    min <= initial &&
    initial <= max,
};

export function parseAnswer(source, done) {
  const rows = [],
    rejected = [];
  const lines = source.slice(0, 65536).split(/\r?\n/);
  for (const [index, raw] of lines.slice(0, 96).entries()) {
    const line = raw.trim();
    if (!line) continue;
    const match = /^(\w+)\((.*)\)$/.exec(line);
    if (match && Object.prototype.hasOwnProperty.call(validators, match[1])) {
      try {
        const args = JSON.parse(`[${match[2]}]`);
        if (validators[match[1]](args)) {
          rows.push({ index, name: match[1], args });
          continue;
        }
      } catch {
        /* Partial lines are expected. */
      }
    }
    if (done || index < lines.length - 1) rejected.push(line);
  }
  return { rows, rejected, truncated: source.length > 65536 || lines.length > 96 };
}
