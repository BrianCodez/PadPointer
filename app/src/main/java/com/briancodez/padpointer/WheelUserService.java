package com.briancodez.padpointer;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Loaded by Shizuku in its shell/root user-service process, where hidden APIs are available. */
public final class WheelUserService extends IWheelService.Stub {
    private Object inputManager;
    private Method inject;
    public WheelUserService() { }

    @Override public synchronized String initialize() {
        try {
            // Android 14+ shell input uses InputManagerGlobal. InputManager.getInstance() on
            // Android 15 requires ActivityThread.currentApplication(), absent in a user service.
            Class<?> manager = Class.forName("android.hardware.input.InputManagerGlobal");
            inputManager = manager.getDeclaredMethod("getInstance").invoke(null);
            inject = manager.getMethod("injectInputEvent", InputEvent.class, int.class);
            return "";
        } catch (ReflectiveOperationException | RuntimeException e) {
            inputManager = null; inject = null;
            return "Direct input is unavailable on this Android build (" + e.getClass().getSimpleName() + ").";
        }
    }

    @Override public synchronized boolean scroll(float x, float y, float horizontal, float vertical, long eventTime) {
        long now = SystemClock.uptimeMillis();
        if (eventTime > now || now - eventTime > LatestWheelFrame.MAX_AGE_MS) return true; // discard stale work
        if (inject == null || !Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0
                || !Float.isFinite(horizontal) || !Float.isFinite(vertical)) return false;
        if (horizontal == 0 && vertical == 0) return true;
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0; properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x; coords.y = y;
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, Math.max(-4, Math.min(4, horizontal)));
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, Math.max(-4, Math.min(4, vertical)));
        MotionEvent event = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            // WAIT_FOR_RESULT reports permission/target rejection. This runs off the app's UI thread.
            return Boolean.TRUE.equals(inject.invoke(inputManager, event, 1));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        } finally { event.recycle(); }
    }

    @Override public void destroy() { System.exit(0); }
}
