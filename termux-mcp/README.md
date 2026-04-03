# termux-mcp

An MCP server that exposes Android device APIs via [Termux:API](https://wiki.termux.com/wiki/Termux:API) as MCP tools.

## Tools

| Tool | Description |
|------|-------------|
| `battery_status` | Battery level, charging status, health |
| `clipboard_get` | Read clipboard contents |
| `clipboard_set` | Write to clipboard |
| `contact_list` | List all contacts |
| `sms_list` | List SMS messages (inbox/sent/draft) |
| `sms_send` | Send an SMS message |
| `call_log` | Recent call history |
| `location` | GPS/network location |
| `notification_send` | Show a notification |
| `notification_list` | List active notifications |
| `camera_photo` | Take a photo |
| `tts_speak` | Text-to-speech |
| `volume` | Get/set audio volume |
| `media_player` | Play/pause/stop media |
| `microphone_record` | Record audio |
| `toast` | Show toast message |
| `vibrate` | Vibrate device |
| `dialog` | Show interactive dialog |
| `wifi_info` | WiFi connection details |
| `telephony_info` | SIM/carrier info |
| `sensor` | Read device sensors |
| `brightness` | Set screen brightness |
| `torch` | Toggle flashlight |
| `fingerprint` | Fingerprint authentication |

## Setup

```bash
# In Termux (not proot)
pkg install nodejs termux-api
cd termux-mcp
npm install
node server.js
```

Server listens on `http://0.0.0.0:8378/mcp`.

## Permission Endpoint

The server also exposes a `/approve` endpoint for remote permission approval workflows. When a tool call needs user confirmation, it shows an Android dialog and vibrates the device.

## Requirements

- [Termux](https://termux.dev)
- [Termux:API](https://wiki.termux.com/wiki/Termux:API) (both the app and the `termux-api` package)
- Node.js 18+
