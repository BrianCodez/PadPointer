package com.briancodez.padpointer;

import org.junit.Test;
import static org.junit.Assert.*;

public class WheelMotionTest {
    @Test public void speedDoesNotDependOnDisplayRefreshRate() {
        for (int hz : new int[]{60, 90, 120, 144}) {
            WheelMotion motion = new WheelMotion();
            double units = 0;
            for (int i = 0; i < hz * 6; i++) units += motion.step(0, 1, .15f, 1200, 1f / hz, 64, 64)[1];
            assertEquals("Six seconds minus a short acceleration ramp at " + hz, -112, units, 1);
        }
    }

    @Test public void slowScrollingKeepsFractionalMovementInsteadOfOnePixelJumps() {
        WheelMotion motion = new WheelMotion();
        double units = 0;
        for (int i = 0; i < 1200; i++) {
            float[] d = motion.step(0, .3f, .15f, 300, 1f / 120, 64, 64);
            assertTrue(d[1] < 0 && Math.abs(d[1]) < 1f / 64);
            units += d[1];
        }
        assertTrue(units < -1 && units > -10);
    }

    @Test public void neutralAndInvalidInputStopImmediately() {
        for (float[] input : new float[][]{{0, 0}, {.05f, .05f}, {Float.NaN, 1}, {1, Float.POSITIVE_INFINITY}}) {
            WheelMotion motion = new WheelMotion();
            for (int i = 0; i < 50; i++) motion.step(0, 1, .15f, 1200, .016f, 64, 64);
            assertArrayEquals(new float[]{0, 0}, motion.step(input[0], input[1], .15f, 1200, .016f, 64, 64), 0);
        }
    }

    @Test public void directionChangesNeverKeepScrollingInTheOldDirection() {
        WheelMotion motion = new WheelMotion();
        for (int i = 0; i < 40; i++) motion.step(0, 1, .15f, 1200, .016f, 64, 64);
        assertTrue(motion.step(0, -1, .15f, 1200, .016f, 64, 64)[1] > 0);
        assertTrue(motion.step(1, 0, .15f, 1200, .016f, 64, 64)[0] > 0);
    }

    @Test public void stallDoesNotCreateACatchUpJump() {
        WheelMotion motion = new WheelMotion();
        for (int i = 0; i < 100; i++) motion.step(0, 1, .15f, 1200, .016f, 64, 64);
        assertTrue(Math.abs(motion.step(0, 1, .15f, 1200, 2, 64, 64)[1]) <= 1200 * .032f / 64);
    }

    @Test public void resetClearsSpeedBeforeAnotherSession() {
        WheelMotion motion = new WheelMotion();
        for (int i = 0; i < 100; i++) motion.step(0, 1, .15f, 1200, .016f, 64, 64);
        motion.reset();
        assertArrayEquals(new WheelMotion().step(0, 1, .15f, 1200, .016f, 64, 64),
                motion.step(0, 1, .15f, 1200, .016f, 64, 64), 0);
    }

    @Test public void badTimingOrScaleNeverProducesInvalidWheelEvents() {
        WheelMotion motion = new WheelMotion();
        assertArrayEquals(new float[]{0, 0}, motion.step(0, 1, .15f, 1200, Float.NaN, 64, 64), 0);
        assertArrayEquals(new float[]{0, 0}, motion.step(0, 1, .15f, 1200, .016f, 0, 64), 0);
    }
}
