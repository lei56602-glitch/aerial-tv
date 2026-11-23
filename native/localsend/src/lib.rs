use jni::objects::{JClass, JString};
use jni::sys::{jint, jboolean, jstring};
use jni::JNIEnv;
use localsend::discovery::{self, ChannelStatus, DeviceChannel, DeviceIdentity, DiscoveryConfig, DiscoveryEvent};
use localsend::http::server::{self, ServerConfigV2, TlsConfig};
use localsend::http::server::common::save::FileUploadTarget;
use localsend::http::server::v2::{PrepareUploadDecisionV2, ServerEventV2};
use localsend::http::state::ClientInfo;
use localsend::model::transfer::FileDto;
use localsend::model::discovery::{DeviceType, ProtocolType, PROTOCOL_VERSION_V2};
use localsend::multicast::{DEFAULT_MULTICAST_GROUP, DEFAULT_MULTICAST_GROUP_V6, DEFAULT_PORT};
use localsend::util::interface::InterfaceFilter;
use serde_json::{json, Value};
use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};

enum DiscoveryCommand {
    Announce,
    SetAnswering(bool),
    Probe { host: String, port: u16, https: bool },
}

struct DiscoverySession {
    stop: oneshot::Sender<()>,
    stopped: Arc<AtomicBool>,
    snapshot: Arc<Mutex<String>>,
    commands: mpsc::UnboundedSender<DiscoveryCommand>,
}

struct HttpServerSession {
    stop: Option<oneshot::Sender<()>>,
    stopping: Arc<AtomicBool>,
    events: Arc<Mutex<VecDeque<Value>>>,
    pending_decisions: Arc<Mutex<HashMap<String, oneshot::Sender<PrepareUploadDecisionV2>>>>,
    pending_uploads: Arc<Mutex<HashMap<(String, String), oneshot::Sender<FileUploadTarget>>>>,
    port: u16,
}

static HTTP_SERVERS: OnceLock<Mutex<HashMap<String, HttpServerSession>>> = OnceLock::new();

fn http_servers() -> &'static Mutex<HashMap<String, HttpServerSession>> {
    HTTP_SERVERS.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<String, DiscoverySession>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<String, DiscoverySession>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn java_string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|error| error.to_string())
}

