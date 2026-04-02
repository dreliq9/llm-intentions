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

const HUB_URL = "http://127.0.0.1:8379/mcp";

async function hubRPC(method, params, id) {
  const body = { jsonrpc: "2.0", method };
  if (id !== undefined) body.id = id;
  if (params !== undefined) body.params = params;

  const res = await fetch(HUB_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json, text/event-stream",
    },
    body: JSON.stringify(body),
  });

  if (id === undefined) return null;
  const text = await res.text();
  for (const line of text.split("\n")) {
    if (line.startsWith("data: ")) return JSON.parse(line.slice(6));
  }
  return JSON.parse(text);
}

// Get server info from hub
const initRes = await hubRPC("initialize", {
  protocolVersion: "2024-11-05",
  capabilities: {},
  clientInfo: { name: "hub-proxy", version: "1.0" },
}, 1);
await hubRPC("notifications/initialized");
const info = initRes.result;
console.error(`hub-proxy: connected to ${info.serverInfo.name} v${info.serverInfo.version}`);

const server = new Server(
  { name: info.serverInfo.name, version: info.serverInfo.version },
  { capabilities: { tools: {} } },
);

server.setRequestHandler(ListToolsRequestSchema, async () => {
  const res = await hubRPC("tools/list", {}, 10);
  return res.result;
});

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const res = await hubRPC("tools/call", req.params, 20);
  if (res.error) throw new Error(res.error.message);
  return res.result;
});

const PORT = 8381;
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
