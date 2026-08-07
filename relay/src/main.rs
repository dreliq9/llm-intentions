mod session;
mod shared_store;

use axum::{
    extract::{
        ws::{Message, WebSocket},
        DefaultBodyLimit, Path as AxumPath, State, WebSocketUpgrade,
    },
    http::{header::AUTHORIZATION, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use intentions_relay::{
    ensure_text_frame_bound, now_ms, relay_state_path_from_env, verify_device_auth, AuthChallenge,
    ClientFrame, EnrollmentRequest, EnrollmentResponse, RelayError, ServerFrame,
    DEFAULT_AUTH_CHALLENGE_TTL_MS, DEFAULT_ENROLLMENT_TTL_MS, MAX_TEXT_FRAME_BYTES,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use session::{DispatchFailure, LiveSessionRouter, SESSION_OUTBOUND_CAPACITY};
use sha2::{Digest, Sha256};
use shared_store::SharedEnrollmentStore;
use std::{net::SocketAddr, sync::Arc, time::Duration};
use subtle::ConstantTimeEq;
use tokio::{
    sync::mpsc,
    time::{interval, timeout},
};

const MCP_MODERN_PROTOCOL_VERSION: &str = "2026-07-28";
const MAX_TEST_TIMEOUT_MS: u64 = 30_000;
const MAX_TEST_PRINCIPAL_CHARS: usize = 256;
const ALLOWED_TEST_TOOLS: [&str; 2] = ["hub.relay_echo", "hub.relay_confirm_echo"];

#[derive(Clone)]
struct AppState {
    enrollments: Arc<SharedEnrollmentStore>,
    sessions: Arc<LiveSessionRouter>,
    admin_token_digest: Option<[u8; 32]>,
}

#[derive(Serialize)]
struct ErrorBody {
    error: String,
}

#[derive(Debug, Deserialize)]
struct TestDispatchRequest {
    principal_id: String,
    #[serde(default)]
    timeout_ms: Option<u64>,
    request: Value,
}

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("intentions-relay: {error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let command = args.next();
    let store = Arc::new(SharedEnrollmentStore::open(relay_state_path_from_env())?);

    match command.as_deref() {
        None | Some("help") | Some("--help") | Some("-h") => {
            print_usage();
            Ok(())
        }
        Some("issue-enrollment") => {
            let account_id = args.next().ok_or("missing account id")?;
            let ttl_seconds = args
                .next()
                .map(|value| value.parse::<u64>())
                .transpose()?
                .unwrap_or(DEFAULT_ENROLLMENT_TTL_MS / 1000);
            if args.next().is_some() {
                return Err("too many arguments".into());
            }
            let token = store.issue_enrollment_token(
                account_id,
                ttl_seconds.saturating_mul(1000),
                now_ms(),
            )?;
            println!("{token}");
            Ok(())
        }
        Some("revoke") => {
            let device_id = args.next().ok_or("missing device id")?;
            if args.next().is_some() {
                return Err("too many arguments".into());
            }
            if store.revoke(&device_id, now_ms())? {
                println!("revoked {device_id}");
                Ok(())
            } else {
                Err("device not found".into())
            }
        }
        Some("list-devices") => {
            if args.next().is_some() {
                return Err("too many arguments".into());
            }
            for device in store.list_devices()? {
                println!(
                    "{}\t{}\t{}\t{}",
                    device.device_id,
                    device.account_id,
                    if device.active() { "active" } else { "revoked" },
                    device.device_label,
                );
            }
            Ok(())
        }
        Some("serve") => {
            let address = args.next().ok_or(
                "serve requires an explicit loopback listen address, e.g. 127.0.0.1:8787",
            )?;
            if args.next().is_some() {
                return Err("too many arguments".into());
            }
            let address: SocketAddr = address.parse()?;
            if !address.ip().is_loopback() {
                return Err(
                    "direct relay listener must be loopback-only; publish it through a TLS reverse proxy"
                        .into(),
                );
            }
            serve(address, store).await
        }
        Some(other) => Err(format!("unknown command: {other}").into()),
    }
}

fn print_usage() {
    println!(
        "LLM Intentions Relay\n\n\
         No command binds a network port by default.\n\n\
         Commands:\n\
           intentions-relay issue-enrollment <account-id> [ttl-seconds]\n\
           intentions-relay list-devices\n\
           intentions-relay revoke <device-id>\n\
           intentions-relay serve <loopback-address>\n\n\
         Environment:\n\
           INTENTIONS_RELAY_STATE       path to app state JSON\n\
           INTENTIONS_RELAY_ADMIN_TOKEN enables loopback synthetic test dispatch\n\n\
         Public deployment must terminate TLS in front of the loopback listener.\n\
         Do not proxy the admin test-dispatch route to untrusted networks."
    );
}

async fn serve(
    address: SocketAddr,
    enrollments: Arc<SharedEnrollmentStore>,
) -> Result<(), Box<dyn std::error::Error>> {
    let admin_token_digest = admin_token_digest_from_env();
    let state = AppState {
        enrollments,
        sessions: Arc::new(LiveSessionRouter::new()),
        admin_token_digest,
    };
    let mut app = Router::new()
        .route("/health", get(health))
        .route("/v1/device/enroll", post(enroll_device))
        .route("/v1/device/connect/{device_id}", get(connect_device));

    if state.admin_token_digest.is_some() {
        app = app.route(
            "/v1/admin/test-dispatch/{device_id}",
            post(test_dispatch),
        );
    }

    let app = app
        .layer(DefaultBodyLimit::max(MAX_TEXT_FRAME_BYTES))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(address).await?;
    eprintln!(
        "intentions-relay listening on http://{address}; expose externally only through HTTPS/WSS"
    );
    axum::serve(listener, app).await?;
    Ok(())
}

async fn health() -> &'static str {
    "ok"
}

async fn enroll_device(
    State(state): State<AppState>,
    Json(request): Json<EnrollmentRequest>,
) -> Response {
    match state.enrollments.redeem(&request, now_ms()) {
        Ok(device) => (
            StatusCode::CREATED,
            Json(EnrollmentResponse {
                device_id: device.device_id,
                account_id: device.account_id,
            }),
        )
            .into_response(),
        Err(error) => relay_error_response(error),
    }
}

async fn test_dispatch(
    AxumPath(device_id): AxumPath<String>,
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<TestDispatchRequest>,
) -> Response {
    if !admin_authorized(&state, &headers) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(ErrorBody {
                error: "admin authentication required".into(),
            }),
        )
            .into_response();
    }

    if !valid_test_principal(&body.principal_id) || !valid_synthetic_request(&body.request) {
        return (
            StatusCode::FORBIDDEN,
            Json(ErrorBody {
                error: "synthetic test dispatch scope rejected".into(),
            }),
        )
            .into_response();
    }

    if state.enrollments.get_active_device(&device_id).is_none() {
        return relay_error_response(RelayError::DeviceUnavailable);
    }

    let timeout_ms = body
        .timeout_ms
        .unwrap_or(10_000)
        .clamp(1, MAX_TEST_TIMEOUT_MS);
    let deadline_ms = now_ms().saturating_add(timeout_ms);
    match state
        .sessions
        .dispatch(
            &device_id,
            body.principal_id,
            body.request,
            deadline_ms,
        )
        .await
    {
        Ok(response) => (StatusCode::OK, Json(response)).into_response(),
        Err(DispatchFailure::Offline) => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(ErrorBody {
                error: "device is offline".into(),
            }),
        )
            .into_response(),
        Err(DispatchFailure::Busy) => (
            StatusCode::TOO_MANY_REQUESTS,
            Json(ErrorBody {
                error: "device relay is busy".into(),
            }),
        )
            .into_response(),
        Err(DispatchFailure::SessionReplaced) => (
            StatusCode::CONFLICT,
            Json(ErrorBody {
                error: "device session was replaced".into(),
            }),
        )
            .into_response(),
        Err(DispatchFailure::Deadline) => (
            StatusCode::GATEWAY_TIMEOUT,
            Json(ErrorBody {
                error: "device dispatch deadline expired".into(),
            }),
        )
            .into_response(),
        Err(DispatchFailure::DeviceError(code, message)) => (
            StatusCode::BAD_GATEWAY,
            Json(ErrorBody {
                error: format!("device error {code}: {message}"),
            }),
        )
            .into_response(),
    }
}