fn return_json(env: &mut JNIEnv, value: Value) -> jstring {
    match env.new_string(value.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn device_snapshot(devices: Vec<discovery::StatefulDevice>) -> String {
    let values: Vec<Value> = devices
        .into_iter()
        .map(|state| {
            let device = state.device;
            let channels: Vec<Value> = state
                .channels
                .into_iter()
                .map(|(channel, status)| match channel {
                    DeviceChannel::Http(http) => json!({
                        "type": "http",
                        "host": http.host,
                        "port": http.port,
                        "protocol": http.protocol.as_str(),
                        "status": match status {
                            ChannelStatus::Available => "available",
                            ChannelStatus::NotReachable => "notReachable",
                        }
                    }),
                })
                .collect();

            let logs: Vec<Value> = state
                .logs
                .into_iter()
                .map(|log| json!({
                    "timestamp": log.timestamp.duration_since(std::time::UNIX_EPOCH)
                        .unwrap_or_default().as_secs(),
                    "kind": format!("{:?}", log.kind).to_lowercase(),
                }))
                .collect();

            json!({
                "alias": device.alias,
                "version": device.version,
                "deviceModel": device.device_model,
                "deviceType": device.device_type.map(|kind| format!("{:?}", kind).to_lowercase()),
                "fingerprint": device.fingerprint,
                "download": device.download,
                "channels": channels,
                "logs": logs
            })
        })
        .collect();

    json!({ "devices": values }).to_string()
}

/// Creates the same RSA/TLS device identity used by LocalSend.
/// Private material must remain in app-private storage and must never be logged.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_generateSecurityContext(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = localsend::crypto::cert::generate_self_signed()
        .map(|identity| {
            json!({
                "privateKey": identity.private_key_pem,
                "publicKey": identity.public_key_pem,
                "certificate": identity.certificate_pem,
                "fingerprint": identity.fingerprint,
            })
        })
        .unwrap_or_else(|error| json!({ "error": error.to_string() }));

    return_json(&mut env, result)
}

/// Starts LocalSend v2 multicast discovery on a dedicated Tokio runtime thread.
/// Devices are exposed as a JSON snapshot through getDiscoveredDevices().
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_startDiscovery(
    mut env: JNIEnv,
    _class: JClass,
    alias: JString,
    certificate: JString,
    private_key: JString,
    fingerprint: JString,
) -> jstring {
    let inputs = (|| -> Result<(String, String, String, String), String> {
        Ok((
            java_string(&mut env, alias)?,
            java_string(&mut env, certificate)?,
            java_string(&mut env, private_key)?,
            java_string(&mut env, fingerprint)?,
        ))
    })();

    let (alias, certificate, private_key, fingerprint) = match inputs {
        Ok(values) => values,
        Err(error) => return return_json(&mut env, json!({ "error": error })),
    };

    let session_id = NEXT_SESSION.fetch_add(1, Ordering::Relaxed).to_string();
    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    let stopped = Arc::new(AtomicBool::new(false));
    let snapshot = Arc::new(Mutex::new(json!({ "devices": [] }).to_string()));
    let (command_tx, mut command_rx) = mpsc::unbounded_channel::<DiscoveryCommand>();

    {
        let mut all = sessions().lock().unwrap();
        all.insert(
            session_id.clone(),
            DiscoverySession {
                stop: stop_tx,
                stopped: stopped.clone(),
                snapshot: snapshot.clone(),
                commands: command_tx,
            },
        );
    }

    std::thread::Builder::new()
        .name("aerial-localsend-discovery".to_string())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    *snapshot.lock().unwrap() = json!({ "error": error.to_string() }).to_string();
                    stopped.store(true, Ordering::Release);
                    return;
                }
            };

            runtime.block_on(async move {
                let (event_tx, mut event_rx) = mpsc::channel::<DiscoveryEvent>(64);
                let config = DiscoveryConfig {
                    group: DEFAULT_MULTICAST_GROUP,
                    group_v6: Some(DEFAULT_MULTICAST_GROUP_V6),
                    port: DEFAULT_PORT,
                    interface_filter: InterfaceFilter::default(),
                    device: localsend::multicast::MulticastDevice {
                        alias,
                        version: PROTOCOL_VERSION_V2.to_string(),
                        device_model: Some("Android TV".to_string()),
                        device_type: Some(DeviceType::Desktop),
                        fingerprint,
                        port: DEFAULT_PORT,
                        protocol: ProtocolType::Https,
                        download: false,
                    },
                    identity: DeviceIdentity {
                        cert_pem: certificate,
                        private_key_pem: private_key,
                    },
                    timeout: Duration::from_millis(700),
                    event_tx: Some(event_tx),
                };

                let handle = discovery::start(config, stop_rx).await;
                // Do not invite peers to send files until Aerial's HTTP receive server is running.
                handle.set_answer_announcements(false);
                let mut refresh = tokio::time::interval(Duration::from_millis(750));

                loop {
                    tokio::select! {
                        _ = refresh.tick() => {
                            *snapshot.lock().unwrap() = device_snapshot(handle.devices());
                            if stopped.load(Ordering::Acquire) {
                                break;
                            }
                        }
                        event = event_rx.recv() => {
                            if event.is_none() {
                                break;
                            }
                            *snapshot.lock().unwrap() = device_snapshot(handle.devices());
                        }
                        command = command_rx.recv() => {
                            match command {
                                Some(DiscoveryCommand::Announce) => handle.announce().await,
                                Some(DiscoveryCommand::SetAnswering(enabled)) => handle.set_answer_announcements(enabled),
                                Some(DiscoveryCommand::Probe { host, port, https }) => {
                                    let protocol = if https { ProtocolType::Https } else { ProtocolType::Http };
                                    let _ = handle.discover(&host, port, protocol).await;
                                }
                                None => break,
                            }
                            *snapshot.lock().unwrap() = device_snapshot(handle.devices());
                        }
                    }
                }

                handle.wait_stopped().await;
                *snapshot.lock().unwrap() = device_snapshot(handle.devices());
                stopped.store(true, Ordering::Release);
            });
        });

    return_json(&mut env, json!({ "sessionId": session_id }))
}

