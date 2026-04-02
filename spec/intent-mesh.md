# Intent Mesh Protocol Specification

**Version:** 0.1.0
**Date:** 2026-04-02
**Status:** Working Draft — implemented and running in production

## Abstract

Intent Mesh is a protocol for orchestrating collaboration between multiple LLMs on a single Android device using Android Intents and direct API calls as the transport layer. Each LLM contributes its unique capabilities (real-time data access, specialized analysis, tool access) and results are synthesized into a unified output.

## 1. Motivation

No single LLM excels at everything. Claude has strong reasoning and tool use. Grok has real-time X/Twitter access. Gemini has deep Google ecosystem integration. Today, users must manually copy-paste between them.

Intent Mesh automates this. The Android device becomes a **multi-LLM orchestration bus** where:
- Each LLM runs as a node with its own tools and data access
- Work is routed between nodes via Android Intents or direct API calls
- Results are synthesized by the initiating node

## 2. Concepts

### 2.1 Mesh Node

A **mesh node** is an LLM instance that can send and receive work. Each node has:
- A **transport** (how it receives/sends messages): Intent, API, clipboard
- A **capability set** (what it's uniquely good at): tool access, real-time data, domain expertise
- A **role** in the mesh: initiator, contributor, synthesizer

### 2.2 Mesh Transaction

A **mesh transaction** is a complete round-trip:
1. **Initiator** prepares context and a request
2. **Transport** delivers the request to a contributor node
3. **Contributor** processes the request using its unique capabilities
4. **Transport** returns the response to the initiator
5. **Initiator** synthesizes the response with its own analysis

### 2.3 Transport Mechanisms

#### Direct API (Preferred)
The initiating LLM calls the contributor's API directly. This is the most reliable transport.

```
Claude → xAI API → Grok → xAI API → Claude
```

- Fully programmatic, no user interaction needed
- Supports structured input/output
- Requires API keys

#### Intent Bridge (Android-native)
Uses Android's `PROCESS_TEXT` intent to send text to another LLM's app.

```
Claude → android.send_intent(PROCESS_TEXT) → Grok App → clipboard → Claude
```

- Works with any LLM that has an Android app supporting PROCESS_TEXT
- Return channel uses clipboard monitoring (polling)
- No API key needed, but less reliable

#### Clipboard Bridge (Legacy)
A polling-based approach where:
1. Initiator writes to clipboard
2. Contributor's app reads clipboard
3. Contributor writes response to clipboard
4. Bridge daemon detects the change and delivers to initiator

This is the fallback when neither API nor Intent transport is available.

## 3. Message Format

### 3.1 Mesh Request

A mesh request contains:

```json
{
  "mesh_version": "0.1.0",
  "transaction_id": "uuid",
  "from": "claude",
  "to": "grok",
  "request_type": "analysis",
  "context": {
    "description": "Technical analysis context from Taichi",
    "data": { ... }
  },
  "prompt": "Analyze real-time X/Twitter sentiment for BTC. Focus on whale activity, fear/greed narrative, and any breaking news.",
  "response_format": "structured"
}
```

### 3.2 Mesh Response

```json
{
  "mesh_version": "0.1.0",
  "transaction_id": "uuid",
  "from": "grok",
  "to": "claude",
  "response_type": "analysis",
  "data": {
    "sentiment": { ... },
    "narratives": [ ... ],
    "breaking_news": [ ... ]
  },
  "confidence": 0.85,
  "timestamp": "2026-04-02T10:30:00Z"
}
```

## 4. Synthesis

The initiator node is responsible for **synthesis** — merging its own analysis with contributor responses into a final output. The synthesis step is where the mesh creates value beyond what any single LLM could produce.

Example synthesis (Claude as initiator):

```
Claude's Taichi TA: RSI neutral, MACD bullish cross, OBV accumulating
Grok's X Sentiment: Extreme fear, "crypto is dead" dominant, whale accumulation quiet
                              ↓
Intent Mesh Synthesis: Textbook divergence — public panic + smart money loading.
                       Tradeable setup with defined risk.
```

## 5. Implementation

### 5.1 Bridge Daemon (`bridge.sh`)

Monitors the clipboard for responses from contributor LLMs:

```bash
# Polls clipboard every N seconds
# Detects new content (response from contributor)
# Writes to inbox file for initiator to read
```

### 5.2 Mesh CLI (`mesh.sh`)

Command-line interface for Intent Mesh transactions:

```bash
mesh.sh send "message"     # One-shot to Grok via xAI API
mesh.sh chat "message"     # With full conversation history
mesh.sh context "text"     # Inject system context
mesh.sh log                # Show conversation history
mesh.sh clear              # Reset conversation
```

### 5.3 Integration with Hub

Intent Mesh operates alongside Hub but at a different layer:
- **Hub** routes tool calls to CapApps (LLM → tools)
- **Intent Mesh** routes analysis requests between LLMs (LLM → LLM)

They can compose: Claude calls Taichi tools via Hub, then sends the results to Grok via Intent Mesh, then synthesizes both into a final signal.

## 6. Security Considerations

- API keys must be stored securely (environment variables, not hardcoded)
- Clipboard transport is inherently insecure — any app can read the clipboard
- Intent transport is process-isolated by Android's security model
- Mesh transactions should not include sensitive user data unless the transport is API-based

## 7. Future Directions

- **More nodes**: Gemini (Google ecosystem), local models (Llama via Ollama)
- **Structured protocols**: Replace free-text prompts with typed schemas
- **Async mesh**: Queue-based transactions for long-running analysis
- **Mesh discovery**: Nodes advertise their capabilities, initiator auto-selects the best contributor for a task
