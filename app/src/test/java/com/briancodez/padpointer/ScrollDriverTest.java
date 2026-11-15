package com.briancodez.padpointer;

import org.junit.Test;
import static org.junit.Assert.*;

/** Holds the right stick against a model of Android's gesture injector and checks what the app would render. */
public class ScrollDriverTest {
    private static final double HOLD_FROM = 300, RELEASE = 6000;

    private record Stats(int frames, int still, int longestStill, double mean, double cv) {
        @Override public String toString() {
            return String.format("frames=%d still=%d longestStill=%d mean=%.2fpx cv=%.3f", frames, still, longestStill, mean, cv);
        }
    }

    private static Stats stats(double[] deltas) {
        int still = 0, run = 0, longest = 0;
        double sum = 0, squares = 0;
        for (double d : deltas) {
            if (d <= 0) { still++; longest = Math.max(longest, ++run); } else run = 0;
            sum += d; squares += d * d;
        }
        double mean = sum / deltas.length;
        double cv = Math.sqrt(Math.max(0, squares / deltas.length - mean * mean)) / mean;
        return new Stats(deltas.length, still, longest, mean, cv);
    }

    /** Push the stick fully down at t=0, hold, release at RELEASE, and let everything settle. */
    private static Stats hold(InjectorSimulation sim, String label) {
        sim.stick(0, 0, 1);
        sim.startScroll(5, 540, 1200);
        sim.run(RELEASE);
        Stats held = stats(sim.deltas(HOLD_FROM, RELEASE));
        sim.stick(RELEASE, 0, 0);
        sim.run(RELEASE + 800);
        System.out.println(label + ": " + held + " grips=" + sim.driver.grips() + " downs=" + sim.downs + " cancels=" + sim.cancels
                + " ups=" + sim.ups + " flings=" + sim.flings + " rejected=" + sim.rejectedContinuations);
        assertFalse("Scrolling ends after release", sim.driver.active());
        if (!sim.firefox) assertEquals("No fling after release", 0, sim.flings); // Firefox edges lift while moving
        assertEquals("No empty segments", 0, sim.emptySegments);
        assertTrue("Content stops promptly after release", sim.lastMoveTime < RELEASE + 200);
        assertEquals(0, sim.driver.inFlight());
        return held;
    }

    private static double target(InjectorSimulation sim) { return sim.speed * sim.frameMs / 1000; }