fn valid_test_principal(principal_id: &str) -> bool {
    principal_id.starts_with("test:")
        && principal_id.len() <= MAX_TEST_PRINCIPAL_CHARS
        && principal_id.len() > "test:".len()
}

fn valid_synthetic_request(request: &Value) -> bool {
    let Some(object) = request.as_object() else {
        return false;
    };
    if object.get("jsonrpc").and_then(Value::as_str) != Some("2.0")
        || object.get("method").and_then(Value::as_str) != Some("tools/call")
        || object.get("id").is_none_or(Value::is_null)
    {
        return false;
    }
    let Some(params) = object.get("params").and_then(Value::as_object) else {
        return false;
    };
    let Some(tool_name) = params.get("name").and_then(Value::as_str) else {
        return false;
    };
    if !ALLOWED_TEST_TOOLS.contains(&tool_name) {
        return false;
    }
    params
        .get("_meta")
        .and_then(Value::as_object)
        .and_then(|meta| meta.get("io.modelcontextprotocol/protocolVersion"))
        .and_then(Value::as_str)
        == Some(MCP_MODERN_PROTOCOL_VERSION)
}

fn admin_token_digest_from_env() -> Option<[u8; 32]> {
    let token = std::env::var("INTENTIONS_RELAY_ADMIN_TOKEN").ok()?;
    let trimmed = token.trim();
    if trimmed.len() < 24 || trimmed.len() > 512 {
        eprintln!("INTENTIONS_RELAY_ADMIN_TOKEN ignored: expected 24..512 characters");
        return None;
    }
    Some(Sha256::digest(trimmed.as_bytes()).into())
}

