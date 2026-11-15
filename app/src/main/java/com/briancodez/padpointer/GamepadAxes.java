package com.briancodez.padpointer;

import android.view.MotionEvent;

/** One axis mapping shared by actual control and the live input tester. */
final class GamepadAxes {
    private GamepadAxes() {}
    static float rightX(MotionEvent event) {
        return larger(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RX));
    }
    static float rightY(MotionEvent event) {
        return larger(event.getAxisValue(MotionEvent.AXIS_RZ), event.getAxisValue(MotionEvent.AXIS_RY));
    }
    private static float larger(float a, float b) { return Math.abs(a) >= Math.abs(b) ? a : b; }
}
