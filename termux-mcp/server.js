#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { createServer } from "node:http";
import { textTool } from "./envelope.js";

const exec = promisify(execFile);
const TIMEOUT = 30000;

/** Like run(), but throws on non-zero exit or subprocess error. */
async function runOrThrow(cmd, args = [], input) {
  const opts = {
    timeout: TIMEOUT,
    maxBuffer: 1024 * 1024,
    env: { ...process.env, TMPDIR: process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp" },
  };
  if (input) opts.input = input;
  const { stdout, stderr } = await exec(cmd, args, opts);  // exec is already defined via promisify(execFile); throws on non-zero
  return stdout || stderr || "(no output)";
}

async function run(cmd, args = [], input) {
  const opts = {
    timeout: TIMEOUT,
    maxBuffer: 1024 * 1024,
    env: { ...process.env, TMPDIR: process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp" },
  };
  if (input) opts.input = input;
  try {
    const { stdout, stderr } = await exec(cmd, args, opts);
    return stdout || stderr || "(no output)";
  } catch (e) {
    return `Error: ${e.message}`;
  }
}

function tryParseJSON(str) {
  try { return JSON.stringify(JSON.parse(str), null, 2); } catch { return str; }
}

const server = new McpServer({
  name: "termux-api",
  version: "1.0.0",
});

// ── Battery ──────────────────────────────────────────────
server.tool("battery_status", "Get device battery level, status, and health", {}, async () => {
  const out = await run("termux-battery-status");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

// ── Clipboard ────────────────────────────────────────────
server.tool("clipboard_get", "Get current clipboard contents", {}, async () => {
  const out = await run("termux-clipboard-get");
  return { content: [{ type: "text", text: out }] };
});

textTool(server, {
  name: "clipboard_set",
  description: "Set clipboard contents",
  schema: {
    text: z.string().describe("Text to copy to clipboard"),
  },
  metadata: {
    destructive: true,
    idempotent: true,
    latencyClass: "FAST",
    permissions: [],
    failureModes: [
      { pattern: "cannot access clipboard|not allowed", hint: "Some secure keyboards block clipboard_set. Ask the user to switch keyboard." },
    ],
  },
}, async ({ text }) => {
  return (await runOrThrow("termux-clipboard-set", [text])) || "Clipboard set.";
});

// ── Contacts ─────────────────────────────────────────────
server.tool("contact_list", "List all contacts on the device", {}, async () => {
  const out = await run("termux-contact-list");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

// ── SMS ──────────────────────────────────────────────────
server.tool("sms_list", "List SMS messages", {
  type: z.enum(["inbox", "sent", "draft", "all"]).default("inbox").describe("Message type to list"),
  limit: z.number().default(10).describe("Max number of messages"),
  offset: z.number().default(0).describe("Offset for pagination"),
}, async ({ type, limit, offset }) => {
  const out = await run("termux-sms-list", ["-t", type, "-l", String(limit), "-o", String(offset)]);
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

textTool(server, {
  name: "sms_send",
  description: "Send an SMS message",
  schema: {
    number: z.string().describe("Phone number to send to"),
    text: z.string().describe("Message text"),
  },
  metadata: {
    destructive: true,
    idempotent: false,
    latencyClass: "SLOW",
    permissions: ["SEND_SMS"],
    failureModes: [
      { pattern: "permission denied|not default", hint: "Termux must be the default SMS app, or grant SEND_SMS permission." },
      { pattern: "invalid (number|phone)", hint: "Pass a fully-qualified phone number (country code + digits only)." },
    ],
  },
}, async ({ number, text }) => {
  return (await runOrThrow("termux-sms-send", ["-n", number], text)) || "SMS sent.";
});

// ── Call Log ─────────────────────────────────────────────
server.tool("call_log", "List recent call history", {
  limit: z.number().default(10).describe("Max entries to return"),
  offset: z.number().default(0).describe("Offset for pagination"),
}, async ({ limit, offset }) => {
  const out = await run("termux-call-log", ["-l", String(limit), "-o", String(offset)]);
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

// ── Location ─────────────────────────────────────────────
textTool(server, {
  name: "location",
  description: "Get current GPS/network location",
  schema: {
    provider: z.enum(["gps", "network", "passive"]).default("network").describe("Location provider"),
  },
  metadata: {
    destructive: false,
    idempotent: true,
    latencyClass: "SLOW",
    permissions: ["ACCESS_FINE_LOCATION"],
    failureModes: [
      { pattern: "provider disabled|location off", hint: "Enable Location Services in System Settings." },
      { pattern: "timeout", hint: "GPS can take 30s indoors; retry outside or use provider=network." },
    ],
  },
}, async ({ provider }) => {
  return await runOrThrow("termux-location", ["-p", provider]);
});

// ── Notifications ────────────────────────────────────────
textTool(server, {
  name: "notification_send",
  description: "Show a notification on the device",
  schema: {
    title: z.string().describe("Notification title"),
    content: z.string().describe("Notification body text"),
    id: z.string().optional().describe("Notification ID (for updating)"),
  },
  metadata: {
    destructive: true,
    idempotent: true,
    latencyClass: "FAST",
    permissions: ["POST_NOTIFICATIONS"],
    failureModes: [
      { pattern: "permission", hint: "Grant POST_NOTIFICATIONS to Termux." },
    ],
  },
}, async ({ title, content, id }) => {
  const args = ["-t", title, "-c", content];
  if (id) args.push("-i", id);
  return (await runOrThrow("termux-notification", args)) || "Notification sent.";
});

server.tool("notification_list", "List all active notifications on the device", {}, async () => {
  const out = await run("termux-notification-list");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

// ── Camera ───────────────────────────────────────────────
textTool(server, {
  name: "camera_photo",
  description: "Take a photo with the device camera",
  schema: {
    camera: z.enum(["0", "1"]).default("0").describe("Camera ID: 0=back, 1=front"),
    output: z.string().default("/data/data/com.termux/files/home/photo.jpg").describe("Output file path"),
  },
  metadata: {
    destructive: true,
    idempotent: false,
    latencyClass: "SLOW",
    permissions: ["CAMERA"],
    failureModes: [
      { pattern: "(No such|doesn't exist) device|Camera (error|unavailable)", hint: "No camera available (emulator?). Use termux-camera-info to list lenses." },
    ],
  },
}, async ({ camera, output }) => {
  return (await runOrThrow("termux-camera-photo", ["-c", camera, output])) || `Photo saved to ${output}`;
});

// ── TTS / Audio ──────────────────────────────────────────
textTool(server, {
  name: "tts_speak",
  description: "Speak text aloud using text-to-speech",
  schema: {
    text: z.string().describe("Text to speak"),
  },
  metadata: {
    destructive: true,
    idempotent: false,
    latencyClass: "SLOW",
    permissions: [],
    failureModes: [
      { pattern: "no tts engine|TTS init", hint: "No TTS engine installed; install one from Play Store." },
    ],
  },
}, async ({ text }) => {
  return (await runOrThrow("termux-tts-speak", [], text)) || "Spoken.";
});

server.tool("volume", "Get or set audio volume", {
  stream: z.enum(["alarm", "music", "notification", "ring", "system", "call"]).optional().describe("Audio stream"),
  volume: z.number().optional().describe("Volume level to set"),
}, async ({ stream, volume }) => {
  if (stream && volume !== undefined) {
    const out = await run("termux-volume", [stream, String(volume)]);
    return { content: [{ type: "text", text: out || `Volume set: ${stream} → ${volume}` }] };
  }
  const out = await run("termux-volume");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

server.tool("media_player", "Control media playback", {
  action: z.enum(["play", "pause", "stop", "info"]).describe("Media player action"),
  file: z.string().optional().describe("File path to play (for play action)"),
}, async ({ action, file }) => {
  const args = action === "play" && file ? [action, file] : [action];
  const out = await run("termux-media-player", args);
  return { content: [{ type: "text", text: out || `Media: ${action}` }] };
});

server.tool("microphone_record", "Record audio from microphone", {
  file: z.string().default("/data/data/com.termux/files/home/recording.m4a").describe("Output file path"),
  duration: z.number().default(5).describe("Recording duration in seconds"),
}, async ({ file, duration }) => {
  const out = await run("termux-microphone-record", ["-f", file, "-l", String(duration)]);
  return { content: [{ type: "text", text: out || `Recording saved to ${file}` }] };
});

// ── UI / Feedback ────────────────────────────────────────
server.tool("toast", "Show a brief toast popup on screen", {
  text: z.string().describe("Toast message"),
  position: z.enum(["top", "middle", "bottom"]).default("middle").describe("Toast position"),
}, async ({ text, position }) => {
  const out = await run("termux-toast", ["-g", position, text]);
  return { content: [{ type: "text", text: out || "Toast shown." }] };
});

server.tool("vibrate", "Vibrate the device", {
  duration: z.number().default(500).describe("Duration in milliseconds"),
}, async ({ duration }) => {
  const out = await run("termux-vibrate", ["-d", String(duration)]);
  return { content: [{ type: "text", text: out || "Vibrated." }] };
});

server.tool("dialog", "Show an interactive dialog and get user input", {
  type: z.enum(["confirm", "text", "date", "time", "spinner", "speech"]).default("text").describe("Dialog widget type"),
  title: z.string().optional().describe("Dialog title"),
  hint: z.string().optional().describe("Input hint text"),
  values: z.string().optional().describe("Comma-separated values for spinner"),
}, async ({ type, title, hint, values }) => {
  const args = [type];
  if (title) args.push("-t", title);
  if (hint) args.push("-i", hint);
  if (values) args.push("-v", values);
  const out = await run("termux-dialog", args);
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

// ── Device / Hardware ────────────────────────────────────
server.tool("wifi_info", "Get current WiFi connection details", {}, async () => {
  const out = await run("termux-wifi-connectioninfo");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

server.tool("telephony_info", "Get telephony/SIM/carrier info", {}, async () => {
  const out = await run("termux-telephony-deviceinfo");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

server.tool("sensor", "Read device sensor data (accelerometer, gyroscope, etc.)", {
  sensor: z.string().optional().describe("Sensor name (omit to list available sensors)"),
  count: z.number().default(1).describe("Number of readings to take"),
}, async ({ sensor, count }) => {
  if (!sensor) {
    const out = await run("termux-sensor", ["-l"]);
    return { content: [{ type: "text", text: out }] };
  }
  const out = await run("termux-sensor", ["-s", sensor, "-n", String(count)]);
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

server.tool("brightness", "Set screen brightness", {
  level: z.number().min(0).max(255).describe("Brightness level 0-255"),
}, async ({ level }) => {
  const out = await run("termux-brightness", [String(level)]);
  return { content: [{ type: "text", text: out || `Brightness set to ${level}` }] };
});

server.tool("torch", "Toggle flashlight on/off", {
  enabled: z.boolean().describe("true = on, false = off"),
}, async ({ enabled }) => {
  const out = await run("termux-torch", [enabled ? "on" : "off"]);
  return { content: [{ type: "text", text: out || `Torch ${enabled ? "on" : "off"}.` }] };
});

server.tool("fingerprint", "Authenticate via fingerprint sensor", {}, async () => {
  const out = await run("termux-fingerprint");
  return { content: [{ type: "text", text: tryParseJSON(out) }] };
});

// ── Claude Code relay ────────────────────────────────────
const CLAUDE_TIMEOUT = 300000; // 5 minutes max for Claude responses

async function handleClaude(req, res) {
  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());

    const message = body.message;
    if (!message) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "message is required" }));
      return;
    }

    const args = ["-p", "--output-format", "json"];

    if (body.resume_session) {
      args.push("--resume", body.resume_session);
    }
    if (body.system_prompt) {
      args.push("--append-system-prompt", body.system_prompt);
    }
    if (body.model) {
      args.push("--model", body.model);
    }

    args.push(message);

    const { stdout, stderr } = await exec("claude", args, {
      timeout: body.timeout_ms || CLAUDE_TIMEOUT,
      maxBuffer: 10 * 1024 * 1024, // 10MB for long responses
      env: {
        ...process.env,
        TMPDIR: process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp",
      },
    });

    // claude --output-format json returns a JSON object
    const result = stdout || stderr || '{"error": "no output"}';
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(result);
  } catch (e) {
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      error: e.message,
      killed: e.killed || false,
      signal: e.signal || null,
    }));
  }
}

// ── Permission approval endpoint (for remote Claude Code hooks) ──
const APPROVE_TIMEOUT = 120000; // 2 minutes to respond

async function handleApprove(req, res) {
  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());

    const tool = body.tool_name || body.tool || "unknown";
    const input = typeof body.tool_input === "string"
      ? body.tool_input
      : JSON.stringify(body.tool_input || {});
    const preview = input.length > 300 ? input.slice(0, 300) + "…" : input;

    // Notify + vibrate so user knows to look at phone
    await Promise.all([
      run("termux-vibrate", ["-d", "500"]),
      run("termux-notification", [
        "-t", "Claude Code Permission",
        "-c", `${tool}: ${preview}`,
        "-i", "claude-approve",
        "--priority", "high",
      ]),
    ]);

    // Show confirm dialog - blocks until user taps
    const dialogResult = await exec("termux-dialog", ["confirm", "-t", "Claude Code Permission", "-i", `Allow ${tool}?\n\n${preview}`], {
      timeout: APPROVE_TIMEOUT,
      maxBuffer: 1024 * 1024,
      env: { ...process.env, TMPDIR: process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp" },
    });

    const parsed = JSON.parse(dialogResult.stdout);
    // termux-dialog confirm returns {"code":0,"text":"yes"} or {"code":-1,"text":"no"}
    const allowed = parsed.code === 0 && parsed.text === "yes";

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ decision: allowed ? "allow" : "deny" }));
  } catch (e) {
    // Timeout or error = deny for safety
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ decision: "deny", reason: e.message }));
  }
}

// ── Start server ─────────────────────────────────────────
const PORT = 8378;

const httpServer = createServer(async (req, res) => {
  if (req.url === "/claude" && req.method === "POST") {
    await handleClaude(req, res);
  } else if (req.url === "/approve" && req.method === "POST") {
    await handleApprove(req, res);
  } else if (req.url === "/mcp" && req.method === "POST") {
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => transport.close());
    await server.connect(transport);
    await transport.handleRequest(req, res);
  } else if (req.url === "/mcp" && (req.method === "GET" || req.method === "DELETE")) {
    res.writeHead(405).end("Method not allowed in stateless mode");
  } else {
    res.writeHead(404).end("Not found");
  }
});

httpServer.listen(PORT, "127.0.0.1", () => {
  console.error(`termux-mcp server listening on http://127.0.0.1:${PORT}/mcp`);
});
