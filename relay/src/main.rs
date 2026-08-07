use axum::{
    extract::{
        ws::{Message, WebSocket},
        Path as AxumPath, State, WebSocketUpgrade,
    },
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::{SinkExt, StreamExt};
use intentions_relay::{
    ensure_text_frame_bound, now_ms, relay_state_path_from_env, verify_device_auth, AuthChallenge,
    ClientFrame, EnrollmentRequest, EnrollmentResponse, EnrollmentStore, RelayError, ServerFrame,
    DEFAULT_AUTH_CHALLENGE_TTL_MS, DEFAULT_ENROLLMENT_TTL_MS,
};
use serde::Serialize;
use std::{net::SocketAddr, sync::Arc, time::Duration};
use tokio::time::{interval, timeout};

#[derive(Clone)]
struct AppState {
    enrollments: Arc<EnrollmentStore>,
}

#[derive(Serialize)]
struct ErrorBody {
    error: String,
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
    let store = Arc::new(EnrollmentStore::open(relay_state_path_from_env())?);

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
            // Raw token is intentionally emitted only to this explicit administrative command.
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
            for device in store.list_devices() {
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
           INTENTIONS_RELAY_STATE   path to app state JSON\n\n\
         Public deployment must terminate TLS in front of the loopback listener."
    );
}

async fn serve(
    address: SocketAddr,
    enrollments: Arc<EnrollmentStore>,
) -> Result<(), Box<dyn std::error::Error>> {
    let state = AppState { enrollments };
    let app = Router::new()
        .route("/health", get(health))
        .route("/v1/device/enroll", post(enroll_device))
        .route("/v1/device/connect/{device_id}", get(connect_device))
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

async fn connect_device(
    AxumPath(device_id): AxumPath<String>,
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> Response {
    if state.enrollments.get_active_device(&device_id).is_none() {
        return relay_error_response(RelayError::DeviceUnavailable);
    }

    ws.max_message_size(intentions_relay::MAX_TEXT_FRAME_BYTES)
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

    let mut revocation_check = interval(Duration::from_secs(30));
    loop {
        tokio::select! {
            _ = revocation_check.tick() => {
                if state.enrollments.get_active_device(&device_id).is_none() {
                    let _ = socket.send(Message::Close(None)).await;
                    break;
                }
            }
            message = socket.next() => {
                match message {
                    Some(Ok(Message::Text(text))) => {
                        if ensure_text_frame_bound(text.as_str()).is_err()
                            || serde_json::from_str::<ClientFrame>(text.as_str()).is_err()
                        {
                            let _ = socket.send(Message::Close(None)).await;
                            break;
                        }
                        // Dispatch responses become meaningful when the bounded live-session router
                        // is attached. Unknown/unsolicited responses never create authority.
                    }
                    Some(Ok(Message::Ping(payload))) => {
                        if socket.send(Message::Pong(payload)).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    Some(Ok(Message::Binary(_))) | Some(Ok(Message::Pong(_))) => {}
                }
            }
        }
    }
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
        RelayError::ChallengeExpired | RelayError::ChallengeDeviceMismatch => StatusCode::UNAUTHORIZED,
        RelayError::Io(_) | RelayError::Json(_) => StatusCode::INTERNAL_SERVER_ERROR,
    };
    (status, Json(ErrorBody { error: error.to_string() })).into_response()
}