/// Returns the most recent discovery snapshot for the supplied session.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_getDiscoveredDevices(
    mut env: JNIEnv,
    _class: JClass,
    session: JString,
) -> jstring {
    let session_id = match java_string(&mut env, session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({ "error": error })),
    };

    let value = sessions()
        .lock()
        .unwrap()
        .get(&session_id)
        .map(|entry| entry.snapshot.lock().unwrap().clone())
        .unwrap_or_else(|| json!({ "error": "Unknown discovery session" }).to_string());

    match env.new_string(value) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Stops discovery and releases its multicast sockets.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_stopDiscovery(
    mut env: JNIEnv,
    _class: JClass,
    session: JString,
) -> jstring {
    let session_id = match java_string(&mut env, session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({ "error": error })),
    };

    let entry = sessions().lock().unwrap().remove(&session_id);
    match entry {
        Some(entry) => {
            entry.stopped.store(true, Ordering::Release);
            let _ = entry.stop.send(());
            return_json(&mut env, json!({ "stopping": true }))
        }
        None => return_json(&mut env, json!({ "stopping": false, "error": "Unknown discovery session" })),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_announceDiscovery(mut env: JNIEnv, _class: JClass, session: JString) -> jstring {
    let session_id = match java_string(&mut env, session) { Ok(v) => v, Err(e) => return return_json(&mut env, json!({"error": e})) };
    let queued = sessions().lock().unwrap().get(&session_id).map(|s| s.commands.send(DiscoveryCommand::Announce).is_ok()).unwrap_or(false);
    return_json(&mut env, json!({"queued": queued}))
}

#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_probeDevice(mut env: JNIEnv, _class: JClass, session: JString, host: JString, port: jint, https: jboolean) -> jstring {
    let session_id = match java_string(&mut env, session) { Ok(v) => v, Err(e) => return return_json(&mut env, json!({"error": e})) };
    let host = match java_string(&mut env, host) { Ok(v) => v, Err(e) => return return_json(&mut env, json!({"error": e})) };
    if !(1..=65535).contains(&port) { return return_json(&mut env, json!({"error": "Port must be between 1 and 65535"})); }
    let queued = sessions().lock().unwrap().get(&session_id).map(|s| s.commands.send(DiscoveryCommand::Probe { host, port: port as u16, https: https != 0 }).is_ok()).unwrap_or(false);
    return_json(&mut env, json!({"queued": queued}))
}

#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_setAnswerAnnouncements(mut env: JNIEnv, _class: JClass, session: JString, enabled: jboolean) -> jstring {
    let session_id = match java_string(&mut env, session) { Ok(v) => v, Err(e) => return return_json(&mut env, json!({"error": e})) };
    let queued = sessions().lock().unwrap().get(&session_id).map(|s| s.commands.send(DiscoveryCommand::SetAnswering(enabled != 0)).is_ok()).unwrap_or(false);
    return_json(&mut env, json!({"queued": queued}))
}


