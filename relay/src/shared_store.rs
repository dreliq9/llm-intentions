use crate::{
    verify_enrollment_proof, DeviceEnrollment, EnrollmentRequest, PendingEnrollment, RelayError,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use fs2::FileExt;
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{self, File, OpenOptions},
    path::{Path, PathBuf},
    sync::Mutex,
};
use uuid::Uuid;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct DiskState {
    #[serde(default)]
    pending_enrollments: HashMap<String, PendingEnrollment>,
    #[serde(default)]
    devices: HashMap<String, DeviceEnrollment>,
}

/// Cross-process coherent relay state.
///
/// Administrative CLI commands and the long-running relay server are separate processes. Every
/// operation therefore reloads the JSON state under an OS file lock instead of trusting a stale
/// process-local cache. A separate lock file keeps the atomic temp-file rename compatible with the
/// held lock.
pub struct SharedEnrollmentStore {
    path: PathBuf,
    lock_path: PathBuf,
    process_lock: Mutex<()>,
}

impl SharedEnrollmentStore {
    pub fn open(path: impl Into<PathBuf>) -> Result<Self, RelayError> {
        let path = path.into();
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            fs::create_dir_all(parent)?;
        }
        let lock_path = PathBuf::from(format!("{}.lock", path.display()));
        let store = Self {
            path,
            lock_path,
            process_lock: Mutex::new(()),
        };
        // Validate any existing file immediately.
        store.with_locked(false, |state| Ok(state.clone()))?;
        Ok(store)
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
        let mut raw = [0_u8; 32];
        OsRng.fill_bytes(&mut raw);
        let token = URL_SAFE_NO_PAD.encode(raw);
        let digest = token_digest(&token);

        self.with_locked(true, |state| {
            cleanup_pending(state, clock_ms);
            state.pending_enrollments.insert(
                digest,
                PendingEnrollment {
                    account_id,
                    expires_at_ms: clock_ms.saturating_add(ttl_ms),
                },
            );
            Ok(())
        })?;
        Ok(token)
    }

    pub fn redeem(
        &self,
        request: &EnrollmentRequest,
        clock_ms: u64,
    ) -> Result<DeviceEnrollment, RelayError> {
        let digest = token_digest(&request.token);
        self.with_locked(true, |state| {
            cleanup_pending(state, clock_ms);
            // Check the high-entropy one-time token before doing public-key work. Invalid public
            // requests therefore cannot force ECDSA verification without knowing a live token.
            let pending = state
                .pending_enrollments
                .get(&digest)
                .cloned()
                .ok_or(RelayError::InvalidEnrollmentToken)?;
            if pending.expires_at_ms <= clock_ms {
                state.pending_enrollments.remove(&digest);
                return Err(RelayError::InvalidEnrollmentToken);
            }

            verify_enrollment_proof(request)?;
            state.pending_enrollments.remove(&digest);

            let device = DeviceEnrollment {
                device_id: Uuid::new_v4().to_string(),
                account_id: pending.account_id,
                device_label: request.device_label.trim().to_owned(),
                public_key_spki_b64: request.public_key_spki_b64.clone(),
                created_at_ms: clock_ms,
                revoked_at_ms: None,
            };
            state.devices.insert(device.device_id.clone(), device.clone());
            Ok(device)
        })
    }

    /// Fail closed on state I/O/corruption: an unreadable store never authenticates a device.
    pub fn get_active_device(&self, device_id: &str) -> Option<DeviceEnrollment> {
        self.with_locked(false, |state| {
            Ok(state
                .devices
                .get(device_id)
                .filter(|device| device.active())
                .cloned())
        })
        .ok()
        .flatten()
    }

    pub fn revoke(&self, device_id: &str, clock_ms: u64) -> Result<bool, RelayError> {
        self.with_locked(true, |state| {
            let Some(device) = state.devices.get_mut(device_id) else {
                return Ok(false);
            };
            device.revoked_at_ms = Some(clock_ms);
            Ok(true)
        })
    }

    pub fn list_devices(&self) -> Result<Vec<DeviceEnrollment>, RelayError> {
        self.with_locked(false, |state| {
            let mut devices: Vec<_> = state.devices.values().cloned().collect();
            devices.sort_by(|a, b| {
                a.created_at_ms
                    .cmp(&b.created_at_ms)
                    .then(a.device_id.cmp(&b.device_id))
            });
            Ok(devices)
        })
    }

    fn with_locked<T>(
        &self,
        persist: bool,
        operation: impl FnOnce(&mut DiskState) -> Result<T, RelayError>,
    ) -> Result<T, RelayError> {
        let _guard = self
            .process_lock
            .lock()
            .expect("relay state process mutex poisoned");
        let lock_file = open_lock_file(&self.lock_path)?;
        lock_file.lock_exclusive()?;

        let result = (|| {
            let mut state = load_state(&self.path)?;
            let value = operation(&mut state)?;
            if persist {
                persist_state(&self.path, &state)?;
            }
            Ok(value)
        })();

        // Unlock errors matter only if the operation otherwise succeeded; the process-local mutex
        // remains held until after this call, so a same-process operation cannot race the unlock.
        let unlock_result = FileExt::unlock(&lock_file).map_err(RelayError::Io);
        match (result, unlock_result) {
            (Err(error), _) => Err(error),
            (Ok(_), Err(error)) => Err(error),
            (Ok(value), Ok(())) => Ok(value),
        }
    }
}

