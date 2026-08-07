use intentions_relay::{now_ms, ServerFrame};
use serde_json::Value;
use std::collections::HashMap;
use thiserror::Error;
use tokio::sync::{mpsc, oneshot, Mutex};
use tokio::time::{timeout, Duration};
use uuid::Uuid;

pub const MAX_PENDING_PER_DEVICE: usize = 8;
pub const SESSION_OUTBOUND_CAPACITY: usize = 16;

#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum DispatchFailure {
    #[error("device is offline")]
    Offline,
    #[error("device session is busy")]
    Busy,
    #[error("device session was replaced")]
    SessionReplaced,
    #[error("device request deadline expired")]
    Deadline,
    #[error("device returned an error: {0}: {1}")]
    DeviceError(String, String),
}

#[derive(Debug)]
struct PendingDispatch {
    tx: oneshot::Sender<Result<Value, DispatchFailure>>,
}

#[derive(Debug)]
struct DeviceSession {
    session_id: String,
    outbound: mpsc::Sender<ServerFrame>,
    pending: HashMap<String, PendingDispatch>,
}

#[derive(Default)]
pub struct LiveSessionRouter {
    sessions: Mutex<HashMap<String, DeviceSession>>,
}

impl LiveSessionRouter {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register the current authenticated connection for a device.
    ///
    /// A newer authenticated session atomically replaces the older one. All work owned by the old
    /// session fails; none of it is transferred to the new connection.
    pub async fn register(
        &self,
        device_id: String,
        session_id: String,
        outbound: mpsc::Sender<ServerFrame>,
    ) {
        let mut sessions = self.sessions.lock().await;
        if let Some(mut old) = sessions.remove(&device_id) {
            let _ = old.outbound.try_send(ServerFrame::SessionReplaced {
                reason: "A newer authenticated session replaced this connection".into(),
            });
            fail_pending(&mut old, DispatchFailure::SessionReplaced);
        }
        sessions.insert(
            device_id,
            DeviceSession {
                session_id,
                outbound,
                pending: HashMap::new(),
            },
        );
    }

    /// Remove a session only if it is still the current authenticated connection.
    pub async fn unregister(&self, device_id: &str, session_id: &str) {
        let mut sessions = self.sessions.lock().await;
        let is_current = sessions
            .get(device_id)
            .map(|session| session.session_id == session_id)
            .unwrap_or(false);
        if !is_current {
            return;
        }
        if let Some(mut session) = sessions.remove(device_id) {
            fail_pending(&mut session, DispatchFailure::Offline);
        }
    }

    pub async fn is_online(&self, device_id: &str) -> bool {
        self.sessions.lock().await.contains_key(device_id)
    }

    /// Route one request to the current live session and await its matching response.
    ///
    /// There is intentionally no persistent/offline queue. If no session exists, this returns
    /// [DispatchFailure::Offline] immediately. A request is owned by the exact session that
    /// accepted it and cannot survive replacement or reconnect.
    pub async fn dispatch(
        &self,
        device_id: &str,
        principal_id: String,
        request: Value,
        deadline_ms: u64,
    ) -> Result<Value, DispatchFailure> {
        let now = now_ms();
        if deadline_ms <= now {
            return Err(DispatchFailure::Deadline);
        }

        let request_id = Uuid::new_v4().to_string();
        let (rx, session_id, outbound) = {
            let mut sessions = self.sessions.lock().await;
            let session = sessions
                .get_mut(device_id)
                .ok_or(DispatchFailure::Offline)?;
            if session.pending.len() >= MAX_PENDING_PER_DEVICE {
                return Err(DispatchFailure::Busy);
            }
            let (tx, rx) = oneshot::channel();
            session
                .pending
                .insert(request_id.clone(), PendingDispatch { tx });
            (rx, session.session_id.clone(), session.outbound.clone())
        };

        let frame = ServerFrame::DispatchRequest {
            request_id: request_id.clone(),
            principal_id,
            deadline_ms,
            request,
        };
        if outbound.try_send(frame).is_err() {
            self.remove_pending(device_id, &session_id, &request_id).await;
            return Err(DispatchFailure::Offline);
        }

        let remaining = deadline_ms.saturating_sub(now_ms());
        if remaining == 0 {
            self.remove_pending(device_id, &session_id, &request_id).await;
            return Err(DispatchFailure::Deadline);
        }

        match timeout(Duration::from_millis(remaining), rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(DispatchFailure::Offline),
            Err(_) => {
                self.remove_pending(device_id, &session_id, &request_id).await;
                Err(DispatchFailure::Deadline)
            }
        }
    }