/// Starts LocalSend's native HTTPS/mTLS receive server and its v2 event bridge.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_startHttpServer(
    mut env: JNIEnv,
    _class: JClass,
    alias: JString,
    certificate: JString,
    private_key: JString,
    fingerprint: JString,
    requested_port: jint,
) -> jstring {
    let values = (|| -> Result<(String, String, String, String), String> {
        Ok((
            java_string(&mut env, alias)?,
            java_string(&mut env, certificate)?,
            java_string(&mut env, private_key)?,
            java_string(&mut env, fingerprint)?,
        ))
    })();
    let (alias, certificate, private_key, _fingerprint) = match values {
        Ok(values) => values,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    if !(0..=65535).contains(&requested_port) {
        return return_json(&mut env, json!({"error": "Port must be between 0 and 65535"}));
    }

    let session_id = NEXT_SESSION.fetch_add(1, Ordering::Relaxed).to_string();
    let stopping = Arc::new(AtomicBool::new(false));
    let events = Arc::new(Mutex::new(VecDeque::new()));
    let pending_decisions = Arc::new(Mutex::new(HashMap::new()));
    let pending_uploads = Arc::new(Mutex::new(HashMap::new()));
    {
        http_servers().lock().unwrap().insert(
            session_id.clone(),
            HttpServerSession {
                stop: None,
                stopping: stopping.clone(),
                events: events.clone(),
                pending_decisions: pending_decisions.clone(),
                pending_uploads: pending_uploads.clone(),
                port: 0,
            },
        );
    }

    let thread_session_id = session_id.clone();
    let (startup_tx, startup_rx) = std::sync::mpsc::sync_channel::<Result<u16, String>>(1);
    let spawn_result = std::thread::Builder::new()
        .name("aerial-localsend-http".to_string())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = startup_tx.send(Err(error.to_string()));
                    http_servers().lock().unwrap().remove(&thread_session_id);
                    return;
                }
            };

            runtime.block_on(async move {
                let (event_tx, mut event_rx) = mpsc::channel::<ServerEventV2>(64);
                let (stop_tx, stop_rx) = oneshot::channel::<()>();
                let info = ClientInfo {
                    alias,
                    version: PROTOCOL_VERSION_V2.to_string(),
                    device_model: Some("Android TV".to_string()),
                    device_type: Some(DeviceType::Desktop),
                    token: format!("aerial-{}", thread_session_id),
                };
                let server = server::start_with_port(
                    requested_port as u16,
                    Some(TlsConfig { cert: certificate, private_key }),
                    info,
                    None,
                    Some(ServerConfigV2 {
                        pin: None,
                        verify_checksums: true,
                        event_tx,
                    }),
                    None,
                    stop_rx,
                ).await;

                let handle = match server {
                    Ok(handle) => handle,
                    Err(error) => {
                        let _ = startup_tx.send(Err(error.to_string()));
                        http_servers().lock().unwrap().remove(&thread_session_id);
                        return;
                    }
                };

                let bound_port = handle.port();
                if let Some(session) = http_servers().lock().unwrap().get_mut(&thread_session_id) {
                    session.stop = Some(stop_tx);
                    session.port = bound_port;
                    // A caller may have timed out while the listener was binding.
                    if session.stopping.load(Ordering::Acquire) {
                        if let Some(stop) = session.stop.take() { let _ = stop.send(()); }
                    }
                }
                let _ = startup_tx.send(Ok(bound_port));

                loop {
                    tokio::select! {
                        event = event_rx.recv() => {
                            let Some(event) = event else { break; };
                            match event {
                                ServerEventV2::Register { ip, info } => {
                                    events.lock().unwrap().push_back(json!({
                                        "type": "register", "ip": ip.to_string(),
                                        "device": serde_json::to_value(info).unwrap_or(Value::Null)
                                    }));
                                }
                                ServerEventV2::PrepareUpload {
                                    session_id, ip, info, cert_fingerprint, files, decision_tx
                                } => {
                                    pending_decisions.lock().unwrap().insert(session_id.clone(), decision_tx);
                                    events.lock().unwrap().push_back(json!({
                                        "type": "prepareUpload", "sessionId": session_id,
                                        "ip": ip.to_string(),
                                        "device": serde_json::to_value(info).unwrap_or(Value::Null),
                                        "certificateFingerprint": cert_fingerprint,
                                        "files": serde_json::to_value(files).unwrap_or(Value::Null)
                                    }));
                                }
                                ServerEventV2::FileUpload { session_id, file_id, file, target_tx } => {
                                    pending_uploads.lock().unwrap().insert((session_id.clone(), file_id.clone()), target_tx);
                                    events.lock().unwrap().push_back(json!({
                                        "type": "fileUpload", "sessionId": session_id,
                                        "fileId": file_id, "file": serde_json::to_value(file).unwrap_or(Value::Null)
                                    }));
                                }
                                ServerEventV2::SessionEnd { session_id, reason } => {
                                    pending_decisions.lock().unwrap().remove(&session_id);
                                    pending_uploads.lock().unwrap().retain(|(sid, _), _| sid != &session_id);
                                    events.lock().unwrap().push_back(json!({
                                        "type": "sessionEnd", "sessionId": session_id,
                                        "reason": format!("{:?}", reason)
                                    }));
                                }
                                ServerEventV2::PrepareUploadAborted { session_id } => {
                                    pending_decisions.lock().unwrap().remove(&session_id);
                                    events.lock().unwrap().push_back(json!({
                                        "type": "prepareUploadAborted", "sessionId": session_id
                                    }));
                                }
                                ServerEventV2::CancelReceived { ip, session_id } => {
                                    events.lock().unwrap().push_back(json!({
                                        "type": "cancelReceived", "ip": ip.to_string(),
                                        "sessionId": session_id
                                    }));
                                }
                                ServerEventV2::ListenerFailed { error } => {
                                    events.lock().unwrap().push_back(json!({
                                        "type": "listenerFailed", "error": error
                                    }));
                                }
                            }
                        }
                        _ = tokio::time::sleep(Duration::from_millis(200)) => {
                            if stopping.load(Ordering::Acquire) { break; }
                        }
                    }
                }

                handle.wait_stopped().await;
                http_servers().lock().unwrap().remove(&thread_session_id);
            });
        });

    if let Err(error) = spawn_result {
        http_servers().lock().unwrap().remove(&session_id);
        return return_json(&mut env, json!({"error": error.to_string()}));
    }

    match startup_rx.recv_timeout(Duration::from_secs(15)) {
        Ok(Ok(port)) => return_json(&mut env, json!({"sessionId": session_id, "port": port})),
        Ok(Err(error)) => return_json(&mut env, json!({"error": error})),
        Err(error) => {
            if let Some(session) = http_servers().lock().unwrap().get_mut(&session_id) {
                session.stopping.store(true, Ordering::Release);
                if let Some(stop) = session.stop.take() { let _ = stop.send(()); }
            }
            return_json(&mut env, json!({"error": format!("HTTP server startup timed out: {error}")}));
        }
    }
}

