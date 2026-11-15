package com.briancodez.padpointer;

import org.junit.Test;
import static org.junit.Assert.*;

public class PointerMathTest {
    @Test public void stickNoiseDoesNotMoveThePointer() {
        assertArrayEquals(new float[]{0, 0}, PointerMath.velocity(0.08f, -0.08f, 0.15f, 1.6f), 0.0001f);
    }
    @Test public void diagonalMotionDoesNotExceedFullSpeed() {
        float[] v = PointerMath.velocity(1, 1, 0.15f, 1.6f);
        assertEquals(1, Math.hypot(v[0], v[1]), 0.0001);
        assertEquals(v[0], v[1], 0.0001);
    }
    @Test public void fineMotionIsSlowerWithAcceleration() {
        assertTrue(PointerMath.velocity(0.5f, 0, 0.15f, 2f)[0] < PointerMath.velocity(0.5f, 0, 0.15f, 1f)[0]);
    }
    @Test public void invalidControllerDataDoesNotEscapeIntoCoordinates() {
        assertArrayEquals(new float[]{0, 0}, PointerMath.velocity(Float.NaN, 0, 0.15f, 1.6f), 0.0001f);
        assertArrayEquals(new float[]{0, 0}, PointerMath.velocity(0, Float.POSITIVE_INFINITY, 0.15f, 1.6f), 0.0001f);
    }
    @Test public void pointerStaysInsideTouchscreenBounds() {
        assertEquals(0, PointerMath.clamp(-50, 1080), 0);
        assertEquals(1079, PointerMath.clamp(1200, 1080), 0);
        assertEquals(0, PointerMath.clamp(12, 0), 0);
    }
}
