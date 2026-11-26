import test from "node:test";
import assert from "node:assert/strict";
import { buildWidgetDocument, stripPendingTags, unwrapDocument } from "./runtime.mjs";

test("buildWidgetDocument produces compact offline-strict HTML shell", () => {
  const html = buildWidgetDocument();
  assert.ok(Buffer.byteLength(html, "utf8") < 16384, "runtime should stay under 16 KB");
  assert.match(html, /default-src 'none'/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /roundRect/);
  assert.match(html, /capture-touch/);
  assert.doesNotMatch(html, /https?:\/\//);
});

test("unwrapDocument extracts head styles and body from full HTML pages", () => {
  const res = unwrapDocument(
    "<!DOCTYPE html><html><head><style>.a{color:red}</style></head><body><div class='a'>hi</div></body></html>",
  );
  assert.equal(res.styles.length, 1);
  assert.match(res.styles[0], /color:red/);
  assert.equal(res.html.trim(), "<div class='a'>hi</div>");
});

test("stripPendingTags removes complete and incomplete scripts and trailing broken tags", () => {
  const streamed = `<div class="w-card">OK</div><script>const x = 1;`;
  assert.equal(stripPendingTags(streamed), `<div class="w-card">OK</div>`);
  assert.equal(stripPendingTags(`<div>Hello</di`), `<div>Hello`);
});
