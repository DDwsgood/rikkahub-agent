import assert from "node:assert/strict";
import test from "node:test";
import { parseAnswer } from "./protocol.mjs";

test("renders only completed declarations from a stream", () => {
  const result = parseAnswer('Title("野餐")\r\nChecklist(["水",', false);
  assert.deepEqual(
    result.rows.map((r) => r.name),
    ["Title"],
  );
  assert.deepEqual(result.rejected, []);
});

test("validates interactive data and keeps adjacent good rows on failure", () => {
  const result = parseAnswer(
    'Budget("元",60,25,2,8,4)\nBudget("元",0,1,0,8,4)\nAction("open")\nTable(["x"],[[null]])\nChecklist(["a\\\"b"])',
    true,
  );
  assert.deepEqual(
    result.rows.map((r) => r.name),
    ["Budget", "Checklist"],
  );
  assert.equal(result.rejected.length, 3);
});

test("only accepts finite literal chart data with matching labels", () => {
  assert.equal(parseAnswer('Chart("bar",["周一"],[10])', true).rows.length, 1);
  for (const invalid of [
    'Chart("pie",["a"],[1])',
    'Chart("bar",["a"],[1,2])',
    'Chart("bar",["a"],[1e999])',
    'Text(@OpenUrl("x"))',
    "toString()",
  ])
    assert.equal(parseAnswer(invalid, true).rows.length, 0);
});
