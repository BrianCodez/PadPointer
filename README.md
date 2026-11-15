# PadPointer

A standalone controller mouse for Android 14+ (API 34). Built from the Gamepad Mouse REA investigation, with text-field taps routed through Android's normal touch and selected-keyboard path.

## Install and use

Download the APK from the [latest GitHub release](https://github.com/BrianCodez/PadPointer/releases/latest), or add this repository URL to Obtainium:

```text
https://github.com/BrianCodez/PadPointer
```

In Obtainium, choose **Add app**, paste the URL, and add it with the default GitHub source settings. Releases contain one APK. Obtainium can check for and install future published releases; updates will still require publishing a new APK with a higher version code and the same signing key.

Version 0.2.1 installs over the earlier 0.1.0 / 0.2.0 builds and preserves settings. Open PadPointer, enable **PadPointer controller mouse** in Android's accessibility settings, and turn on **Pointer enabled**. Android may require an additional confirmation for a sideloaded accessibility service. This is an early debug-signed test build; device verification is pending.

The accessibility service must be enabled, but no floating accessibility shortcut is needed or requested. Use L3 or the app's **Pointer enabled** switch. If Android retained a shortcut from an earlier version, open **Settings → Accessibility → PadPointer controller mouse** (under **Installed apps** on some devices) and turn off its **Shortcut** option while leaving the service itself enabled. See [Android's shortcut-removal instructions](https://support.google.com/accessibility/android/answer/7650693).

| Input | Default action |
| --- | --- |
| Left stick | Move cursor |
| Right stick | Touch-scroll at cursor |
| D-pad | Precision cursor movement |
| A | Tap; hold to drag |
| B | Back |
| X / Y | Double tap / long press |
| Start / Select | Home / Recents |
| L1 / R1 | Volume down / up |
| L3 | Pointer on / off |
| R3 | Pass controller input to the focused app or keyboard; press again to return |

Move over an input field and press A. The app receives a 50 ms touch gesture; its own text editor handles focus and the user's currently selected IME. The pointer can tap that keyboard's keys. R3 also permits native controller navigation if the selected keyboard supports it. R3 does not add controller navigation to an IME that lacks it.

Settings include speed, acceleration, deadzone, size, scrolling, inactivity hiding, vibration, and button mapping. Mappable actions also include drag toggle, notifications, screenshot, media play/pause, brightness, and repeat click at a saved location. Brightness access is optional. Repeat click stops with its mapped button, pointer off, screen off, keyboard pass-through, or controller disconnect.

## Cursor appearance and stick testing

Under **Cursor appearance**, choose Classic arrow, Compact pointer, Dot, Crosshair, or Custom image. Built-in styles support seven color presets and a custom six-digit RGB hex value. A contrasting outline keeps dark and light colors visible. The preview updates immediately, and all choices persist.

Use **Import cursor image…** to choose an image through Android's system file picker. The app preserves its colors, transparency, and aspect ratio, and stores a PNG copy in private app storage with a maximum edge of 512 pixels. No broad photo/storage permission is requested. Cancelled or failed imports retain the previous image. Animated formats use a static frame.

Set **Image click point X / Y** to align a custom image's tip with the tap location. The preview marker shows that point; 0% / 0% is the image's top-left and 50% / 50% is its center. Dot and crosshair styles click at their centers. Cursor size now ranges from 12 to 128 dp. In R3 pass-through mode an amber badge indicates that controls are going to the keyboard/app while preserving the selected cursor color or image.

Under **Stick input test**, turn the pointer off and move the sticks. Each stick shows raw X/Y, magnitude, a live bar, and deadzone status. The left stick also shows output after applying the acceleration curve. The tester uses the same right-stick axis mapping as pointer control, pauses while the pointer is enabled, and clears on controller disconnect.

## Keyboard behavior

- Joystick input uses `AccessibilityServiceInfo.setMotionEventSources` and `AccessibilityService.onMotionEvent` (Android 14+).
- The cursor uses `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`. There is no focus-capture window, hidden text editor, replacement keyboard, or scheduled focus reclamation.
- Clicks use `AccessibilityService.dispatchGesture`, so the target application performs its normal touch handling.
- **Show on-screen keyboard with a controller connected** requests `SHOW_MODE_IGNORE_HARD_KEYBOARD`. This allows the selected keyboard even when Android treats the controller as a physical keyboard. It does not switch the selected IME. PadPointer relinquishes its override when disabled or disconnected.
- Gestures are serialized. A new click finishes an active continued drag before it is dispatched.

The expected result is the same keyboard path as a finger tap. Apps and IMEs retain their own behavior, including custom editors and explicit keyboard suppression. Actual keyboard behavior and controller hardware compatibility still require device verification.

## Build

Requires JDK 17, Android SDK platform 35 and build-tools, and network access for uncached Gradle dependencies.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. The supplied APK is a debug-signed test build. Package: `com.briancodez.padpointer`; minimum Android API: 34.

## Verification status

Debug build, five unit tests, Android lint, and APK signature verification passed. The release includes `validation.txt` with the warnings; there are no lint errors. Unit tests cover noisy sticks, diagonal speed, acceleration, invalid data, and screen bounds. They do not establish runtime keyboard behavior.

Runtime testing was blocked by the Device panel error: **Android emulator video needs the libx264 encoder.** The host's FFmpeg has no libx264 encoder. No app launch, keyboard result, or physical-controller result is claimed. A test AVD named `PadPointer_API35` was created and remains available after the host codec issue is fixed.

Device acceptance checks are in [DEVICE-TESTS.md](DEVICE-TESTS.md). Static findings and source evidence are in [RESEARCH.md](RESEARCH.md).

## Scope

This version targets phone/tablet control on the primary display. Samsung DeX window management, secondary-display routing, and Android 10–13 are outside this build. The selected Android keyboard provides text input. Original app binaries, assets, advertising, purchases, and its custom Steam-style keyboard are not included in the source package.

The app has no network permission, account, analytics, or content logging.

## Publishing updates

Bump `versionCode` and `versionName` in `app/build.gradle`, build and run the checks above, then publish a numbered GitHub release with the APK as its only APK asset. Keep the signing certificate unchanged: the current test APK uses the original local Android debug keystore, which is not in this repository. A build from a different machine's generated debug keystore cannot update this APK in place. Release APK checksums are published in `SHA256SUMS`.

Source archives are provided by GitHub. Third-party APKs, raw REA evidence, local build output, and private signing material are excluded from this repository.