    @Test public void heldStickScrollsEveryFrameAtSteadySpeedWhenControllerEventsDoNotCancel() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventsCancelTouches = false; // Android 16 with the injector fix, or a perfectly quiet stick
        Stats held = hold(sim, "60 Hz, no cancels");
        assertEquals("Never a frame without movement", 0, held.still);
        assertEquals(target(sim), held.mean, target(sim) * 0.05);
        assertTrue("Even speed", held.cv < 0.1);
        assertEquals("Every container edge was crossed with a re-grip, not a lift", 0, sim.rejectedContinuations);
        assertTrue("Several edges crossed in 6 s", sim.driver.grips() >= 4);
        assertEquals("Each re-grip cancels the previous finger", sim.driver.grips() - 1, sim.cancels);
        assertEquals("Only one real lift, at release", 1, sim.ups);
    }

    @Test public void noisyControllerOnAndroid14StillScrollsEveryFrame() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventEveryMs = 8; // 125 Hz controller reports, each cancelling the touch
        Stats held = hold(sim, "60 Hz, controller event every 8 ms");
        assertTrue("Hundreds of cancellations happened", sim.cancels > 300);
        assertTrue("Almost every frame moves: " + held, held.still <= held.frames * 0.03);
        assertTrue("Never two still frames in a row: " + held, held.longestStill <= 1);
        assertEquals(target(sim), held.mean, target(sim) * 0.05);
    }

    @Test public void occasionalControllerEventsOnAndroid14DoNotInterruptScrolling() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventEveryMs = 120;
        Stats held = hold(sim, "60 Hz, controller event every 120 ms");
        assertTrue(sim.cancels > 20);
        assertTrue("" + held, held.still <= 2 && held.longestStill <= 1);
        assertEquals(target(sim), held.mean, target(sim) * 0.08);
    }

    @Test public void highRefreshDisplayWithNoisyController() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.frameMs = 1000 / 120.0; sim.sampleMs = 8;
        sim.stickEventEveryMs = 8;
        Stats held = hold(sim, "120 Hz, controller event every 8 ms");
        assertTrue("" + held, held.still <= held.frames * 0.06 && held.longestStill <= 1);
        assertEquals(target(sim), held.mean, target(sim) * 0.08);
    }

    @Test public void mainThreadStallsDoNotOpenGapsInTheTouch() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventsCancelTouches = false;
        sim.stallEveryMs = 400; sim.stallMs = 25;
        Stats held = hold(sim, "60 Hz, 25 ms service stall every 400 ms");
        assertEquals("" + held, 0, held.still);
    }

    @Test public void boundsRefreshesAreRequestedWhileTheFingerIsMovingBeforeAnEdge() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventsCancelTouches = false;
        sim.stick(0, 0, 1);
        sim.startScroll(5, 540, 1200);
        sim.run(1000);
        assertEquals("The original finger is still moving", 1, sim.downs);
        assertEquals("Refresh proactively without waiting for a re-grip", 3, sim.areaRefreshes);
        assertEquals("Refreshing does not lift the finger", 0, sim.ups);
    }

    @Test public void clickWhileScrollingStopsTheContentWithoutAFling() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventsCancelTouches = false;
        sim.stick(0, 0, 1);
        sim.startScroll(5, 540, 1200);
        sim.run(2000);
        sim.blockedFlag = true;
        sim.run(2400);
        assertFalse(sim.driver.active());
        assertEquals(1, sim.endedCount);
        assertEquals(1, sim.ups);
        assertEquals(0, sim.flings);
        assertTrue("Stops within the queued lookahead", sim.lastMoveTime < 2000 + 60);
    }

    @Test public void cancellationWithoutControllerInputIsNotFought() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventsCancelTouches = false;
        sim.stick(0, 0, 1);
        sim.startScroll(5, 540, 1200);
        sim.run(2000);
        int grips = sim.driver.grips();
        sim.realFingerTouch(2000);
        sim.run(2500);
        assertFalse("A real touch took over", sim.driver.active());
        assertTrue(sim.lastEndForgotStick);
        assertEquals("No new finger fights the real one", grips, sim.driver.grips());
    }

    @Test public void firefoxWithout0_2_5HandlingJumpsBackAtEveryEdge() {
        // What 0.2.4 did on the Android 15 emulator: each new finger made Firefox jump back by a finger's travel.
        InjectorSimulation sim = new InjectorSimulation();
        sim.firefox = true; sim.plannerKnowsFirefox = false; sim.stickEventsCancelTouches = false;
        sim.stick(0, 0, 1);
        sim.startScroll(5, 540, 1200);
        sim.run(6000);
        assertTrue("backward " + sim.backwardPx, sim.backwardPx > 3000);
    }

    @Test public void firefoxHeldStickScrollsWithoutJumpsOrTaps() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.firefox = true; sim.stickEventsCancelTouches = false;
        Stats held = hold(sim, "Firefox, no cancels");
        assertTrue("backward " + sim.backwardPx, sim.backwardPx < 3);
        assertEquals("Nothing on the page was tapped", 0, sim.taps);
        assertTrue("Container edges were crossed", sim.driver.grips() >= 4);
        assertTrue("" + held, held.longestStill <= 3);
        assertEquals(target(sim), held.mean, target(sim) * 0.12);
    }

    @Test public void firefoxWithControllerEventsKeepsPanningInPlace() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.firefox = true;
        sim.stickEventEveryMs = 60;
        Stats held = hold(sim, "Firefox, controller event every 60 ms");
        assertTrue("Many cancels that Firefox never saw", sim.cancels > 50);
        assertTrue("backward " + sim.backwardPx, sim.backwardPx < 3);
        assertEquals(0, sim.taps);
        assertTrue("" + held, held.longestStill <= 2);
        assertEquals(target(sim), held.mean, target(sim) * 0.12);
    }

    @Test public void firefoxTouchLeftHangingByAReleaseDoesNotJumpTheNextScroll() {
        // On Android 14-15 letting go of the stick sends a controller event, which cancels the finger; Firefox
        // ignores that cancel and still believes the finger is down where it last was.
        for (boolean fixed : new boolean[]{false, true}) {
            InjectorSimulation sim = new InjectorSimulation();
            sim.firefox = true; sim.plannerKnowsFirefox = fixed;
            sim.stick(0, 0, 1);
            sim.startScroll(5, 540, 1200);
            sim.run(900);
            sim.stick(900, 0, 0);
            sim.run(1500);
            assertFalse(sim.driver.active());
            double before = sim.backwardPx;
            sim.stick(1500, 0, 1);
            sim.startScroll(1505, 540, 1200);
            sim.run(2500);
            if (fixed) {
                assertTrue("backward " + (sim.backwardPx - before), sim.backwardPx - before < 3);
                assertEquals(0, sim.taps);
            } else assertTrue("0.2.4 jumped back here", sim.backwardPx - before > 500);
        }
    }

    @Test public void firefoxHighRefreshInterruptionsDoNotRetraceUnreportedMoves() {
        for (int hz : new int[]{120, 144, 90}) {
            InjectorSimulation sim = new InjectorSimulation();
            sim.firefox = true;
            sim.frameMs = 1000.0 / hz; sim.sampleMs = (int) sim.frameMs;
            sim.stickEventEveryMs = 60;
            Stats held = hold(sim, "Firefox " + hz + " Hz, controller event every 60 ms");
            assertTrue("No backwards correction at " + hz + " Hz: " + sim.backwardPx, sim.backwardPx < 3);
            assertEquals("No unintended taps", 0, sim.taps);
            assertEquals(target(sim), held.mean, target(sim) * 0.12);
            assertTrue("No long pauses: " + held, held.longestStill <= 2);
        }
    }

    @Test public void stickReleasedDuringRegripStopsCleanly() {
        InjectorSimulation sim = new InjectorSimulation();
        sim.stickEventEveryMs = 8;
        sim.stick(0, 0, 1);
        sim.startScroll(5, 540, 1200);
        sim.run(1503);
        sim.stick(1503, 0, 0);
        sim.run(2500);
        assertFalse(sim.driver.active());
        assertEquals(0, sim.flings);
        assertEquals(0, sim.driver.inFlight());
    }
}
