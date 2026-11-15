# PadPointer

A standalone controller mouse for Android 14+ (API 34). Built from the Gamepad Mouse REA investigation, with text-field taps routed through Android's normal touch and selected-keyboard path.

## Install and use

Download the APK from the [latest GitHub release](https://github.com/BrianCodez/PadPointer/releases/latest), or add this repository URL to Obtainium:

```text
https://github.com/BrianCodez/PadPointer
```

In Obtainium, choose **Add app**, paste the URL, and add it with the default GitHub source settings. Releases contain one APK. Obtainium can check for and install future published releases; updates will still require publishing a new APK with a higher version code and the same signing key.

Version 0.2.6 installs over earlier builds and preserves settings. Open PadPointer, enable **PadPointer controller mouse** in Android's accessibility settings, and turn on **Pointer enabled**. Android may require an additional confirmation for a sideloaded accessibility service. This is an early debug-signed test build; device verification is pending.

The accessibility service must be enabled, but no floating accessibility shortcut is needed or requested. Use your on/off buttons (L3 by default) or the app's **Pointer enabled** switch. If Android retained a shortcut from an earlier version, open **Settings → Accessibility → PadPointer controller mouse** (under **Installed apps** on some devices) and turn off its **Shortcut** option while leaving the service itself enabled. See [Android's shortcut-removal instructions](https://support.google.com/accessibility/android/answer/7650693).

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
| L3 (changeable) | Pointer on / off |
| R3 | Pass controller input to the focused app or keyboard; press again to return |

Move over an input field and press A. The app receives a 50 ms touch gesture; its own text editor handles focus and the user's currently selected IME. The pointer can tap that keyboard's keys. R3 also permits native controller navigation if the selected keyboard supports it. R3 does not add controller navigation to an IME that lacks it.

**Pointer on / off buttons:** under **Pointer on / off**, tap **Change on / off buttons…**, hold any controller button or several together (for example L1 + R1 or Select + Start), then let go. **Reset to L3** restores the default. A single button always toggles the pointer, even in games. With a combination, each button keeps its normal use when pressed alone. While the pointer is off, games still receive the first buttons; only the button that completes the combination is taken. While the pointer is on, combination buttons wait up to 250 ms for the rest before doing their own action. R3 alone stays reserved for keyboard pass-through.

Settings include speed, acceleration, deadzone, size, scrolling, inactivity hiding, vibration, and button mapping. Mappable actions also include drag toggle, notifications, screenshot, media play/pause, brightness, and repeat click at a saved location. Brightness access is optional. Repeat click stops with its mapped button, pointer off, screen off, keyboard pass-through, or controller disconnect.

Joystick scrolling drags a virtual finger. Stick deflection sets the speed through a radial deadzone and a short smoothing ramp. Holding the stick scrolls continuously:

- **No gaps between segments.** Each segment is one display frame long, and about 40 ms of movement stays queued. Android then joins each stroke directly to the previous one.
- **Controller input no longer stops the scroll.** On Android 14 and 15, every controller event that reaches an accessibility service cancels the injected touch (`MotionEventInjector.onMotionEvent`). Holding a stick can produce a steady stream of these events. PadPointer now places a new finger within about a millisecond and moves it past touch slop at once, keeping the current speed. Apps drop the slop distance from the first drag delta, so the content usually resumes within a frame or two. Very frequent controller events still make it choppy; see the known limitations under **Verification status**.
- **Container edges no longer pause the scroll.** When the finger runs out of room, a new finger goes down at the far side and the old one is cancelled, without a lift or fling.

- **Firefox and other GeckoView browsers** (Focus, Fennec, Mull, Iceraven, Tor Browser, and others) ignore the cancel Android sends for a replaced finger, because its timestamp does not match the touch. Firefox then still thinks the old finger is down, so a new finger elsewhere made the page jump back, sometimes with a zoom. In these apps, PadPointer puts a replacement finger down exactly where Firefox last saw the old one. At the edge of the scroll area it lifts the old finger normally instead of cancelling it. It reserves room near the edge to move a restarted finger past Firefox's tap distance before lifting, reducing the risk of an unintended link click.

If a cancellation arrives with no controller input, such as a real finger on the screen, scrolling stops instead of fighting that touch. Returning the stick to neutral eases out over a few frames, then holds still before lifting so apps add no fling. The re-gripping finger follows the scrollable container under the cursor. Its bounds refresh on a background worker as toolbars collapse, so a slow target app cannot block the gesture callbacks; updated bounds may take a few hundred milliseconds to arrive. It stays clear of a visible keyboard when the cursor is outside it. This still uses touch gestures, not native mouse-wheel injection.

Clicks and mapped actions end an active scroll touch first. After a mapped button interrupts scrolling, return the right stick to neutral before starting another scroll. **Scroll speed** still controls the overall rate.

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

Debug build, 46 unit tests, Android lint, and APK signature verification passed. Configurable pointer on/off buttons were verified on the Android 15 emulator with a virtual gamepad. Combinations toggle in either order, single presses keep their actions, and repeated toggles stay reliable. The release includes `validation.txt` with the warnings; there are no lint errors. Unit tests cover noisy sticks, diagonal speed, acceleration, invalid data, screen bounds, scroll planning, stops, reversals, container edges, proportional speed, interruption, and touch slop.

`ScrollDriverTest` holds the stick against `InjectorSimulation`, a model of Android's gesture injector based on the AOSP android14-release source.

### 0.2.6 smoothing changes

Movement segments now contain one injector sample at every refresh rate (including 120/144 Hz). Previously a cancellation could happen after an intermediate move but before the completion callback, leaving Firefox recovery one frame behind and causing a small backward correction. The gesture queue still covers about 40 ms to absorb callback delays. Scroll-area refreshes run off the main thread, with at most one request outstanding; results from an old scroll session are ignored. Firefox also reserves room to finish a restarted drag past its tap tolerance before lifting at an edge.

The JVM injector/Firefox model reproduces backward corrections with 0.2.5 at 120/144 Hz and passes the new regression checks with 0.2.6. These are simulations, not phone performance measurements. All 46 tests pass. A new emulator run was blocked: `PadPointer_API35` crashed with SIGSEGV at startup, including the diagnostic retry. Therefore 0.2.6 has **not** been visually verified on an emulator or physical phone. The measurements below belong to earlier releases.

### Emulator measurements (Android 15)

Scrolling was measured on an Android 15 emulator (API 35, 60 Hz) in Settings › Apps › All apps. A virtual uinput gamepad held the right stick fully down for 5 s, once still and once with random ±1% jitter at set intervals. The screen was recorded, and list movement was measured per frame. On Android 14–15, every jitter event cancels the injected touch.

| Stick | 0.2.2 | 0.2.3 |
| --- | --- | --- |
| Held still | Stops up to 117 ms at container edges; 87% speed | No stop longer than 2 frames (33 ms); 99% speed |
| Jitter every 120 ms | Stops up to 300 ms; 28% speed | No stop longer than 2 frames; 94–95% speed |
| Jitter every 30 ms | Frozen | No stop longer than 2 frames; 92% speed |
| Jitter every 8 ms (extreme) | Frozen | Choppy: about 330 px/s, stops up to 183 ms |

About 10% of frames show no movement even in the best runs, which is emulator rendering/recording jitter. No unintended taps occurred.

In Firefox 160 on the same emulator (a mobile Wikipedia article), 0.2.4 jumped back to the top of the page about every 1.3 s while the stick was held, once with a zoom. 0.2.5 scrolls at 20–22 px every frame with no jumps. With jitter every 60 ms it keeps full speed and still never jumps back. A scroll left hanging by a stick release continues cleanly, and a tap afterwards opens the link under the pointer.

Known limitations on Android 14–15 with a stick that keeps reporting small changes:
- Very frequent controller events, every few milliseconds, still make scrolling choppy. Each one cancels the touch, and Android discards moves the app has not consumed yet.
- At the top of a page with a snapping collapsing header, such as Settings, frequent controller events can keep the header snapping back open, so the page stays put. A still stick (for example, pushed fully against its edge) collapses it normally.

Device acceptance checks are in [DEVICE-TESTS.md](DEVICE-TESTS.md). Static findings and source evidence are in [RESEARCH.md](RESEARCH.md).

## Scope

This version targets phone/tablet control on the primary display. Samsung DeX window management, secondary-display routing, and Android 10–13 are outside this build. The selected Android keyboard provides text input. Original app binaries, assets, advertising, purchases, and its custom Steam-style keyboard are not included in the source package.

The app has no network permission, account, analytics, or content logging.

## Publishing updates

Bump `versionCode` and `versionName` in `app/build.gradle`, build and run the checks above, then publish a numbered GitHub release with the APK as its only APK asset. Keep the signing certificate unchanged: the current test APK uses the original local Android debug keystore, which is not in this repository. A build from a different machine's generated debug keystore cannot update this APK in place. Release APK checksums are published in `SHA256SUMS`.

Source archives are provided by GitHub. Third-party APKs, raw REA evidence, local build output, and private signing material are excluded from this repository.