    /// Complete a response only when device, session, and request all match the current owner.
    /// Returns false for stale/unsolicited responses.
    pub async fn complete_response(
        &self,
        device_id: &str,
        session_id: &str,
        request_id: &str,
        result: Result<Value, DispatchFailure>,
    ) -> bool {
        let pending = {
            let mut sessions = self.sessions.lock().await;
            let Some(session) = sessions.get_mut(device_id) else {
                return false;
            };
            if session.session_id != session_id {
                return false;
            }
            session.pending.remove(request_id)
        };
        let Some(pending) = pending else {
            return false;
        };
        pending.tx.send(result).is_ok()
    }

    async fn remove_pending(&self, device_id: &str, session_id: &str, request_id: &str) {
        let mut sessions = self.sessions.lock().await;
        if let Some(session) = sessions.get_mut(device_id) {
            if session.session_id == session_id {
                session.pending.remove(request_id);
            }
        }
    }
}

fn fail_pending(session: &mut DeviceSession, failure: DispatchFailure) {
    for (_, pending) in session.pending.drain() {
        let _ = pending.tx.send(Err(failure.clone()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::task::yield_now;

    #[tokio::test]
    async fn offline_device_fails_without_queueing() {
        let router = LiveSessionRouter::new();
        let result = router
            .dispatch("missing", "test:user".into(), Value::Null, now_ms() + 5_000)
            .await;
        assert_eq!(result.unwrap_err(), DispatchFailure::Offline);
    }

    #[tokio::test]
    async fn current_session_can_complete_matching_request() {
        let router = std::sync::Arc::new(LiveSessionRouter::new());
        let (tx, mut rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
        router
            .register("device".into(), "session-a".into(), tx)
            .await;

        let task_router = router.clone();
        let task = tokio::spawn(async move {
            task_router
                .dispatch(
                    "device",
                    "test:user".into(),
                    serde_json::json!({"method":"ping"}),
                    now_ms() + 5_000,
                )
                .await
        });
        let frame = rx.recv().await.expect("dispatch frame");
        let request_id = match frame {
            ServerFrame::DispatchRequest { request_id, .. } => request_id,
            other => panic!("unexpected frame: {other:?}"),
        };
        assert!(
            router
                .complete_response(
                    "device",
                    "session-a",
                    &request_id,
                    Ok(serde_json::json!({"ok":true})),
                )
                .await
        );
        assert_eq!(task.await.unwrap().unwrap(), serde_json::json!({"ok":true}));
    }

    #[tokio::test]
    async fn replacing_session_fails_old_pending_work() {
        let router = std::sync::Arc::new(LiveSessionRouter::new());
        let (old_tx, mut old_rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
        router
            .register("device".into(), "session-old".into(), old_tx)
            .await;

        let task_router = router.clone();
        let pending = tokio::spawn(async move {
            task_router
                .dispatch("device", "test:user".into(), Value::Null, now_ms() + 5_000)
                .await
        });
        let frame = old_rx.recv().await.expect("old request");
        let old_request_id = match frame {
            ServerFrame::DispatchRequest { request_id, .. } => request_id,
            other => panic!("unexpected frame: {other:?}"),
        };

        let (new_tx, _new_rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
        router
            .register("device".into(), "session-new".into(), new_tx)
            .await;
        assert_eq!(pending.await.unwrap().unwrap_err(), DispatchFailure::SessionReplaced);

        assert!(!router
            .complete_response(
                "device",
                "session-old",
                &old_request_id,
                Ok(Value::Null),
            )
            .await);
    }

    #[tokio::test]
    async fn unregister_fails_pending_work_instead_of_replaying_after_reconnect() {
        let router = std::sync::Arc::new(LiveSessionRouter::new());
        let (tx, mut rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
        router.register("device".into(), "s1".into(), tx).await;

        let task_router = router.clone();
        let pending = tokio::spawn(async move {
            task_router
                .dispatch("device", "test:user".into(), Value::Null, now_ms() + 5_000)
                .await
        });
        rx.recv().await.expect("request frame");
        router.unregister("device", "s1").await;
        assert_eq!(pending.await.unwrap().unwrap_err(), DispatchFailure::Offline);

        let (new_tx, _new_rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
        router.register("device".into(), "s2".into(), new_tx).await;
        assert!(router.is_online("device").await);
    }

    #[tokio::test]
    async fn expired_deadline_never_enters_session_pending_map() {
        let router = LiveSessionRouter::new();
        let (tx, mut rx) = mpsc::channel(SESSION_OUTBOUND_CAPACITY);
        router.register("device".into(), "s1".into(), tx).await;
        let result = router
            .dispatch("device", "test:user".into(), Value::Null, now_ms())
            .await;
        assert_eq!(result.unwrap_err(), DispatchFailure::Deadline);
        yield_now().await;
        assert!(rx.try_recv().is_err());
    }
}