fn token_digest(token: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(token.as_bytes()))
}

fn cleanup_pending(state: &mut DiskState, clock_ms: u64) {
    state
        .pending_enrollments
        .retain(|_, pending| pending.expires_at_ms > clock_ms);
}

fn open_lock_file(path: &Path) -> Result<File, RelayError> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }
    Ok(OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .open(path)?)
}

fn load_state(path: &Path) -> Result<DiskState, RelayError> {
    if !path.exists() {
        return Ok(DiskState::default());
    }
    let bytes = fs::read(path)?;
    if bytes.is_empty() {
        Ok(DiskState::default())
    } else {
        Ok(serde_json::from_slice(&bytes)?)
    }
}

fn persist_state(path: &Path, state: &DiskState) -> Result<(), RelayError> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        fs::create_dir_all(parent)?;
    }
    let temp = path.with_extension(format!("tmp-{}", Uuid::new_v4()));
    fs::write(&temp, serde_json::to_vec_pretty(state)?)?;
    fs::rename(&temp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::enrollment_proof_message;
    use p256::{
        ecdsa::{signature::Signer, Signature, SigningKey},
        pkcs8::EncodePublicKey,
        PublicKey,
    };
    use tempfile::tempdir;

    fn key() -> SigningKey {
        SigningKey::from_slice(&[11_u8; 32]).unwrap()
    }

    fn spki_b64(key: &SigningKey) -> String {
        let point = key.verifying_key().to_sec1_point(false);
        let public_key = PublicKey::from_sec1_bytes(point.as_bytes()).unwrap();
        URL_SAFE_NO_PAD.encode(public_key.to_public_key_der().unwrap().as_bytes())
    }

    fn request(token: String) -> EnrollmentRequest {
        let key = key();
        let public_key_spki_b64 = spki_b64(&key);
        let client_nonce_b64 = URL_SAFE_NO_PAD.encode([3_u8; 32]);
        let message = enrollment_proof_message(&token, &public_key_spki_b64, &client_nonce_b64);
        let signature: Signature = key.sign(&message);
        EnrollmentRequest {
            token,
            device_label: "coherent test phone".into(),
            public_key_spki_b64,
            client_nonce_b64,
            signature_der_b64: URL_SAFE_NO_PAD.encode(signature.to_der().as_bytes()),
        }
    }

    #[test]
    fn separate_store_instances_see_issue_redeem_and_revoke() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("state.json");
        let cli = SharedEnrollmentStore::open(&path).unwrap();
        let server = SharedEnrollmentStore::open(&path).unwrap();

        let token = cli.issue_enrollment_token("account", 60_000, 1_000).unwrap();
        let device = server.redeem(&request(token), 2_000).unwrap();
        assert!(cli.get_active_device(&device.device_id).is_some());

        assert!(cli.revoke(&device.device_id, 3_000).unwrap());
        assert!(server.get_active_device(&device.device_id).is_none());
    }

    #[test]
    fn expired_token_is_rejected_across_process_views() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("state.json");
        let cli = SharedEnrollmentStore::open(&path).unwrap();
        let server = SharedEnrollmentStore::open(&path).unwrap();
        let token = cli.issue_enrollment_token("account", 1_000, 1_000).unwrap();
        assert!(matches!(
            server.redeem(&request(token), 2_000),
            Err(RelayError::InvalidEnrollmentToken)
        ));
    }
}
