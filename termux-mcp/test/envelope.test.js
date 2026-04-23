import { test } from "node:test";
import assert from "node:assert/strict";
import { ok, warn, fail, fromException, renderText, Status } from "../envelope.js";

test("ok has empty hint", () => {
  const e = ok("clipboard updated");
  assert.equal(e.status, Status.OK);
  assert.equal(e.summary, "clipboard updated");
  assert.equal(e.hint, "");
});

test("fail carries hint", () => {
  const e = fail("permission denied", "grant SEND_SMS");
  assert.equal(e.status, Status.FAIL);
  assert.equal(e.hint, "grant SEND_SMS");
});

test("warn carries hint", () => {
  const e = warn("partial", "3 of 5");
  assert.equal(e.status, Status.WARN);
  assert.equal(e.hint, "3 of 5");
});

test("fromException matches pattern", () => {
  const meta = { failureModes: [{ pattern: "permission denied", hint: "Grant perm." }] };
  const e = fromException("sms_send", meta, new Error("permission denied by user"));
  assert.equal(e.status, Status.FAIL);
  assert.equal(e.hint, "Grant perm.");
});

test("fromException matches exceptionType by constructor name", () => {
  class TimeoutError extends Error {
    constructor(msg) { super(msg); this.name = "TimeoutError"; }
  }
  const meta = { failureModes: [{ exceptionType: "TimeoutError", hint: "retry smaller" }] };
  const e = fromException("slow", meta, new TimeoutError("timed out"));
  assert.equal(e.hint, "retry smaller");
});

test("render ok starts with OK: and includes data JSON", () => {
  const e = ok("found 3 items", { count: 3 });
  const t = renderText(e);
  assert.ok(t.startsWith("OK: found 3 items"));
  assert.ok(t.includes("\"count\": 3"));
});

test("render fail includes hint line", () => {
  const e = fail("denied", "grant perm");
  const t = renderText(e);
  assert.ok(t.startsWith("FAIL: denied"));
  assert.ok(t.includes("Hint: grant perm"));
});

test("render ok omits raw block", () => {
  const e = ok("done", {}, { stdout: "hi" });
  const t = renderText(e);
  assert.ok(!t.includes("Raw:"));
});

test("render fail includes raw block", () => {
  const e = fail("bad", "", {}, { stderr: "boom" });
  const t = renderText(e);
  assert.ok(t.includes("Raw:"));
  assert.ok(t.includes("boom"));
});
