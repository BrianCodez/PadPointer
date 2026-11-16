package com.briancodez.padpointer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.HashSet;

public final class PointerService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener, InputManager.InputDeviceListener {
    static boolean connected;
    /** The settings screen is recording new on/off buttons; let every controller button through to it. */
    static volatile boolean capturing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashSet<Integer> consumed = new HashSet<>();
    private final HashSet<Integer> directions = new HashSet<>();
    private final HashSet<Integer> swallowRelease = new HashSet<>();
    private ButtonChord chord = new ButtonChord(Prefs.DEFAULT_POINTER_BUTTONS);
    private final Runnable chordTimeout = this::runWaitingButtons;
    private final ArrayDeque<GestureDescription> gestures = new ArrayDeque<>();
    private final ArrayDeque<String> deferredActions = new ArrayDeque<>();
    private Prefs prefs;
    private WheelBridge wheelBridge;
    private final WheelMotion wheelMotion = new WheelMotion();
    private boolean nativeMode, nativeNeedsCenter = true, nativeWasReady;
    private float wheelHorizontalFactor, wheelVerticalFactor;
    private final Runnable wheelConnectionChanged = () -> {
        boolean ready = wheelBridge.ready();
        if (ready != nativeWasReady) {
            nativeWasReady = ready; nativeNeedsCenter = true; stopNativeScroll();
            if (this.enabled && nativeMode) toast(ready ? "Shizuku connected · center the right stick to scroll"
                    : "Shizuku scrolling unavailable · reconnect in PadPointer");
        }
    };
    private WindowManager windows;
    private CursorView cursor;
    private WindowManager.LayoutParams cursorParams;
    private InputManager inputs;
    private float x, y, width, height, axisX, axisY, scrollX, scrollY;
    private float dragX, dragY, repeatX, repeatY;
    private boolean enabled, typing, dragging, clickPending, repeat, keyboardOverride;
    private boolean destroyed;
    private int lastDeviceId = -1;
    /** Dispatched gestures (taps, drags, and scroll segments) still awaiting Android's result. */
    private int inFlight;
    private long lastFrameNanos, lastActivity;
    private GestureDescription.StrokeDescription stroke;
    private boolean scrollInterrupted;
    /** Newest stroke of the scrolling finger, which the next segment continues. */
    private GestureDescription.StrokeDescription scrollStroke;
    private AccessibilityNodeInfo scrollNode;
    // A target app may take hundreds of milliseconds to answer a node refresh. Never make the
    // gesture callback thread wait for it. Keep just one request outstanding and reject old results.
    private final java.util.concurrent.ExecutorService scrollBoundsWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean scrollBoundsPending;
    private ScrollMotion.Area cachedScrollArea;
    /** App being scrolled, and where a Firefox-engine app still believes a cancelled finger is down. */
    private String scrollPackage, stalePackage;
    private int[] staleTouch;
    private final java.util.HashMap<String, Boolean> geckoApps = new java.util.HashMap<>();
    private Rect scrollWindow, scrollKeyboard;
    private float scrollOriginX, scrollOriginY;
    private final ScrollDriver scroller = new ScrollDriver(new ScrollDriver.Host() {
        @Override public boolean send(ScrollMotion.Segment segment, int epoch) { return sendScroll(segment, epoch); }
        @Override public float stickX() { return scrollX; }
        @Override public float stickY() { return scrollY; }
        @Override public float deadzone() { return prefs.deadzone(); }
        @Override public float speed() { return Math.min(width, height) * 1.2f * prefs.data.getInt("scroll", 100) / 100f; }
        @Override public boolean stickHeld() { return scrollStickHeld(); }
        @Override public boolean blocked() { return scrollBlocked() || nativeMode; }
        @Override public ScrollMotion.Area refreshArea() { return refreshScrollArea(); }
        @Override public long now() { return SystemClock.uptimeMillis(); }
        @Override public void staleTouch(int x, int y) { staleTouch = new int[]{x, y}; stalePackage = scrollPackage; }
        @Override public void touchEnded() { staleTouch = null; }
        @Override public int[] staleTouch() {
            return staleTouch != null && scrollPackage != null && scrollPackage.equals(stalePackage) ? staleTouch : null;
        }
        @Override public void ended(boolean forgetStick) {
            scrollStroke = null; scrollNode = null; cachedScrollArea = null;
            if (forgetStick) scrollX = scrollY = 0;
            if (!destroyed) pump();
        }
    });