fn admin_authorized(state: &AppState, headers: &HeaderMap) -> bool {
    let Some(expected) = state.admin_token_digest else {
        return false;
    };
    let Some(value) = headers.get(AUTHORIZATION).and_then(|value| value.to_str().ok()) else {
        return false;
    };
    let Some(token) = value.strip_prefix("Bearer ") else {
        return false;
    };
    let supplied: [u8; 32] = Sha256::digest(token.as_bytes()).into();
    bool::from(expected.ct_eq(&supplied))
}

async fn connect_device(
    AxumPath(device_id): AxumPath<String>,
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> Response {
    if state.enrollments.get_active_device(&device_id).is_none() {
        return relay_error_response(RelayError::DeviceUnavailable);
    }

    ws.max_message_size(MAX_TEXT_FRAME_BYTES)
        .on_upgrade(move |socket| handle_device_socket(socket, device_id, state))
}

async fn handle_device_socket(mut socket: WebSocket, device_id: String, state: AppState) {
    let Some(device) = state.enrollments.get_active_device(&device_id) else {
        let _ = socket.send(Message::Close(None)).await;
        return;
    };

    let challenge = AuthChallenge::issue(
        device_id.clone(),
        now_ms(),
        DEFAULT_AUTH_CHALLENGE_TTL_MS,
    );
    if send_server_frame(
        &mut socket,
        &ServerFrame::AuthChallenge {
            challenge: challenge.clone(),
        },
    )
    .await
    .is_err()
    {
        return;
    }

    let auth_message = match timeout(
        Duration::from_millis(DEFAULT_AUTH_CHALLENGE_TTL_MS),
        socket.next(),
    )
    .await
    {
        Ok(Some(Ok(Message::Text(text)))) => text,
        _ => {
            let _ = socket.send(Message::Close(None)).await;
            return;
        }
    };

    if ensure_text_frame_bound(auth_message.as_str()).is_err() {
        let _ = socket.send(Message::Close(None)).await;
        return;
    }
    let Ok(ClientFrame::AuthResponse { signature_der_b64 }) =
        serde_json::from_str::<ClientFrame>(auth_message.as_str())
    else {
        let _ = socket.send(Message::Close(None)).await;
        return;
    };

    if verify_device_auth(&device, &challenge, &signature_der_b64, now_ms()).is_err() {
        let _ = socket.send(Message::Close(None)).await;
        return;
    }

    if send_server_frame(
        &mut socket,
        &ServerFrame::Authenticated {
            device_id: device_id.clone(),
            session_id: challenge.session_id.clone(),
        },
    )
    .await
    .is_err()
    {
        return;
    }

    let session_id = challenge.session_id.clone();
    let (outbound_tx, mut outbound_rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
    state
        .sessions
        .register(device_id.clone(), session_id.clone(), outbound_tx)
        .await;

    let (mut ws_sender, mut ws_receiver) = socket.split();
    let writer = tokio::spawn(async move {
        while let Some(frame) = outbound_rx.recv().await {
            let close_after = matches!(&frame, ServerFrame::SessionReplaced { .. });
            let Ok(text) = serde_json::to_string(&frame) else {
                break;
            };
            if ensure_text_frame_bound(&text).is_err()
                || ws_sender.send(Message::Text(text.into())).await.is_err()
            {
                break;
            }
            if close_after {
                let _ = ws_sender.send(Message::Close(None)).await;
                break;
            }
        }
    });

    let mut revocation_check = interval(Duration::from_secs(30));
    loop {
        tokio::select! {
            _ = revocation_check.tick() => {
                if state.enrollments.get_active_device(&device_id).is_none() {
                    break;
                }
            }
            message = ws_receiver.next() => {
                match message {
                    Some(Ok(Message::Text(text))) => {
                        if ensure_text_frame_bound(text.as_str()).is_err() {
                            break;
                        }
                        let frame = match serde_json::from_str::<ClientFrame>(text.as_str()) {
                            Ok(frame) => frame,
                            Err(_) => break,
                        };
                        match frame {
                            ClientFrame::DispatchResponse { request_id, response } => {
                                let _ = state.sessions.complete_response(
                                    &device_id,
                                    &session_id,
                                    &request_id,
                                    Ok(response),
                                ).await;
                            }
                            ClientFrame::DispatchError { request_id, code, message } => {
                                let _ = state.sessions.complete_response(
                                    &device_id,
                                    &session_id,
                                    &request_id,
                                    Err(DispatchFailure::DeviceError(code, message)),
                                ).await;
                            }
                            ClientFrame::AuthResponse { .. } => break,
                        }
                    }
                    Some(Ok(Message::Ping(_))) | Some(Ok(Message::Pong(_))) => {}
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(Message::Binary(_))) => break,
                }
            }
        }
    }

    state.sessions.unregister(&device_id, &session_id).await;
    writer.abort();
}

async fn send_server_frame(socket: &mut WebSocket, frame: &ServerFrame) -> Result<(), ()> {
    let text = serde_json::to_string(frame).map_err(|_| ())?;
    ensure_text_frame_bound(&text).map_err(|_| ())?;
    socket.send(Message::Text(text.into())).await.map_err(|_| ())
}

fn relay_error_response(error: RelayError) -> Response {
    let status = match error {
        RelayError::InvalidEnrollmentToken
        | RelayError::InvalidSignature
        | RelayError::InvalidPublicKey
        | RelayError::InvalidDeviceLabel
        | RelayError::InvalidRequest(_)
        | RelayError::Base64(_) => StatusCode::BAD_REQUEST,
        RelayError::DeviceUnavailable => StatusCode::NOT_FOUND,
        RelayError::ChallengeExpired | RelayError::ChallengeDeviceMismatch => {
            StatusCode::UNAUTHORIZED
        }
        RelayError::Io(_) | RelayError::Json(_) => StatusCode::INTERNAL_SERVER_ERROR,
    };
    (status, Json(ErrorBody { error: error.to_string() })).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn synthetic_scope_rejects_real_tools_and_non_test_principals() {
        assert!(valid_test_principal("test:relay"));
        assert!(!valid_test_principal("provider:user"));

        let good = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "hub.relay_echo",
                "arguments": {"message": "hello"},
                "_meta": {"io.modelcontextprotocol/protocolVersion": MCP_MODERN_PROTOCOL_VERSION}
            }
        });
        assert!(valid_synthetic_request(&good));

        let real_tool = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "people.contacts_search",
                "arguments": {},
                "_meta": {"io.modelcontextprotocol/protocolVersion": MCP_MODERN_PROTOCOL_VERSION}
            }
        });
        assert!(!valid_synthetic_request(&real_tool));
    }
}
