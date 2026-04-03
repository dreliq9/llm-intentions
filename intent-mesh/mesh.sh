#!/bin/bash
# Intent Mesh v4 — Claude ↔ Grok via xAI API
# Fully autonomous, no clipboard, no intents needed.
#
# Usage:
#   mesh.sh send "message"     — send to Grok, get response
#   mesh.sh chat "message"     — send with full conversation history
#   mesh.sh context "text"     — add system context (e.g. Claude's analysis)
#   mesh.sh log                — show conversation history
#   mesh.sh clear              — reset conversation
#   mesh.sh models             — list available models
#
# Configuration:
#   Set XAI_API_KEY environment variable with your xAI API key.
#   Set GROK_MODEL to override the default model (default: grok-3).

MESH_DIR="${INTENT_MESH_DIR:-$HOME/intent-mesh}"
CONVO_FILE="$MESH_DIR/conversation.json"
LOG_FILE="$MESH_DIR/conversation.log"
API_KEY="${XAI_API_KEY:?Set XAI_API_KEY environment variable}"
MODEL="${GROK_MODEL:-grok-3}"
API_URL="https://api.x.ai/v1/chat/completions"

mkdir -p "$MESH_DIR"

# Initialize conversation file if missing
if [ ! -f "$CONVO_FILE" ]; then
    echo '[]' > "$CONVO_FILE"
fi

case "${1:-}" in
    send)
        shift
        MSG="$*"
        [ -z "$MSG" ] && echo "Usage: mesh.sh send <message>" && exit 1

        # Single-shot: just user message, no history
        RESP=$(curl -sf --connect-timeout 10 --max-time 60 "$API_URL" \
            -H "Authorization: Bearer $API_KEY" \
            -H "Content-Type: application/json" \
            -d "$(jq -n --arg model "$MODEL" --arg msg "$MSG" '{
                model: $model,
                messages: [{"role":"user","content":$msg}]
            }')" 2>/dev/null) || { echo "ERROR: API request failed (network or auth error)" >&2; exit 1; }

        CONTENT=$(echo "$RESP" | jq -r '.choices[0].message.content // .error.message // "ERROR: no response"')
        echo "$CONTENT"

        # Log it
        echo "---[CLAUDE $(date +%H:%M:%S)]---" >> "$LOG_FILE"
        echo "$MSG" >> "$LOG_FILE"
        echo "" >> "$LOG_FILE"
        echo "---[GROK $(date +%H:%M:%S)]---" >> "$LOG_FILE"
        echo "$CONTENT" >> "$LOG_FILE"
        echo "" >> "$LOG_FILE"
        ;;

    chat)
        shift
        MSG="$*"
        [ -z "$MSG" ] && echo "Usage: mesh.sh chat <message>" && exit 1

        # Add user message to history
        HISTORY=$(cat "$CONVO_FILE")
        HISTORY=$(echo "$HISTORY" | jq --arg msg "$MSG" '. + [{"role":"user","content":$msg}]')
        echo "$HISTORY" > "$CONVO_FILE"

        # Send full history
        RESP=$(curl -s --connect-timeout 10 --max-time 60 "$API_URL" \
            -H "Authorization: Bearer $API_KEY" \
            -H "Content-Type: application/json" \
            -d "$(jq -n --arg model "$MODEL" --argjson msgs "$HISTORY" '{
                model: $model,
                messages: $msgs
            }')" 2>/dev/null) || { echo "ERROR: curl failed (network error)" >&2; exit 1; }

        CONTENT=$(echo "$RESP" | jq -r '.choices[0].message.content // .error.message // "ERROR: no response"')
        echo "$CONTENT"

        # Add assistant response to history
        HISTORY=$(cat "$CONVO_FILE")
        HISTORY=$(echo "$HISTORY" | jq --arg msg "$CONTENT" '. + [{"role":"assistant","content":$msg}]')
        echo "$HISTORY" > "$CONVO_FILE"

        # Log it
        echo "---[CLAUDE $(date +%H:%M:%S)]---" >> "$LOG_FILE"
        echo "$MSG" >> "$LOG_FILE"
        echo "" >> "$LOG_FILE"
        echo "---[GROK $(date +%H:%M:%S)]---" >> "$LOG_FILE"
        echo "$CONTENT" >> "$LOG_FILE"
        echo "" >> "$LOG_FILE"
        ;;

    context)
        shift
        CTX="$*"
        [ -z "$CTX" ] && echo "Usage: mesh.sh context <system message>" && exit 1

        # Add system message to conversation
        HISTORY=$(cat "$CONVO_FILE")
        HISTORY=$(echo "$HISTORY" | jq --arg msg "$CTX" '. + [{"role":"system","content":$msg}]')
        echo "$HISTORY" > "$CONVO_FILE"
        echo "Context added to conversation"
        ;;

    log)
        cat "$LOG_FILE" 2>/dev/null || echo "(no history)"
        ;;

    clear)
        echo '[]' > "$CONVO_FILE"
        > "$LOG_FILE"
        echo "Conversation cleared"
        ;;

    models)
        curl -s "https://api.x.ai/v1/models" \
            -H "Authorization: Bearer $API_KEY" | jq -r '.data[].id'
        ;;

    *)
        echo "Intent Mesh v4 — Claude ↔ Grok via xAI API"
        echo ""
        echo "Usage:"
        echo "  mesh.sh send  <msg>   — one-shot message to Grok"
        echo "  mesh.sh chat  <msg>   — message with full conversation history"
        echo "  mesh.sh context <txt> — inject system context into conversation"
        echo "  mesh.sh log           — show conversation log"
        echo "  mesh.sh clear         — reset conversation"
        echo "  mesh.sh models        — list available Grok models"
        ;;
esac
