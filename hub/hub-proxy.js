#!/usr/bin/env node
// Thin MCP SDK proxy: uses the exact same transport as termux-api
// but forwards all requests to the hub server on port 8379.
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { createServer } from "node:http";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const HUB_URL = process.env.HUB_URL || "http://127.0.0.1:8379/mcp";
const PORT = parseInt(process.env.HUB_PROXY_PORT || "8381", 10);
const REQUEST_TIMEOUT = 30000;

async function hubRPC(method, params, id) {
  const body = { jsonrpc: "2.0", method };
  if (id !== undefined) body.id = id;
  if (params !== undefined) body.params = params;

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);

  try {
    const res = await fetch(HUB_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });

    if (!res.ok) {
      throw new Error(`Hub returned HTTP ${res.status}: ${res.statusText}`);
    }

    if (id === undefined) return null;
    const text = await res.text();
    for (const line of text.split("\n")) {
      if (line.startsWith("data: ")) return JSON.parse(line.slice(6));
    }
    return JSON.parse(text);
  } finally {
    clearTimeout(timeout);
  }
}

// Get server info from hub
let info;
try {
  const initRes = await hubRPC("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "hub-proxy", version: "1.0" },
  }, 1);
  await hubRPC("notifications/initialized");
  info = initRes.result;
  console.error(`hub-proxy: connected to ${info.serverInfo.name} v${info.serverInfo.version}`);
} catch (e) {
  console.error(`hub-proxy: failed to connect to Hub at ${HUB_URL}: ${e.message}`);
  process.exit(1);
}

const server = new Server(
  { name: info.serverInfo.name, version: info.serverInfo.version },
  { capabilities: { tools: {} } },
);

server.setRequestHandler(ListToolsRequestSchema, async () => {
  try {
    const res = await hubRPC("tools/list", {}, 10);
    return res.result;
  } catch (e) {
    console.error(`hub-proxy: tools/list failed: ${e.message}`);
    throw new Error(`Hub unreachable: ${e.message}`);
  }
});

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  try {
    const res = await hubRPC("tools/call", req.params, 20);
    if (res.error) throw new Error(res.error.message);
    return res.result;
  } catch (e) {
    console.error(`hub-proxy: tools/call failed: ${e.message}`);
    throw e;
  }
});

const httpServer = createServer(async (req, res) => {
  if (req.url === "/mcp" && req.method === "POST") {
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
  console.error(`hub-proxy listening on http://127.0.0.1:${PORT}/mcp`);
});

process.on("SIGTERM", () => {
  console.error("hub-proxy: shutting down");
  httpServer.close(() => process.exit(0));
});
process.on("SIGINT", () => {
  console.error("hub-proxy: shutting down");
  httpServer.close(() => process.exit(0));
});
