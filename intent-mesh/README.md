# Intent Mesh (Experimental)

> **Status: Early experiment.** The scripts work but the workflow is manual and rough. This is a proof of concept for where multi-LLM routing is heading.

Multi-LLM routing for Android. Route work between Claude, Grok, and other LLMs using Android Intents and direct API calls.

## What is this?

Intent Mesh lets multiple LLMs collaborate on the same device. Each LLM contributes what it's best at:

- **Claude** (via Taichi): Technical analysis, structured reasoning, tool orchestration
- **Grok** (via xAI API): Real-time X/Twitter sentiment, breaking news, social signals

The mesh synthesizes both into a single output that neither LLM could produce alone.

## Components

### `mesh.sh` — CLI for Grok communication

```bash
# Set your xAI API key
export XAI_API_KEY="your-key-here"

# One-shot message
./mesh.sh send "What's the X sentiment on BTC right now?"

# With conversation history
./mesh.sh chat "Compare ETH sentiment to BTC"

# Inject Claude's analysis as context
./mesh.sh context "$(cat taichi-analysis.json)"
./mesh.sh chat "Given this technical analysis, what does X say?"
```

### `bridge.sh` — Clipboard bridge daemon (legacy)

Monitors the clipboard for responses from LLMs launched via Android Intents. Used when API access isn't available.

```bash
./bridge.sh 3  # Poll every 3 seconds
```

## Example Output

See the [notes/](notes/) directory for real Intent Mesh synthesis outputs combining Claude's Taichi technical analysis with Grok's X/Twitter sentiment data.
