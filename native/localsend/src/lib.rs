use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::json;

/// Creates the same RSA/TLS device identity used by LocalSend.
///
/// The JSON contract keeps the JNI boundary simple and versionable. Private
/// material is returned only to the in-process Aerial caller and must be
/// persisted in app-private storage, never logged or exported.
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
            .to_string()
        })
        .unwrap_or_else(|error| {
            json!({
                "error": error.to_string(),
            })
            .to_string()
        });

    match env.new_string(result) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
