use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use p256::{
    ecdsa::{signature::Verifier, Signature, VerifyingKey},
    pkcs8::DecodePublicKey,
    PublicKey,
};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};
use thiserror::Error;
use uuid::Uuid;

pub const MAX_TEXT_FRAME_BYTES: usize = 256 * 1024;
pub const MAX_DEVICE_LABEL_CHARS: usize = 128;
pub const DEFAULT_ENROLLMENT_TTL_MS: u64 = 10 * 60 * 1000;
pub const DEFAULT_AUTH_CHALLENGE_TTL_MS: u64 = 15 * 1000;

#[derive(Debug, Error)]
pub enum RelayError {
    #[error("state I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("state JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid base64")]
    Base64(#[from] base64::DecodeError),
    #[error("invalid public key")]
    InvalidPublicKey,
    #[error("invalid ECDSA signature")]
    InvalidSignature,
    #[error("enrollment token is missing, expired, or already used")]
    InvalidEnrollmentToken,
    #[error("device is missing or revoked")]
    DeviceUnavailable,
    #[error("authentication challenge expired")]
    ChallengeExpired,
    #[error("authentication challenge does not belong to this device")]
    ChallengeDeviceMismatch,
    #[error("device label is invalid")]
    InvalidDeviceLabel,
    #[error("invalid request: {0}")]
    InvalidRequest(&'static str),
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0_u8; N];
    OsRng.fill_bytes(&mut bytes);
    bytes
}

fn token_digest(token: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(token.as_bytes()))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingEnrollment {
    pub account_id: String,
    pub expires_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceEnrollment {
    pub device_id: String,
    pub account_id: String,
    pub device_label: String,
    pub public_key_spki_b64: String,
    pub created_at_ms: u64,
    pub revoked_at_ms: Option<u64>,
}

impl DeviceEnrollment {
    pub fn active(&self) -> bool {
        self.revoked_at_ms.is_none()
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct PersistedRelayState {
    #[serde(default)]
    pending_enrollments: HashMap<String, PendingEnrollment>,
    #[serde(default)]
    devices: HashMap<String, DeviceEnrollment>,
}

/// File-backed relay enrollment state.
///
/// Only SHA-256 enrollment-token digests are persisted. Raw one-time tokens are returned once to
/// the operator/user and are never written to the relay state file.
pub struct EnrollmentStore {
    path: PathBuf,
    inner: Mutex<PersistedRelayState>,
}

impl EnrollmentStore {
    pub fn open(path: impl Into<PathBuf>) -> Result<Self, RelayError> {
        let path = path.into();
        let state = if path.exists() {
            let bytes = fs::read(&path)?;
            if bytes.is_empty() {
                PersistedRelayState::default()
            } else {
                serde_json::from_slice(&bytes)?
            }
        } else {
            PersistedRelayState::default()
        };
        Ok(Self {
            path,
            inner: Mutex::new(state),
        })
    }

    pub fn issue_enrollment_token(
        &self,
        account_id: impl Into<String>,
        ttl_ms: u64,
        clock_ms: u64,
    ) -> Result<String, RelayError> {
        let account_id = account_id.into();
        if account_id.trim().is_empty() {
            return Err(RelayError::InvalidRequest("account_id must not be blank"));
        }
        let token = URL_SAFE_NO_PAD.encode(random_bytes::<32>());
        let digest = token_digest(&token);

        let mut guard = self.inner.lock().expect("relay state mutex poisoned");
        let mut next = guard.clone();
        next.pending_enrollments
            .retain(|_, pending| pending.expires_at_ms > clock_ms);
        next.pending_enrollments.insert(
            digest,
            PendingEnrollment {
                account_id,
                expires_at_ms: clock_ms.saturating_add(ttl_ms),
            },
        );
        self.persist(&next)?;
        *guard = next;
        Ok(token)
    }

    pub fn redeem(&self, request: &EnrollmentRequest, clock_ms: u64) -> Result<DeviceEnrollment, RelayError> {
        validate_enrollment_request(request)?;
        verify_enrollment_proof(request)?;

        let digest = token_digest(&request.token);
        let mut guard = self.inner.lock().expect("relay state mutex poisoned");
        let mut next = guard.clone();
        next.pending_enrollments
            .retain(|_, pending| pending.expires_at_ms > clock_ms);
        let pending = next
            .pending_enrollments
            .remove(&digest)
            .ok_or(RelayError::InvalidEnrollmentToken)?;
        if pending.expires_at_ms <= clock_ms {
            return Err(RelayError::InvalidEnrollmentToken);
        }

        let device = DeviceEnrollment {
            device_id: Uuid::new_v4().to_string(),
            account_id: pending.account_id,
            device_label: request.device_label.trim().to_owned(),
            public_key_spki_b64: request.public_key_spki_b64.clone(),
            created_at_ms: clock_ms,
            revoked_at_ms: None,
        };
        next.devices.insert(device.device_id.clone(), device.clone());
        self.persist(&next)?;
        *guard = next;
        Ok(device)
    }

    pub fn get_active_device(&self, device_id: &str) -> Option<DeviceEnrollment> {
        let guard = self.inner.lock().expect("relay state mutex poisoned");
        guard.devices.get(device_id).filter(|device| device.active()).cloned()
    }

    pub fn revoke(&self, device_id: &str, clock_ms: u64) -> Result<bool, RelayError> {
        let mut guard = self.inner.lock().expect("relay state mutex poisoned");
        let mut next = guard.clone();
        let Some(device) = next.devices.get_mut(device_id) else {
            return Ok(false);
        };
        device.revoked_at_ms = Some(clock_ms);
        self.persist(&next)?;
        *guard = next;
        Ok(true)
    }

    pub fn list_devices(&self) -> Vec<DeviceEnrollment> {
        let guard = self.inner.lock().expect("relay state mutex poisoned");
        let mut devices: Vec<_> = guard.devices.values().cloned().collect();
        devices.sort_by(|a, b| a.created_at_ms.cmp(&b.created_at_ms).then(a.device_id.cmp(&b.device_id)));
        devices
    }

    fn persist(&self, state: &PersistedRelayState) -> Result<(), RelayError> {
        if let Some(parent) = self.path.parent().filter(|path| !path.as_os_str().is_empty()) {
            fs::create_dir_all(parent)?;
        }
        let temp = self.path.with_extension(format!("tmp-{}", Uuid::new_v4()));
        let bytes = serde_json::to_vec_pretty(state)?;
        fs::write(&temp, bytes)?;
        fs::rename(&temp, &self.path)?;
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnrollmentRequest {
    pub token: String,
    pub device_label: String,
    pub public_key_spki_b64: String,
    pub client_nonce_b64: String,
    pub signature_der_b64: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnrollmentResponse {
    pub device_id: String,
    pub account_id: String,
}

pub fn enrollment_proof_message(
    token: &str,
    public_key_spki_b64: &str,
    client_nonce_b64: &str,
) -> Vec<u8> {
    format!(
        "llm-intentions-enroll-v1\n{token}\n{public_key_spki_b64}\n{client_nonce_b64}"
    )
    .into_bytes()
}

fn validate_enrollment_request(request: &EnrollmentRequest) -> Result<(), RelayError> {
    let label_len = request.device_label.chars().count();
    if request.device_label.trim().is_empty() || label_len > MAX_DEVICE_LABEL_CHARS {
        return Err(RelayError::InvalidDeviceLabel);
    }
    if request.token.len() > 256
        || request.public_key_spki_b64.len() > 4096
        || request.client_nonce_b64.len() > 256
        || request.signature_der_b64.len() > 1024
    {
        return Err(RelayError::InvalidRequest("enrollment field exceeds bound"));
    }
    Ok(())
}

fn parse_verifying_key(public_key_spki_b64: &str) -> Result<VerifyingKey, RelayError> {
    let der = URL_SAFE_NO_PAD.decode(public_key_spki_b64)?;
    let public_key = PublicKey::from_public_key_der(&der).map_err(|_| RelayError::InvalidPublicKey)?;
    Ok(VerifyingKey::from(public_key))
}

fn verify_der_signature(
    public_key_spki_b64: &str,
    message: &[u8],
    signature_der_b64: &str,
) -> Result<(), RelayError> {
    let key = parse_verifying_key(public_key_spki_b64)?;
    let signature_bytes = URL_SAFE_NO_PAD.decode(signature_der_b64)?;
    let signature = Signature::from_der(&signature_bytes).map_err(|_| RelayError::InvalidSignature)?;
    key.verify(message, &signature)
        .map_err(|_| RelayError::InvalidSignature)
}

pub fn verify_enrollment_proof(request: &EnrollmentRequest) -> Result<(), RelayError> {
    verify_der_signature(
        &request.public_key_spki_b64,
        &enrollment_proof_message(
            &request.token,
            &request.public_key_spki_b64,
            &request.client_nonce_b64,
        ),
        &request.signature_der_b64,
    )
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthChallenge {
    pub device_id: String,
    pub session_id: String,
    pub nonce_b64: String,
    pub expires_at_ms: u64,
}

impl AuthChallenge {
    pub fn issue(device_id: impl Into<String>, clock_ms: u64, ttl_ms: u64) -> Self {
        Self {
            device_id: device_id.into(),
            session_id: Uuid::new_v4().to_string(),
            nonce_b64: URL_SAFE_NO_PAD.encode(random_bytes::<32>()),
            expires_at_ms: clock_ms.saturating_add(ttl_ms),
        }
    }

    pub fn signing_message(&self) -> Vec<u8> {
        format!(
            "llm-intentions-relay-auth-v1\n{}\n{}\n{}\n{}",
            self.device_id, self.session_id, self.nonce_b64, self.expires_at_ms
        )
        .into_bytes()
    }
}

pub fn verify_device_auth(
    device: &DeviceEnrollment,
    challenge: &AuthChallenge,
    signature_der_b64: &str,
    clock_ms: u64,
) -> Result<(), RelayError> {
    if !device.active() {
        return Err(RelayError::DeviceUnavailable);
    }
    if challenge.device_id != device.device_id {
        return Err(RelayError::ChallengeDeviceMismatch);
    }
    if challenge.expires_at_ms <= clock_ms {
        return Err(RelayError::ChallengeExpired);
    }
    verify_der_signature(
        &device.public_key_spki_b64,
        &challenge.signing_message(),
        signature_der_b64,
    )
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerFrame {
    AuthChallenge { challenge: AuthChallenge },
    Authenticated { device_id: String, session_id: String },
    DispatchRequest {
        request_id: String,
        principal_id: String,
        deadline_ms: u64,
        request: serde_json::Value,
    },
    SessionReplaced { reason: String },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientFrame {
    AuthResponse { signature_der_b64: String },
    DispatchResponse {
        request_id: String,
        response: serde_json::Value,
    },
    DispatchError {
        request_id: String,
        code: String,
        message: String,
    },
}

pub fn ensure_text_frame_bound(text: &str) -> Result<(), RelayError> {
    if text.len() > MAX_TEXT_FRAME_BYTES {
        Err(RelayError::InvalidRequest("WebSocket text frame exceeds maximum size"))
    } else {
        Ok(())
    }
}

pub fn relay_state_path_from_env() -> PathBuf {
    std::env::var_os("INTENTIONS_RELAY_STATE")
        .map(PathBuf::from)
        .unwrap_or_else(|| Path::new("intentions-relay-state.json").to_path_buf())
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::{
        ecdsa::{signature::Signer, SigningKey},
        pkcs8::EncodePublicKey,
    };
    use tempfile::tempdir;

    fn signing_key() -> SigningKey {
        SigningKey::from_slice(&[7_u8; 32]).expect("test signing key")
    }

    fn spki_b64(signing_key: &SigningKey) -> String {
        let point = signing_key.verifying_key().to_encoded_point(false);
        let public_key = PublicKey::from_sec1_bytes(point.as_bytes()).expect("public key");
        URL_SAFE_NO_PAD.encode(
            public_key
                .to_public_key_der()
                .expect("SPKI")
                .as_bytes(),
        )
    }

    fn sign_b64(signing_key: &SigningKey, message: &[u8]) -> String {
        let signature: Signature = signing_key.sign(message);
        URL_SAFE_NO_PAD.encode(signature.to_der().as_bytes())
    }

    #[test]
    fn enrollment_token_is_one_use_and_persists_device() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("state.json");
        let store = EnrollmentStore::open(&path).unwrap();
        let token = store
            .issue_enrollment_token("account-1", 60_000, 1_000)
            .unwrap();
        let key = signing_key();
        let public_key_spki_b64 = spki_b64(&key);
        let client_nonce_b64 = URL_SAFE_NO_PAD.encode([9_u8; 32]);
        let signature_der_b64 = sign_b64(
            &key,
            &enrollment_proof_message(&token, &public_key_spki_b64, &client_nonce_b64),
        );
        let request = EnrollmentRequest {
            token: token.clone(),
            device_label: "Adam phone".into(),
            public_key_spki_b64,
            client_nonce_b64,
            signature_der_b64,
        };

        let device = store.redeem(&request, 2_000).unwrap();
        assert_eq!(device.account_id, "account-1");
        assert!(store.redeem(&request, 2_001).is_err());

        let reloaded = EnrollmentStore::open(&path).unwrap();
        assert_eq!(
            reloaded.get_active_device(&device.device_id).unwrap().device_label,
            "Adam phone"
        );
    }

    #[test]
    fn enrollment_requires_private_key_possession() {
        let dir = tempdir().unwrap();
        let store = EnrollmentStore::open(dir.path().join("state.json")).unwrap();
        let token = store.issue_enrollment_token("a", 60_000, 1_000).unwrap();
        let key = signing_key();
        let other = SigningKey::from_slice(&[8_u8; 32]).unwrap();
        let public_key_spki_b64 = spki_b64(&key);
        let client_nonce_b64 = URL_SAFE_NO_PAD.encode([1_u8; 32]);
        let request = EnrollmentRequest {
            token: token.clone(),
            device_label: "phone".into(),
            public_key_spki_b64: public_key_spki_b64.clone(),
            client_nonce_b64: client_nonce_b64.clone(),
            signature_der_b64: sign_b64(
                &other,
                &enrollment_proof_message(&token, &public_key_spki_b64, &client_nonce_b64),
            ),
        };
        assert!(matches!(store.redeem(&request, 2_000), Err(RelayError::InvalidSignature)));
    }

    #[test]
    fn challenge_binds_device_session_nonce_and_expiry() {
        let key = signing_key();
        let device = DeviceEnrollment {
            device_id: "device-1".into(),
            account_id: "account".into(),
            device_label: "phone".into(),
            public_key_spki_b64: spki_b64(&key),
            created_at_ms: 1,
            revoked_at_ms: None,
        };
        let challenge = AuthChallenge::issue("device-1", 1_000, 5_000);
        let signature = sign_b64(&key, &challenge.signing_message());
        verify_device_auth(&device, &challenge, &signature, 2_000).unwrap();
        assert!(matches!(
            verify_device_auth(&device, &challenge, &signature, 6_000),
            Err(RelayError::ChallengeExpired)
        ));

        let mut transplanted = challenge.clone();
        transplanted.device_id = "device-2".into();
        assert!(matches!(
            verify_device_auth(&device, &transplanted, &signature, 2_000),
            Err(RelayError::ChallengeDeviceMismatch)
        ));
    }

    #[test]
    fn revocation_disables_authentication() {
        let dir = tempdir().unwrap();
        let store = EnrollmentStore::open(dir.path().join("state.json")).unwrap();
        let token = store.issue_enrollment_token("a", 60_000, 1_000).unwrap();
        let key = signing_key();
        let public_key_spki_b64 = spki_b64(&key);
        let client_nonce_b64 = URL_SAFE_NO_PAD.encode([2_u8; 32]);
        let request = EnrollmentRequest {
            token: token.clone(),
            device_label: "phone".into(),
            public_key_spki_b64: public_key_spki_b64.clone(),
            client_nonce_b64: client_nonce_b64.clone(),
            signature_der_b64: sign_b64(
                &key,
                &enrollment_proof_message(&token, &public_key_spki_b64, &client_nonce_b64),
            ),
        };
        let device = store.redeem(&request, 2_000).unwrap();
        assert!(store.revoke(&device.device_id, 3_000).unwrap());
        assert!(store.get_active_device(&device.device_id).is_none());
    }

    #[test]
    fn text_frames_are_bounded() {
        assert!(ensure_text_frame_bound("ok").is_ok());
        let too_large = "x".repeat(MAX_TEXT_FRAME_BYTES + 1);
        assert!(ensure_text_frame_bound(&too_large).is_err());
    }
}