/// Drains pending LocalSend HTTP server events as a JSON array.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_pollHttpServerEvents(
    mut env: JNIEnv, _class: JClass, session: JString
) -> jstring {
    let session_id = match java_string(&mut env, session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let events = http_servers().lock().unwrap().get(&session_id)
        .map(|server| {
            let mut queue = server.events.lock().unwrap();
            queue.drain(..).collect::<Vec<_>>()
        });
    match events {
        Some(events) => return_json(&mut env, json!({"events": events})),
        None => return_json(&mut env, json!({"error": "Unknown HTTP server session"})),
    }
}

/// Accepts selected incoming file IDs, or declines when acceptedFileIdsJson is null.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_respondPrepareUpload(
    mut env: JNIEnv, _class: JClass, session: JString, transfer_session: JString, accepted_file_ids_json: JString
) -> jstring {
    let session_id = match java_string(&mut env, session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let transfer_session_id = match java_string(&mut env, transfer_session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let accepted_json = match java_string(&mut env, accepted_file_ids_json) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let decision = if accepted_json.trim() == "null" {
        None
    } else {
        match serde_json::from_str::<Vec<String>>(&accepted_json) {
            Ok(ids) => Some(ids),
            Err(error) => return return_json(&mut env, json!({"error": error.to_string()})),
        }
    };

    let pending = http_servers().lock().unwrap().get(&session_id)
        .and_then(|server| server.pending_decisions.lock().unwrap().remove(&transfer_session_id));
    match pending {
        Some(sender) => {
            let decision = match decision {
                Some(ids) => PrepareUploadDecisionV2::Accept(ids.into_iter().collect()),
                None => PrepareUploadDecisionV2::Decline,
            };
            match sender.send(decision) {
                Ok(()) => return_json(&mut env, json!({"ok": true})),
                Err(_) => return_json(&mut env, json!({"error": "Upload request already ended"})),
            }
        }
        None => return_json(&mut env, json!({"error": "No pending upload decision"})),
    }
}

/// Routes an accepted file to an app-selected destination path. LocalSend core
/// performs the streaming write and SHA-256 verification.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_acceptFileUpload(
    mut env: JNIEnv, _class: JClass, session: JString, transfer_session: JString, file_id: JString, destination: JString
) -> jstring {
    let session_id = match java_string(&mut env, session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let transfer_session_id = match java_string(&mut env, transfer_session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let file_id = match java_string(&mut env, file_id) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let destination = match java_string(&mut env, destination) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let path = PathBuf::from(destination);
    if path.file_name().is_none() {
        return return_json(&mut env, json!({"error": "Destination must include a file name"}));
    }
    if let Some(parent) = path.parent() {
        if let Err(error) = std::fs::create_dir_all(parent) {
            return return_json(&mut env, json!({"error": error.to_string()}));
        }
    }

    let pending = http_servers().lock().unwrap().get(&session_id)
        .and_then(|server| server.pending_uploads.lock().unwrap().remove(&(transfer_session_id, file_id.clone())));
    match pending {
        Some(target_tx) => {
            let (result_tx, _result_rx) = oneshot::channel::<Result<(), String>>();
            let target = FileUploadTarget::Path {
                path,
                result_tx,
                progress_tx: None,
            };
            match target_tx.send(target) {
                Ok(()) => return_json(&mut env, json!({"ok": true})),
                Err(_) => return_json(&mut env, json!({"error": "Upload request already ended"})),
            }
        }
        None => return_json(&mut env, json!({"error": "No pending file upload"})),
    }
}

/// Stops the LocalSend HTTPS receive server and releases its listener.
#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_stopHttpServer(
    mut env: JNIEnv, _class: JClass, session: JString
) -> jstring {
    let session_id = match java_string(&mut env, session) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, json!({"error": error})),
    };
    let stopped = {
        let mut all = http_servers().lock().unwrap();
        all.get_mut(&session_id).map(|server| {
            server.stopping.store(true, Ordering::Release);
            server.stop.take().map(|stop| stop.send(()).is_ok()).unwrap_or(false)
        })
    };
    return_json(&mut env, json!({"stopping": stopped.unwrap_or(false)}))
}
