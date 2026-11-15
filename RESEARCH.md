# Gamepad Mouse: REA findings

Investigated package `com.pkyul13.gamepadmouse` using the connected REA 6.7.0 tools and JADX headless 0.7.1. Inspection was static; target APKs were never installed or executed.

## Artifact identity

| Version | Version code | Minimum SDK | Target SDK | Base APK SHA-256 |
| --- | --- | --- | --- | --- |
| 1.3.1 | 31 | 32 | 36 | `732f0cacd790d361d7595a964e29f4c131c1ce2a71c817cd881e59bdb45a17f1` |
| 2.0.2 | 44 | 29 | 36 | `f835036304e8004e09fe68b193741044476edcdd260eb029a9b4431e7d46baf5` |

The APKs were extracted from APKPure XAPKs. The downloaded 1.3.1 XAPK SHA-256 matches its published download-page value: `75708c450201b211dd5bd472854dea136e5648cef7f1856d1399ed18318f16ab`. Version 2.0.2 XAPK SHA-256 is `10c5d19e6dd9cd8892d289949ce06e342457742ffc29556e7b7e7d66140bd8db`. APK signatures and equivalence to Google Play-delivered bytes were not verified. Names, version declarations, and hashes identify the analyzed artifacts, not their authenticity.

## Observed click path

In 2.0.2, `MouseAccessibilityService.q(float,float)` constructs a stationary path and a 50 ms `GestureDescription.StrokeDescription`, assigns the active display on API 30+, and calls `dispatchGesture`. Evidence: `ev_f518a18c3e3c44c83eaa90f4bb8cae27002986f407fcba4bfc462ac0ebc6e079`.

`MouseAccessibilityService.C(KeyEvent)` contains a direct call to that tap method and, when custom keyboard support is enabled and keyboard mode is inactive, schedules `o60` after 40 ms. This method required a complete smali fallback; control flow was read from the fallback rather than assuming JADX's reconstructed Java was correct. Evidence: `ev_a48876929f03d38eda6599d40c55cb3d633c27f6ecab95dee342e66c3e12141a`.

In 1.3.1 the corresponding tap method is `d(float,float)` with the same 50 ms gesture shape. Double tap uses two 50 ms strokes separated by a 100 ms gap. Dragging uses continued accessibility strokes. Evidence: `ev_fefc1987a631e75fcf3c5e802266c886878cb8964b6a0e7b89c7cd550ce75d73`, `ev_218fd18ff2454e8a11c446cf695716b4213f4ded6a541850d41c54a34daf1bfc`, `ev_e1d4a0c13e5a866e529028b892dddccca6c8984dab05f438d7e2434a9a3b2c02`.

## Observed custom keyboard behavior in 2.0.2

`o60.run()` looks for an editable node near the tapped coordinates. It schedules a subsequent check/open path and checks purchase eligibility before opening `MouseAccessibilityService.J(node,x,y)`. The `n60.run()` callback also invokes `J`. Evidence: `ev_a2c1731cb15b8f647d17b4a10ee632cd65480eef6c345ffe9d5d3d951d535bf5`, `ev_f9cd1ae419d85ba99fc8de482035ebd8e523da568e5fff25d9f6168e00e5e030`.

`J` creates a `yy` custom keyboard view and wraps it in an accessibility overlay. It tracks its own text buffer, language, navigation state, and visibility. It is not the selected Android IME. Evidence: `ev_ddb8cab5f9f55a13f143d340c0e2e95ac7cb68eac79d95f0c28cbf527cae78b7`.

The editable-node predicate `F(node)` explicitly rejects password nodes before checking the SET_TEXT action and text-editor class names. Evidence: `ev_3f6fe2510ed3f93896710cba6d5df162a4359e33875d0121c36f742b4fa2a46d`. PadPointer's touch path has no such exclusion; the target password editor and selected keyboard handle input normally.

## Observed focus capture and inferred risk

The 1.3.1 `u()` method creates small accessibility-overlay windows to capture controller input. `q(...)` toggles `FLAG_NOT_FOCUSABLE` and requests focus. `at.onTouchEvent()` schedules focus reclamation 65 ms after certain outside touches, and `ws.run()` performs the request. These are static observations. The inference is that replicating this capture scheme can compete with a text editor's focus. No runtime keyboard failure was observed or claimed. Evidence: `ev_b176ca8789b340d4a007aface17303e9ca3381598df9ea87eeabe9b5984547da`, `ev_f9a8573681b841792d6cbc1ca2b7264d2b7a9ad97b37bb1c40ddcbd0390b0acd`, `ev_ff4f8dcd00e525f44d1cd2b1832df26c4abff327fa0be14b29a36a37f2be5628`, `ev_489600407177b17b92bbca1d2c1f09d4a0b2c4e0c537f82c7fbdeb347e5abd52`.

Version 2.0.2 also toggles focusable capture overlays in `X(...)`; this identifies a similar capture mechanism without proving all timing or keyboard behavior is unchanged. Evidence: `ev_81341890ad397dd788e1c58f976ddc4c2f0b68388e84d719eb9b73582e4a8d84`.

## Implementation decision for the requested Android 14+ app

Use the public Android 14 motion-source API for global joystick capture, a non-focusable/non-touchable cursor overlay, and serialized touch gestures. The foreground editor owns the input connection and Android selects the user's IME. Optionally request `SHOW_MODE_IGNORE_HARD_KEYBOARD` to permit the soft keyboard when a controller is classified as a hardware keyboard. Restore the override when PadPointer stops. This is a design based on the observed tap mechanism and documented Android APIs; it is not an observed behavior of the original APK.

Android documents touch dispatch as user-like screen input, motion capture from API 34, and soft-keyboard control as best effort. Thus exact runtime equivalence remains an acceptance-test requirement, not a static-analysis conclusion.

## Evidence and coverage

Full retained REA records are stored locally as `research/evidence/rea-final.json`; raw evidence and original APKs are excluded from the public repository. This bundle contains 67 records, including both package manifests and the selected class/method observations. Reported method source coverage is complete for the cited bodies; `C` is smali. Static reference coverage does not establish dynamic/reflective reachability. No Frida/runtime, physical-controller, or native-keyboard test was performed. The Device panel could not open the test emulator because host FFmpeg lacks libx264.

Sources:

- [Gamepad Mouse on Google Play](https://play.google.com/store/apps/details?id=com.pkyul13.gamepadmouse)
- [REA](https://github.com/morluto/rea)
- [APKPure 1.3.1 artifact page](https://apkpure.net/gamepad-mouse/com.pkyul13.gamepadmouse/download/1.3.1)
- [Android AccessibilityService: touch dispatch and motion events](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android AccessibilityServiceInfo: motion-source selection](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#setMotionEventSources(int))
- [Android soft-keyboard controller](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.SoftKeyboardController)
- [Window focus and touch flags](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
