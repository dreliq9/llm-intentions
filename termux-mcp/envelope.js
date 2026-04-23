// Canonical OK/WARN/FAIL envelope for Termux tool responses.
// Mirrors com.androidmcp.core.protocol.Envelope on the Kotlin side so the
// wire contract is language-independent.
//
// Shape:
//   { status: "ok" | "warn" | "fail", summary, hint, data, raw }
//
// Rendered text:
//   OK|WARN|FAIL: <summary>
//   [Hint: <hint>]
//
//   <data JSON>
//   [Raw: <raw JSON> on non-OK]

export const Status = Object.freeze({ OK: "ok", WARN: "warn", FAIL: "fail" });

export function ok(summary, data = {}, raw = {}) {
  return { status: Status.OK, summary, hint: "", data, raw };
}

export function warn(summary, hint = "", data = {}, raw = {}) {
  return { status: Status.WARN, summary, hint, data, raw };
}

export function fail(summary, hint = "", data = {}, raw = {}) {
  return { status: Status.FAIL, summary, hint, data, raw };
}

/**
 * Convert an Error into a FAIL envelope, consulting the tool's metadata
 * (if any) for an actionable hint via pattern or exceptionType matching.
 *
 * @param {string} toolName
 * @param {object|null} metadata - { failureModes: [{ pattern?, exceptionType?, hint }] }
 * @param {Error} err
 */
export function fromException(toolName, metadata, err) {
  const message = err?.message ?? String(err);
  const typeName = err?.name ?? err?.constructor?.name ?? "Error";
  let hint = "";
  if (metadata?.failureModes) {
    for (const fm of metadata.failureModes) {
      if (fm.pattern && new RegExp(fm.pattern).test(message)) { hint = fm.hint; break; }
      if (fm.exceptionType && (typeName === fm.exceptionType || fm.exceptionType === "Error")) { hint = fm.hint; break; }
    }
  }
  return fail(
    `${toolName} failed: ${message}`,
    hint,
    {},
    { exception: typeName, message },
  );
}

export function renderText(env) {
  const prefix = env.status.toUpperCase();
  const lines = [`${prefix}: ${env.summary}`];
  if (env.hint) lines.push(`Hint: ${env.hint}`);
  if (env.data && Object.keys(env.data).length) {
    lines.push("");
    lines.push(JSON.stringify(env.data, null, 2));
  }
  if (env.status !== Status.OK && env.raw && Object.keys(env.raw).length) {
    lines.push("");
    lines.push("Raw:");
    lines.push(JSON.stringify(env.raw, null, 2));
  }
  return lines.join("\n");
}

/**
 * Register a tool whose handler returns a String (or throws).
 * String return → OK envelope. Throw → FAIL envelope with failureModes hint.
 *
 * Usage:
 *   textTool(server, {
 *     name: "sms_send",
 *     description: "...",
 *     schema: { number: z.string(), text: z.string() },
 *     metadata: { destructive: true, latencyClass: "SLOW",
 *       failureModes: [{ pattern: "permission denied", hint: "Grant SEND_SMS..." }] },
 *   }, async ({ number, text }) => {
 *     // let exceptions propagate
 *     return await doSomething();
 *   });
 */
export function textTool(server, { name, description, schema, metadata = null }, handler) {
  server.tool(name, description, schema, async (args) => {
    try {
      const result = await handler(args);
      const text = typeof result === "string" ? result : JSON.stringify(result);
      const env = ok(`${name} succeeded`, { output: text });
      return { content: [{ type: "text", text: renderText(env) }], isError: false };
    } catch (err) {
      const env = fromException(name, metadata, err);
      return { content: [{ type: "text", text: renderText(env) }], isError: true };
    }
  });
}

/**
 * Register a tool whose handler returns an Envelope directly — useful for emitting
 * WARN or a specific FAIL without throwing.
 */
export function envelopeTool(server, { name, description, schema, metadata = null }, handler) {
  server.tool(name, description, schema, async (args) => {
    try {
      const env = await handler(args);
      return {
        content: [{ type: "text", text: renderText(env) }],
        isError: env.status === Status.FAIL,
      };
    } catch (err) {
      const env = fromException(name, metadata, err);
      return { content: [{ type: "text", text: renderText(env) }], isError: true };
    }
  });
}
