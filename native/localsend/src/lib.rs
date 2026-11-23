use jni::objects::{JClass, JString};
use jni::sys::{jint, jboolean, jstring};
use jni::JNIEnv;
use localsend::discovery::{self, ChannelStatus, DeviceChannel, DeviceIdentity, DiscoveryConfig, DiscoveryEvent};
use localsend::model::discovery::{DeviceType, ProtocolType, PROTOCOL_VERSION_V2};
use localsend::multicast::{DEFAULT_MULTICAST_GROUP, DEFAULT_MULTICAST_GROUP_V6, DEFAULT_PORT};
use localsend::util::interface::InterfaceFilter;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};

enum DiscoveryCommand {
    Announce,
    Probe { host: String, port: u16, https: bool },
}

struct DiscoverySession {
    stop: oneshot::Sender<()>,
    stopped: Arc<AtomicBool>,
    snapshot: Arc<Mutex<String>>,
    commands: mpsc::UnboundedSender<DiscoveryCommand>,
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
        Err(error) => return_json(&mut env, json!({ "error": error })),
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
                .enable_time()
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
        Err(error) => return_json(&mut env, json!({ "error": error })),
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
        Err(error) => return_json(&mut env, json!({ "error": error })),
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
    let session_id = match java_string(&mut env, session) { Ok(v) => v, Err(e) => return_json(&mut env, json!({"error": e})) };
    let queued = sessions().lock().unwrap().get(&session_id).map(|s| s.commands.send(DiscoveryCommand::Announce).is_ok()).unwrap_or(false);
    return_json(&mut env, json!({"queued": queued}))
}

#[no_mangle]
pub extern "system" fn Java_app_orchard_tv_localsend_LocalSendNative_probeDevice(mut env: JNIEnv, _class: JClass, session: JString, host: JString, port: jint, https: jboolean) -> jstring {
    let session_id = match java_string(&mut env, session) { Ok(v) => v, Err(e) => return_json(&mut env, json!({"error": e})) };
    let host = match java_string(&mut env, host) { Ok(v) => v, Err(e) => return_json(&mut env, json!({"error": e})) };
    if !(1..=65535).contains(&port) { return return_json(&mut env, json!({"error": "Port must be between 1 and 65535"})); }
    let queued = sessions().lock().unwrap().get(&session_id).map(|s| s.commands.send(DiscoveryCommand::Probe { host, port: port as u16, https: https != 0 }).is_ok()).unwrap_or(false);
    return_json(&mut env, json!({"queued": queued}))
}
