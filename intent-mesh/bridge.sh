#!/bin/bash
# Intent Mesh Bridge — clipboard monitor for Grok → Claude return channel
#
# This daemon watches the clipboard for new content (Grok responses)
# and writes them to inbox.txt for Claude Code to read.
#
# Outbound (Claude → Grok) is handled directly by Claude Code via MCP tools.
#
# Usage: bash bridge.sh [poll_interval_seconds]

MESH_DIR="${INTENT_MESH_DIR:-$HOME/intent-mesh}"
INBOX="$MESH_DIR/inbox.txt"
RECV_LOG="$MESH_DIR/recv.log"
POLL="${1:-3}"

mkdir -p "$MESH_DIR"
touch "$RECV_LOG"

# Verify Termux:API is available
if ! command -v termux-clipboard-get &>/dev/null; then
    echo "[bridge] ERROR: termux-clipboard-get not found. Install Termux:API." >&2
    exit 1
fi

# Snapshot current clipboard as baseline
LAST_CLIP=$(termux-clipboard-get 2>/dev/null || echo "")

echo "[bridge] Clipboard monitor started (poll: ${POLL}s)"
echo "[bridge] Inbox: $INBOX"
echo "[bridge] Baseline clipboard: ${LAST_CLIP:0:50}..."

while true; do
    CUR_CLIP=$(termux-clipboard-get 2>/dev/null || echo "")
    if [ -n "$CUR_CLIP" ] && [ "$CUR_CLIP" != "$LAST_CLIP" ]; then
        echo "[bridge] $(date +%H:%M:%S) New clipboard content detected (${#CUR_CLIP} chars)"
        echo "$CUR_CLIP" > "$INBOX"
        echo "$(date -Iseconds) ${CUR_CLIP:0:80}..." >> "$RECV_LOG"
        LAST_CLIP="$CUR_CLIP"
    fi
    sleep "$POLL"
done
