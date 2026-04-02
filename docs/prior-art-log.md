# LLM Intentions — Prior Art Discovery Log

**Date:** 2026-04-02
**Author:** dreliq9
**Purpose:** Document the independent discovery of a similar project and establish timeline of prior work

---

## Summary

On 2026-04-02, while researching whether to patent or open source the "CapApp" concept (Android apps that exist purely as MCP tool providers for LLMs), a GitHub search revealed a project with a strikingly similar architecture:

**[system-pclub/mobile-mcp](https://github.com/system-pclub/mobile-mcp)**

This project describes:
- "Tool-App" — apps that expose capabilities to LLMs (identical to our CapApp concept)
- "LLM-App" — an AI assistant that discovers and calls tool-apps (identical to our Hub)
- Android Intents as the transport layer (same as our architecture)
- On-device MCP for capability discovery (same as our architecture)
- A formal spec: `mobile-mcp_spec_v1.md`

## Key Distinction: We Had a Working Product

At the time of this discovery, LLM Intentions was already a **fully operational system** running on a physical Android device, not a spec or prototype:

### Working components as of 2026-04-02:
- **Hub** (com.llmintentions.hub) — MCP gateway running on port 8379 via streamable-http, aggregating tools from multiple sources with namespace routing
- **Files** (com.llmintentions.files) — file management CapApp (15 tools across files.* and fs.* namespaces)
- **Notify** (com.llmintentions.notify) — notification management CapApp (7 tools)
- **People** (com.llmintentions.people) — contacts and calendar CapApp (10 tools)
- **Taichi** (com.taichi) — crypto analysis CapApp (41 tools, proprietary)
- **termux-mcp** — MCP server exposing Android APIs via Termux on port 8378
- **Intent Mesh** — multi-LLM routing via Android intents and xAI API (e.g., Grok-Claude bridge)

**Total: 118 tools across 8 sources, running in production on a real device.**

### Capabilities demonstrated prior to this date:
- Multi-source tool aggregation with automatic namespacing
- Real-time Android device control (battery, clipboard, wifi, volume, torch, notifications, media, brightness, ringer)
- File system operations, contact management, calendar integration
- Crypto market analysis (technical analysis, backtesting, portfolio management)
- Cross-LLM communication via Intent Mesh (Claude <-> Grok bridge)
- Deep link generation and Android app launching
- Full MCP compliance over streamable-http transport

## Comparison at Time of Discovery

| Aspect | LLM Intentions | mobile-mcp (system-pclub) |
|---|---|---|
| Status | Production system on real device | Published spec |
| Tool count | 118 tools, 8 sources | Unknown |
| Architecture | Distributed CapApps + Hub gateway | LLM-APP + Tool-APP (similar concept) |
| Transport | Android Intents + MCP streamable-http | Android Intents + MCP |
| Multi-LLM | Intent Mesh (working Grok-Claude bridge) | Not addressed |
| Commercial CapApp | Taichi (41 crypto tools) | None |
| Public visibility | Private (not yet published) | Public GitHub repo |

## Decision

Open source LLM Intentions under Apache 2.0 license. Keep Taichi (com.taichi) proprietary as the commercial proof-of-concept. Prioritize getting the Hub, example CapApps, and the Intent Mesh spec published on GitHub immediately.

---

*This log serves as a timestamped record of independent prior work on the CapApp/LLM Intentions architecture.*
