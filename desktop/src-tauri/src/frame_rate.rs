//! The frame rate of the web view on macOS.
//!
//! WebKit keeps the page frames near 60 fps by default, also on a 120 Hz
//! ProMotion display. The WebKit feature `PreferPageRenderingUpdatesNear60FPSEnabled`
//! sets this limit. Then a pan of the canvas moves the drawing half as often as
//! the fingers move on the trackpad. With the feature off, the page gets one
//! frame for each frame of the display.

#![allow(unexpected_cfgs)] // objc 0.2 macro noise, see `set_macos_dock_icon`.

use objc::runtime::{Class, Object, Sel, BOOL, NO};
use objc::{msg_send, sel, sel_impl};
use std::ffi::CStr;
use std::os::raw::c_char;

type Id = *mut Object;

/// The WebKit feature that keeps the page frames near 60 fps.
pub const NEAR_60_FPS_FEATURE: &str = "PreferPageRenderingUpdatesNear60FPSEnabled";

/// Turn off the 60 fps limit in the `WKPreferences` object `prefs`.
///
/// It gives `true` when the feature is off after the call. It gives `false`
/// when this WebKit has no such feature.
///
/// The call uses private WebKit API. It first asks each object if it knows
/// the selector, so an older or newer WebKit does not throw an exception.
pub unsafe fn allow_full_frame_rate(prefs: Id) -> bool {
    if prefs.is_null() {
        return false;
    }
    let Some(feature) = find_feature(NEAR_60_FPS_FEATURE) else {
        return false;
    };
    if !responds_to(prefs, sel!(_setEnabled:forFeature:))
        || !responds_to(prefs, sel!(_isEnabledForFeature:))
    {
        return false;
    }
    let _: () = msg_send![prefs, _setEnabled: NO forFeature: feature];
    let on: BOOL = msg_send![prefs, _isEnabledForFeature: feature];
    on == NO
}

/// The `_WKFeature` object for `key`, from the private feature list of WebKit.
///
/// It gives `None` when WebKit has no such list or no feature with this key.
unsafe fn find_feature(key: &str) -> Option<Id> {
    let class = Class::get("WKPreferences")?;
    let class_obj = class as *const Class as Id;
    if !responds_to(class_obj, sel!(_features)) {
        return None;
    }
    let features: Id = msg_send![class_obj, _features];
    if features.is_null() || !responds_to(features, sel!(count)) {
        return None;
    }
    let count: usize = msg_send![features, count];
    (0..count)
        .map(|i| -> Id { msg_send![features, objectAtIndex: i] })
        .find(|&f| feature_key(f).as_deref() == Some(key))
}

/// The `key` of a `_WKFeature` object, as a Rust string.
unsafe fn feature_key(feature: Id) -> Option<String> {
    if feature.is_null() || !responds_to(feature, sel!(key)) {
        return None;
    }
    let key: Id = msg_send![feature, key];
    if key.is_null() || !responds_to(key, sel!(UTF8String)) {
        return None;
    }
    let utf8: *const c_char = msg_send![key, UTF8String];
    if utf8.is_null() {
        return None;
    }
    CStr::from_ptr(utf8).to_str().ok().map(str::to_owned)
}

/// `true` when `obj` knows the selector `sel`.
unsafe fn responds_to(obj: Id, sel: Sel) -> bool {
    let yes: BOOL = msg_send![obj, respondsToSelector: sel];
    yes != NO
}

// The test is in tests/frame_rate.rs: WebKit must start on the main thread,
// and the test runner runs each unit test on a different thread.
