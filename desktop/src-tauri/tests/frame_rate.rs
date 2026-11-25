//! The web view gets a frame for each frame of the display (macOS).
//!
//! WebKit must start on the main thread. The default test runner runs each
//! test on a different thread, so this file has its own `main`
//! (`harness = false` in Cargo.toml).

#[cfg(target_os = "macos")]
#[allow(dead_code)]
#[path = "../src/frame_rate.rs"]
mod frame_rate;

#[cfg(target_os = "macos")]
#[allow(unexpected_cfgs)]
fn main() {
    use frame_rate::{allow_full_frame_rate, NEAR_60_FPS_FEATURE};
    use objc::runtime::{Object, BOOL};
    use objc::{class, msg_send, sel, sel_impl};
    use std::ffi::CStr;
    use std::os::raw::c_char;

    type Id = *mut Object;

    #[link(name = "WebKit", kind = "framework")]
    extern "C" {}

    /// The `_WKFeature` object for `key`, from the private list of WebKit.
    unsafe fn feature(key: &str) -> Option<Id> {
        let features: Id = msg_send![class!(WKPreferences), _features];
        let count: usize = msg_send![features, count];
        (0..count)
            .map(|i| -> Id { msg_send![features, objectAtIndex: i] })
            .find(|f| {
                let k: Id = msg_send![*f, key];
                let utf8: *const c_char = msg_send![k, UTF8String];
                CStr::from_ptr(utf8).to_str() == Ok(key)
            })
    }

    unsafe fn is_enabled(prefs: Id, key: &str) -> bool {
        let f = feature(key).expect("this WebKit has the feature");
        let on: BOOL = msg_send![prefs, _isEnabledForFeature: f];
        on
    }

    print!("test the_web_view_gets_a_frame_for_each_frame_of_the_display ... ");
    unsafe {
        let prefs: Id = msg_send![class!(WKPreferences), new];
        assert!(is_enabled(prefs, NEAR_60_FPS_FEATURE), "the 60 fps limit is on by default");
        assert!(allow_full_frame_rate(prefs), "the call turns the limit off");
        assert!(!is_enabled(prefs, NEAR_60_FPS_FEATURE), "the 60 fps limit is off after the call");
    }
    println!("ok");
}

#[cfg(not(target_os = "macos"))]
fn main() {}
