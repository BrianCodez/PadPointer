package com.briancodez.padpointer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashSet<Integer> consumed = new HashSet<>();
    private final HashSet<Integer> directions = new HashSet<>();
    private final ArrayDeque<GestureDescription> gestures = new ArrayDeque<>();
    private final ArrayDeque<String> deferredActions = new ArrayDeque<>();
    private Prefs prefs;
    private WindowManager windows;
    private CursorView cursor;
    private WindowManager.LayoutParams cursorParams;
    private InputManager inputs;
    private float x, y, width, height, axisX, axisY, scrollX, scrollY;
    private float dragX, dragY, repeatX, repeatY;
    private boolean enabled, typing, busy, dragging, clickPending, repeat, keyboardOverride;
    private boolean destroyed;
    private int lastDeviceId = -1;
    private long lastFrame, lastActivity;
    private GestureDescription.StrokeDescription stroke;
    private boolean scrolling;
    private boolean scrollInterrupted;
    private ScrollMotion scrollMotion;

    private final Runnable holdClick = () -> { if (clickPending) { clickPending = false; dragging = true; } };
    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (!enabled || !repeat || typing) return;
            if (!busy && stroke == null && gestures.isEmpty()) enqueue(tapGesture(repeatX, repeatY, 50, false));
            handler.postDelayed(this, prefs.repeatMs());
        }
    };
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
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
                if (!busy) {
                    if (stroke != null) { if (scrolling) continueScroll(); else continueDrag(); }
                    else if (!gestures.isEmpty()) pump();
                    else if (dragging) continueDrag();
                    else if (scrollRequested()) startScroll();
                }
                if (prefs.timeout() > 0 && now - lastActivity > prefs.timeout() * 1000L && !dragging && !scrolling && !repeat) cursor.setVisibility(View.INVISIBLE);
            }
            handler.postDelayed(this, 16);
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { prefs.data.edit().putBoolean("enabled", false).apply(); }
    };

    @Override protected void onServiceConnected() {
        connected = true;
        prefs = new Prefs(this);
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
        lastFrame = SystemClock.uptimeMillis();
        handler.post(frame);
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
        updateBounds(); moveCursor();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences data, String key) { applyPreferences(); }

    private void applyPreferences() {
        boolean wasEnabled = enabled;
        enabled = prefs.enabled();
        if (!enabled || !wasEnabled) {
            axisX = axisY = scrollX = scrollY = 0;
            directions.clear(); gestures.clear(); deferredActions.clear();
            clickPending = false; dragging = false; typing = false;
            stopRepeat(); handler.removeCallbacks(holdClick);
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.setMotionEventSources(enabled && !typing ? InputDevice.SOURCE_JOYSTICK : 0);
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
        toast(enabled ? "Pointer on · R3 passes controls to the keyboard" : "Pointer off");
    }

    @Override public void onMotionEvent(MotionEvent event) { motion(event); }

    private boolean motion(MotionEvent event) {
        if (!enabled || typing || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
        lastDeviceId = event.getDeviceId();
        axisX = event.getAxisValue(MotionEvent.AXIS_X); axisY = event.getAxisValue(MotionEvent.AXIS_Y);
        scrollX = GamepadAxes.rightX(event);
        scrollY = GamepadAxes.rightY(event);
        if (Math.hypot(scrollX, scrollY) <= prefs.deadzone()) scrollInterrupted = false;
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
        if (!controller) return false;
        lastDeviceId = event.getDeviceId();
        if (event.getAction() == KeyEvent.ACTION_UP && consumed.remove(key)) {
            directions.remove(key);
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || "Click".equals(prefs.action(key))) finishClick();
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0) return consumed.contains(key);
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            consumed.add(key);
            toggle();
            return true;
        }
        if (!enabled) return false;
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            consumed.add(key); typing = !typing;
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
        consumed.add(key); showCursor(); haptic(event.getDevice());
        if (action.equals("Click")) {
            clickPending = true;
            handler.postDelayed(holdClick, 400);
        } else perform(action);
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
        if (scrolling) {
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
    private void pump() {
        if (busy) return;
        if (stroke != null) { if (scrolling) continueScroll(); else continueDrag(); return; }
        if (!enabled || typing) return;
        while (!deferredActions.isEmpty()) perform(deferredActions.remove());
        if (!gestures.isEmpty()) dispatch(gestures.remove(), null);
    }
    private void dispatch(GestureDescription gesture, GestureDescription.StrokeDescription nextStroke) {
        busy = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription completed) {
                busy = false; stroke = nextStroke;
                if (nextStroke == null) { scrolling = false; scrollMotion = null; }
                if (!destroyed) pump();
            }
            @Override public void onCancelled(GestureDescription cancelled) {
                if (scrolling) scrollX = scrollY = 0;
                busy = false; stroke = null; dragging = false; scrolling = false; scrollMotion = null;
                if (!destroyed) pump();
            }
        }, handler);
        if (!accepted) { busy = false; stroke = null; dragging = false; scrolling = false; scrollMotion = null; gestures.clear(); deferredActions.clear(); toast("Android could not dispatch the touch gesture"); }
    }
    private void continueDrag() {
        if (busy || (!dragging && stroke == null)) return;
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
    private boolean scrollRequested() {
        return enabled && !typing && !dragging && !clickPending && !repeat && !scrollInterrupted && gestures.isEmpty() && deferredActions.isEmpty()
                && Float.isFinite(scrollX) && Float.isFinite(scrollY) && Math.hypot(scrollX, scrollY) > prefs.deadzone();
    }
    private void startScroll() {
        scrolling = true;
        scrollMotion = new ScrollMotion(scrollArea(), x, y, scrollX, scrollY, ViewConfiguration.get(this).getScaledTouchSlop());
        continueScroll();
    }
    private void continueScroll() {
        if (busy || scrollMotion == null) return;
        float speed = Math.min(width, height) * 1.2f * prefs.data.getInt("scroll", 100) / 100f;
        ScrollMotion.Segment segment = scrollMotion.next(scrollX, scrollY, prefs.deadzone(), speed, scrollRequested());
        Path path = new Path(); path.moveTo(segment.fromX(), segment.fromY());
        if (segment.fromX() != segment.toX() || segment.fromY() != segment.toY()) path.lineTo(segment.toX(), segment.toY());
        GestureDescription.StrokeDescription next = stroke == null
                ? new GestureDescription.StrokeDescription(path, 0, segment.durationMs(), segment.keepDown())
                : stroke.continueStroke(path, 0, segment.durationMs(), segment.keepDown());
        dispatch(onDisplay(new GestureDescription.Builder().addStroke(next)).build(), segment.keepDown() ? next : null);
        showCursor();
    }
    private ScrollMotion.Area scrollArea() {
        Rect bounds = new Rect(0, 0, (int) width, (int) height);
        Rect keyboard = null;
        java.util.List<AccessibilityWindowInfo> visibleWindows = getWindows();
        for (AccessibilityWindowInfo window : visibleWindows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                keyboard = new Rect(); window.getBoundsInScreen(keyboard); break;
            }
        }
        // Reposition a continued swipe only inside the container under the cursor, including nested lists.
        for (AccessibilityWindowInfo window : visibleWindows) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            Rect windowBounds = new Rect(); window.getBoundsInScreen(windowBounds);
            if (!windowBounds.contains((int) x, (int) y)) continue;
            bounds.set(windowBounds);
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                try { findScrollBounds(root, bounds, new int[]{512}); }
                finally { root.recycle(); }
            }
            if (!bounds.intersect(windowBounds)) bounds.set(windowBounds);
            break;
        }
        if (!bounds.intersect(0, 0, (int) width, (int) height)) bounds.set(0, 0, (int) width, (int) height);
        if (keyboard != null && !keyboard.contains((int) x, (int) y) && Rect.intersects(bounds, keyboard)) {
            // Keep scroll re-anchoring out of an overlaid keyboard, including floating keyboards.
            if (y < keyboard.top) bounds.bottom = Math.min(bounds.bottom, keyboard.top);
            else if (y >= keyboard.bottom) bounds.top = Math.max(bounds.top, keyboard.bottom);
            else if (x < keyboard.left) bounds.right = Math.min(bounds.right, keyboard.left);
            else if (x >= keyboard.right) bounds.left = Math.max(bounds.left, keyboard.right);
        }
        float insetX = Math.min(12 * getResources().getDisplayMetrics().density, Math.max(0, (bounds.width() - 2) / 4f));
        float insetY = Math.min(12 * getResources().getDisplayMetrics().density, Math.max(0, (bounds.height() - 2) / 4f));
        return new ScrollMotion.Area(bounds.left + insetX, bounds.top + insetY, bounds.right - 1 - insetX, bounds.bottom - 1 - insetY);
    }
    private void findScrollBounds(AccessibilityNodeInfo node, Rect result, int[] budget) {
        if (budget[0]-- <= 0) return;
        Rect candidate = new Rect(); node.getBoundsInScreen(candidate);
        if (!candidate.contains((int) x, (int) y)) return;
        if (node.isScrollable() && candidate.width() > 2 && candidate.height() > 2) result.set(candidate);
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { findScrollBounds(child, result, budget); }
            finally { child.recycle(); }
        }
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
        if (deviceId == lastDeviceId) prefs.data.edit().putBoolean("enabled", false).apply();
    }
    @Override public void onDestroy() {
        destroyed = true; connected = false;
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