    private final Runnable holdClick = () -> { if (clickPending) { clickPending = false; dragging = true; } };
    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (!enabled || !repeat || typing) return;
            if (!busy() && stroke == null && gestures.isEmpty() && !scroller.active()) enqueue(tapGesture(repeatX, repeatY, 50, false));
            handler.postDelayed(this, prefs.repeatMs());
        }
    };
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (destroyed) return;
            long now = SystemClock.uptimeMillis();
            // Use the display clock, not callback arrival time, to avoid converting main-thread
            // scheduling jitter into speed changes on high-refresh displays.
            float dt = lastFrameNanos == 0 ? 0.016f
                    : Math.max(0, Math.min(0.05f, (frameTimeNanos - lastFrameNanos) / 1_000_000_000f));
            lastFrameNanos = frameTimeNanos;
            if (!nativeMode || !enabled || typing || scrollBlocked() || !scrollStickHeld()
                    || !wheelBridge.ready() || nativeNeedsCenter) stopNativeScroll();
            if (enabled && !typing) {
                float[] velocity = PointerMath.velocity(axisX, axisY, prefs.deadzone(), prefs.curve());
                float dx = velocity[0], dy = velocity[1];
                if (directions.contains(KeyEvent.KEYCODE_DPAD_LEFT)) dx -= 0.4f;
                if (directions.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) dx += 0.4f;
                if (directions.contains(KeyEvent.KEYCODE_DPAD_UP)) dy -= 0.4f;
                if (directions.contains(KeyEvent.KEYCODE_DPAD_DOWN)) dy += 0.4f;
                if (dx != 0 || dy != 0) {
                    x = PointerMath.clamp(x + dx * prefs.speed() * dt, width);
                    y = PointerMath.clamp(y + dy * prefs.speed() * dt, height);
                    showCursor();
                    moveCursor();
                }
                if (scroller.active()) scroller.feed();
                else if (!busy()) {
                    if (stroke != null) continueDrag();
                    else if (!gestures.isEmpty()) pump();
                    else if (dragging) continueDrag();
                    else if (scrollRequested()) {
                        if (nativeMode) scrollNative(dt, now);
                        else startScroll();
                    }
                }
                if (prefs.timeout() > 0 && now - lastActivity > prefs.timeout() * 1000L && !dragging && !scroller.active() && !repeat) cursor.setVisibility(View.INVISIBLE);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { prefs.data.edit().putBoolean("enabled", false).apply(); }
    };

    @Override protected void onServiceConnected() {
        connected = true;
        prefs = new Prefs(this);
        wheelBridge = WheelBridge.get(this);
        wheelBridge.listen(wheelConnectionChanged);
        ViewConfiguration configuration = ViewConfiguration.get(this);
        wheelHorizontalFactor = configuration.getScaledHorizontalScrollFactor();
        wheelVerticalFactor = configuration.getScaledVerticalScrollFactor();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        updateBounds(); x = width / 2; y = height / 2;
        cursor = new CursorView(this);
        cursorParams = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
        cursorParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        cursorParams.setFitInsetsTypes(0);
        windows.addView(cursor, cursorParams);
        cursor.setVisibility(View.INVISIBLE);
        prefs.data.registerOnSharedPreferenceChangeListener(this);
        inputs = (InputManager) getSystemService(INPUT_SERVICE);
        inputs.registerInputDeviceListener(this, handler);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        applyPreferences();
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void updateBounds() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels; height = metrics.heightPixels;
        x = PointerMath.clamp(x, width); y = PointerMath.clamp(y, height);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (windows == null) return;
        axisX = axisY = scrollX = scrollY = 0; dragging = false;
        stopNativeScroll(); nativeNeedsCenter = true;
        updateBounds(); moveCursor();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences data, String key) { applyPreferences(); }

    private void applyPreferences() {
        boolean changedMode = nativeMode != prefs.nativeScroll();
        nativeMode = prefs.nativeScroll();
        if (changedMode) {
            scrollX = scrollY = 0; nativeNeedsCenter = true; stopNativeScroll();
        }
        wheelBridge.setEnabled(nativeMode);
        boolean wasEnabled = enabled;
        enabled = prefs.enabled();
        if (!enabled || !wasEnabled) {
            stopNativeScroll(); nativeNeedsCenter = true;
            axisX = axisY = scrollX = scrollY = 0;
            directions.clear(); gestures.clear(); deferredActions.clear();
            clickPending = false; dragging = false; typing = false;
            stopRepeat(); handler.removeCallbacks(holdClick);
        }
        int[] onOff = prefs.pointerButtons();
        if (!chord.matches(onOff)) { handler.removeCallbacks(chordTimeout); chord = new ButtonChord(onOff); }
        AccessibilityServiceInfo info = getServiceInfo();
        int sources = enabled && !typing ? InputDevice.SOURCE_JOYSTICK : 0;
        // Changing the service's settings makes Android reroute releases of held buttons, so only do it when needed.
        if (info != null && ((info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0 || info.getMotionEventSources() != sources)) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.setMotionEventSources(sources);
            setServiceInfo(info);
        }
        if (enabled && prefs.forceKeyboard()) {
            if (!keyboardOverride) keyboardOverride = getSoftKeyboardController().setShowMode(SHOW_MODE_IGNORE_HARD_KEYBOARD);
        } else if (keyboardOverride) {
            if (getSoftKeyboardController().getShowMode() == SHOW_MODE_IGNORE_HARD_KEYBOARD) getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
            keyboardOverride = false;
        }
        int size = (int) (prefs.size() * getResources().getDisplayMetrics().density);
        cursorParams.width = cursorParams.height = size;
        cursor.applyAppearance(prefs);
        cursor.setTyping(typing);
        if (enabled) showCursor();
        else cursor.setVisibility(View.INVISIBLE);
        moveCursor();
        if (!enabled) pump(); // Finish any continued touch before leaving pointer mode.
    }

    private void toggle() {
        prefs.data.edit().putBoolean("enabled", !enabled).apply();
        toast(enabled ? "Pointer on · R3 passes controls to the keyboard" : "Pointer off · " + prefs.pointerButtonsName() + " turns it back on");
    }

    @Override public void onMotionEvent(MotionEvent event) { motion(event); }

    private boolean motion(MotionEvent event) {
        if (!enabled || typing || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
        lastDeviceId = event.getDeviceId();
        axisX = event.getAxisValue(MotionEvent.AXIS_X); axisY = event.getAxisValue(MotionEvent.AXIS_Y);
        scrollX = GamepadAxes.rightX(event);
        scrollY = GamepadAxes.rightY(event);
        if (Math.hypot(scrollX, scrollY) <= prefs.deadzone()) {
            scrollInterrupted = false; nativeNeedsCenter = false; stopNativeScroll();
        }
        // On Android 14-15 this event has just cancelled any injected touch; the scroller re-grips.
        scroller.stickMoved();
        // Some controllers expose their D-pad only as HAT axes.
        if (axisX == 0 && axisY == 0) {
            axisX = event.getAxisValue(MotionEvent.AXIS_HAT_X) * 0.5f;
            axisY = event.getAxisValue(MotionEvent.AXIS_HAT_Y) * 0.5f;
        }
        return true;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        InputDevice input = event.getDevice();
        boolean controller = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || (input != null && (input.supportsSource(InputDevice.SOURCE_GAMEPAD) || input.supportsSource(InputDevice.SOURCE_JOYSTICK)))
                || (KeyEvent.isGamepadButton(key) && event.getDeviceId() >= 0);
        if (!controller || capturing) return false; // While capturing, the settings screen records the buttons itself.
        lastDeviceId = event.getDeviceId();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // Released before the rest of its combination arrived: it was an ordinary press of that button.
            if (chord.isWaiting(key)) runWaitingButtons();
            if (chord.release(key)) return true;
            return buttonUp(key);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0) {
            chord.stillHeld(key, event.getEventTime());
            return consumed.contains(key) || chord.isSwallowed(key) || chord.isWaiting(key);
        }
        // Any other button means the held-back ones were not the start of the combination.
        if (!chord.isMember(key) && chord.isWaiting()) runWaitingButtons();
        switch (chord.press(key, actsOnItsOwn(key), event.getEventTime())) {
            case TOGGLE -> { handler.removeCallbacks(chordTimeout); toggle(); return true; }
            case WAIT -> { handler.removeCallbacks(chordTimeout); handler.postDelayed(chordTimeout, ButtonChord.WINDOW_MS); return true; }
            default -> { return buttonDown(key, event.getDevice()); }
        }
    }

    /**
     * Whether PadPointer handles this button by itself right now, so it can wait briefly for the rest of the on/off
     * combination without the app missing a press.
     */
    private boolean actsOnItsOwn(int key) {
        if (!enabled) return false;
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBR) return true;
        if (typing) return false;
        if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_CENTER) return true;
        return !"Pass through".equals(prefs.action(key));
    }

    /** Buttons held back for the combination that turned out to be ordinary presses. */
    private void runWaitingButtons() {
        handler.removeCallbacks(chordTimeout);
        for (int key : chord.expire()) {
            // If the button no longer does anything (pointer mode changed meanwhile), still swallow its release.
            if (!buttonDown(key, InputDevice.getDevice(lastDeviceId))) swallowRelease.add(key);
        }
    }

    private boolean buttonDown(int key, InputDevice device) {
        if (!enabled) return false;
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            consumed.add(key); typing = !typing;
            stopNativeScroll(); nativeNeedsCenter = true;
            axisX = axisY = scrollX = scrollY = 0; directions.clear(); stopRepeat();
            clickPending = false; dragging = false; gestures.clear(); deferredActions.clear(); handler.removeCallbacks(holdClick);
            pump();
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) { info.setMotionEventSources(typing ? 0 : InputDevice.SOURCE_JOYSTICK); setServiceInfo(info); }
            cursor.setTyping(typing); showCursor();
            toast(typing ? "Keyboard controls · R3 returns to pointer" : "Pointer controls");
            return true;
        }
        if (typing) return false;
        if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            consumed.add(key); directions.add(key); showCursor(); return true;
        }
        String action = key == KeyEvent.KEYCODE_DPAD_CENTER ? "Click" : prefs.action(key);
        if (action.equals("Pass through")) return false;
        scrollInterrupted = true;
        stopNativeScroll();
        consumed.add(key); showCursor(); haptic(device);
        if (action.equals("Click")) {
            clickPending = true;
            handler.postDelayed(holdClick, 400);
        } else perform(action);
        return true;
    }

    private boolean buttonUp(int key) {
        if (swallowRelease.remove(key)) return true;
        if (!consumed.remove(key)) return false;
        directions.remove(key);
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || "Click".equals(prefs.action(key))) finishClick();
        return true;
    }

    private void finishClick() {
        handler.removeCallbacks(holdClick);
        if (clickPending && enabled && !typing) {
            clickPending = false;
            enqueue(tapGesture(x, y, 50, false));
        } else { clickPending = false; dragging = false; }
    }

    private void perform(String action) {
        if (scroller.active()) {
            // Lift the virtual finger before actions can navigate to a different app/window.
            if (deferredActions.size() < 8) deferredActions.add(action);
            pump(); return;
        }
        switch (action) {
            case "Back" -> performGlobalAction(GLOBAL_ACTION_BACK);
            case "Home" -> performGlobalAction(GLOBAL_ACTION_HOME);
            case "Recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "Notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "Screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            case "Double click" -> { enqueue(tapGesture(x, y, 50, true)); }
            case "Long press" -> { enqueue(tapGesture(x, y, 650, false)); }
            case "Drag toggle" -> { dragging = !dragging; }
            case "Volume up", "Volume down" -> ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    action.equals("Volume up") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            case "Play / pause" -> {
                AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            }
            case "Brightness up", "Brightness down" -> {
                if (!Settings.System.canWrite(this)) { toast("Enable brightness access in PadPointer settings first"); return; }
                int value = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.min(255, value + (action.equals("Brightness up") ? 20 : -20))));
            }
            case "Repeat click" -> {
                if (repeat) stopRepeat();
                else { repeatX = x; repeatY = y; repeat = true; handler.post(repeater); }
                toast(repeat ? "Repeat click on · press again to stop" : "Repeat click stopped");
            }
            default -> { }
        }
    }

    private GestureDescription tapGesture(float px, float py, long duration, boolean twice) {
        Path path = new Path(); path.moveTo(px, py);
        GestureDescription.Builder builder = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        if (twice) builder.addStroke(new GestureDescription.StrokeDescription(path, duration + 100, duration));
        return onDisplay(builder).build();
    }
    private GestureDescription.Builder onDisplay(GestureDescription.Builder builder) {
        builder.setDisplayId(Display.DEFAULT_DISPLAY);
        return builder;
    }
    private void enqueue(GestureDescription gesture) {
        if (!enabled || gestures.size() >= 8) return;
        dragging = false;
        gestures.add(gesture); pump();
    }
    private boolean busy() { return inFlight > 0; }
    private void pump() {
        if (scroller.active()) { scroller.feed(); return; }
        if (busy()) return;
        if (stroke != null) { continueDrag(); return; }
        if (!enabled || typing) return;
        while (!deferredActions.isEmpty()) perform(deferredActions.remove());
        if (!gestures.isEmpty()) dispatch(gestures.remove(), null);
    }
    private void dispatch(GestureDescription gesture, GestureDescription.StrokeDescription nextStroke) {
        staleTouch = null; // a tap or drag ends any touch Firefox was still holding on to
        inFlight++;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription completed) {
                inFlight--; stroke = nextStroke;
                if (!destroyed) pump();
            }
            @Override public void onCancelled(GestureDescription cancelled) {
                inFlight--; stroke = null; dragging = false;
                if (!destroyed) pump();
            }
        }, handler);
        if (!accepted) { inFlight--; stroke = null; dragging = false; gestures.clear(); deferredActions.clear(); toast("Android could not dispatch the touch gesture"); }
    }
    private void continueDrag() {
        if (busy() || (!dragging && stroke == null)) return;
        Path path = new Path();
        GestureDescription.StrokeDescription next;
        boolean continuing = dragging && gestures.isEmpty() && enabled && !typing;
        if (stroke == null) { dragX = x; dragY = y; path.moveTo(x, y); next = new GestureDescription.StrokeDescription(path, 0, 50, continuing); }
        else {
            path.moveTo(dragX, dragY); path.lineTo(x, y);
            next = stroke.continueStroke(path, 0, 50, continuing);
            dragX = x; dragY = y;
        }
        dispatch(onDisplay(new GestureDescription.Builder().addStroke(next)).build(), continuing ? next : null);
    }

    private boolean scrollStickHeld() {
        return Float.isFinite(scrollX) && Float.isFinite(scrollY) && Math.hypot(scrollX, scrollY) > prefs.deadzone();
    }
    /** Anything that must end, or prevent, a scroll touch right now. */
    private boolean scrollBlocked() {
        return !enabled || typing || dragging || clickPending || repeat || scrollInterrupted || stroke != null
                || !gestures.isEmpty() || !deferredActions.isEmpty();
    }
    private boolean scrollRequested() { return !busy() && !scrollBlocked() && scrollStickHeld(); }
    private void scrollNative(float dt, long now) {
        if (!wheelBridge.ready() || nativeNeedsCenter) return;
        float speed = Math.min(width, height) * 1.2f * prefs.data.getInt("scroll", 100) / 100f;
        float[] delta = wheelMotion.step(scrollX, scrollY, prefs.deadzone(), speed, dt,
                wheelHorizontalFactor, wheelVerticalFactor);
        wheelBridge.scroll(x, y, delta[0], delta[1], now);
        showCursor();
    }
    private void stopNativeScroll() {
        wheelMotion.reset();
        if (wheelBridge != null) wheelBridge.stop();
    }
    private void startScroll() {
        scrollOriginX = x; scrollOriginY = y;
        ScrollMotion motion = new ScrollMotion(scrollArea(), x, y, ViewConfiguration.get(this).getScaledTouchSlop(), gestureSampleMs());
        if (isGeckoApp(scrollPackage)) motion.setGecko(geckoTolerancePx());
        scroller.start(motion);
    }
    /** Android samples injected gestures once per display frame; matching it keeps touch events evenly spaced. */
    private int gestureSampleMs() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        int ms = display == null ? 16 : (int) (1000 / display.getRefreshRate());
        return ms < 1 ? 16 : ms;
    }
    private boolean sendScroll(ScrollMotion.Segment segment, int epoch) {
        Path path = new Path(); path.moveTo(segment.fromX(), segment.fromY());
        if (segment.fromX() != segment.toX() || segment.fromY() != segment.toY()) path.lineTo(segment.toX(), segment.toY());
        GestureDescription.StrokeDescription next;
        if (segment.fresh()) next = new GestureDescription.StrokeDescription(path, 0, segment.durationMs(), segment.keepDown());
        else if (scrollStroke != null) next = scrollStroke.continueStroke(path, 0, segment.durationMs(), segment.keepDown());
        else return false;
        scrollStroke = segment.keepDown() ? next : null;
        inFlight++;
        boolean accepted = dispatchGesture(onDisplay(new GestureDescription.Builder().addStroke(next)).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription completed) { inFlight--; if (!destroyed) scroller.completed(epoch, segment); }
            @Override public void onCancelled(GestureDescription cancelled) { inFlight--; if (!destroyed) scroller.cancelled(epoch); }
        }, handler);
        if (!accepted) { inFlight--; scrollStroke = null; }
        showCursor();
        return accepted;
    }
    private ScrollMotion.Area scrollArea() {
        Rect bounds = new Rect(0, 0, (int) width, (int) height);
        scrollNode = null; scrollWindow = null; scrollKeyboard = null; scrollPackage = null; cachedScrollArea = null;
        java.util.List<AccessibilityWindowInfo> visibleWindows = getWindows();
        for (AccessibilityWindowInfo window : visibleWindows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                scrollKeyboard = new Rect(); window.getBoundsInScreen(scrollKeyboard); break;
            }
        }
        // Keep the finger inside the container under the cursor, including nested lists.
        for (AccessibilityWindowInfo window : visibleWindows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            Rect windowBounds = new Rect(); window.getBoundsInScreen(windowBounds);
            if (!windowBounds.contains((int) x, (int) y)) continue;
            scrollWindow = windowBounds;
            bounds.set(windowBounds);
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && root.getPackageName() != null) scrollPackage = root.getPackageName().toString();
            if (root != null) scrollNode = findScrollable(root, new int[]{512});
            if (scrollNode != null) {
                scrollNode.getBoundsInScreen(bounds);
                if (!bounds.intersect(windowBounds)) bounds.set(windowBounds);
            }
            break;
        }
        return areaFor(bounds);
    }
    /** Refresh in the background while queued gestures keep moving; expose only the last completed result. */
    private ScrollMotion.Area refreshScrollArea() {
        if (scrollNode != null && !scrollBoundsPending && !destroyed) {
            AccessibilityNodeInfo expected = scrollNode;
            AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(expected);
            scrollBoundsPending = true;
            scrollBoundsWorker.execute(() -> {
                Rect bounds = new Rect();
                boolean refreshed;
                try {
                    refreshed = copy.refresh();
                    if (refreshed) copy.getBoundsInScreen(bounds);
                } catch (IllegalStateException unavailable) {
                    refreshed = false;
                } finally {
                    copy.recycle();
                }
                boolean valid = refreshed && bounds.width() > 2 && bounds.height() > 2;
                handler.post(() -> {
                    scrollBoundsPending = false;
                    // A new session/app can start before the previous app answers.
                    if (!destroyed && scrollNode == expected && valid
                            && (scrollWindow == null || bounds.intersect(scrollWindow))) {
                        cachedScrollArea = areaFor(bounds);
                    }
                });
            });
        }
        return cachedScrollArea;
    }
    private ScrollMotion.Area areaFor(Rect bounds) {
        if (!bounds.intersect(0, 0, (int) width, (int) height)) bounds.set(0, 0, (int) width, (int) height);
        Rect keyboard = scrollKeyboard;
        float cx = scrollOriginX, cy = scrollOriginY;
        if (keyboard != null && !keyboard.contains((int) cx, (int) cy) && Rect.intersects(bounds, keyboard)) {
            // Keep the finger out of an overlaid keyboard, including floating keyboards.
            if (cy < keyboard.top) bounds.bottom = Math.min(bounds.bottom, keyboard.top);
            else if (cy >= keyboard.bottom) bounds.top = Math.max(bounds.top, keyboard.bottom);
            else if (cx < keyboard.left) bounds.right = Math.min(bounds.right, keyboard.left);
            else if (cx >= keyboard.right) bounds.left = Math.max(bounds.left, keyboard.right);
        }
        float insetX = Math.min(12 * getResources().getDisplayMetrics().density, Math.max(0, (bounds.width() - 2) / 4f));
        float insetY = Math.min(12 * getResources().getDisplayMetrics().density, Math.max(0, (bounds.height() - 2) / 4f));
        return new ScrollMotion.Area(bounds.left + insetX, bounds.top + insetY, bounds.right - 1 - insetX, bounds.bottom - 1 - insetY);
    }
    /**
     * Firefox and other GeckoView apps ignore the cancel Android sends when it replaces an injected finger, so they
     * need in-place re-grips (see ScrollMotion). They are recognized by GeckoView's content-process services.
     */
    private boolean isGeckoApp(String packageName) {
        if (packageName == null) return false;
        return geckoApps.computeIfAbsent(packageName, name -> {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(name, PackageManager.GET_SERVICES);
                if (info.services != null) {
                    for (ServiceInfo service : info.services) if (service.name.startsWith("org.mozilla.gecko.")) return true;
                }
            } catch (PackageManager.NameNotFoundException hidden) { /* not visible to PadPointer */ }
            return name.startsWith("org.mozilla.");
        });
    }
    /** Firefox for Android starts panning (and stops treating a touch as a tap) after 0.06 inch. */
    private int geckoTolerancePx() { return (int) Math.ceil(0.06f * getResources().getDisplayMetrics().densityDpi) + 2; }
    /** Deepest scrollable node under the cursor. */
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node, int[] budget) {
        if (budget[0]-- <= 0) return null;
        Rect candidate = new Rect(); node.getBoundsInScreen(candidate);
        if (!candidate.contains((int) x, (int) y)) return null;
        AccessibilityNodeInfo found = node.isScrollable() && candidate.width() > 2 && candidate.height() > 2 ? node : null;
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo deeper = findScrollable(child, budget);
            if (deeper != null) found = deeper;
        }
        return found;
    }

    private void stopRepeat() { repeat = false; handler.removeCallbacks(repeater); }
    private void showCursor() { lastActivity = SystemClock.uptimeMillis(); if (cursor != null) cursor.setVisibility(View.VISIBLE); }
    private void moveCursor() {
        if (cursor == null) return;
        cursorParams.x = Math.round(x - cursor.hotspotX() / 32f * cursorParams.width);
        cursorParams.y = Math.round(y - cursor.hotspotY() / 32f * cursorParams.height);
        windows.updateViewLayout(cursor, cursorParams);
    }
    private void haptic(InputDevice device) {
        if (!prefs.haptics() || device == null) return;
        Vibrator vibrator = device.getVibrator();
        if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { if (prefs != null) prefs.data.edit().putBoolean("enabled", false).apply(); }
    @Override public void onInputDeviceAdded(int deviceId) { }
    @Override public void onInputDeviceChanged(int deviceId) { }
    @Override public void onInputDeviceRemoved(int deviceId) {
        if (deviceId == lastDeviceId) {
            handler.removeCallbacks(chordTimeout); chord.reset(); swallowRelease.clear();
            prefs.data.edit().putBoolean("enabled", false).apply();
        }
    }
    @Override public void onDestroy() {
        destroyed = true; connected = false;
        stopNativeScroll();
        if (wheelBridge != null) wheelBridge.unlisten(wheelConnectionChanged);
        Choreographer.getInstance().removeFrameCallback(frame);
        scroller.abandon();
        scrollBoundsWorker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (prefs != null) prefs.data.unregisterOnSharedPreferenceChangeListener(this);
        if (inputs != null) inputs.unregisterInputDeviceListener(this);
        if (keyboardOverride && getSoftKeyboardController().getShowMode() == SHOW_MODE_IGNORE_HARD_KEYBOARD) getSoftKeyboardController().setShowMode(SHOW_MODE_AUTO);
        if (windows != null) {
            if (cursor != null) windows.removeView(cursor);
        }
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }
}
