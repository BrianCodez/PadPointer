package com.briancodez.padpointer;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScrollMotionTest {
    private static final ScrollMotion.Area AREA = new ScrollMotion.Area(20, 30, 980, 1970);
    private static final float SLOP = 21, DEAD = 0.15f, SPEED = 1300;

    private ScrollMotion gripped(float stickY) {
        ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, 16);
        motion.grip(0, stickY, DEAD, SPEED, -1, false);
        return motion;
    }

    @Test public void eachSegmentHasOneInjectorSampleSoCancellationRecoveryKnowsEveryMove() {
        int[][] cases = {{16, 16}, {11, 11}, {8, 8}, {6, 6}, {4, 4}, {33, 33}};
        for (int[] c : cases) {
            ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, c[0]);
            assertEquals(c[1], motion.segmentMs);
            assertEquals(0, motion.segmentMs % c[0]);
            assertTrue("Keep about 40 ms queued", motion.lookahead * motion.segmentMs >= ScrollMotion.LOOKAHEAD_MS);
        }
    }

    @Test public void heldStickKeepsOneFingerDownWithExactlyJoinedWholePixelSegments() {
        ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, 16);
        ScrollMotion.Segment previous = motion.grip(0, 1, DEAD, SPEED, -1, false);
        assertTrue(previous.fresh());
        for (int i = 0; i < 60; i++) {
            ScrollMotion.Segment next = motion.next(0, 1, DEAD, SPEED, true, false);
            assertNotNull(next);
            assertFalse(next.fresh()); assertTrue(next.keepDown());
            assertEquals(previous.toX(), next.fromX()); assertEquals(previous.toY(), next.fromY());
            assertTrue("Stick down drags the finger up", next.toY() < next.fromY());
            assertEquals(16, next.durationMs());
            previous = next;
        }
    }

    @Test public void gripCrossesTouchSlopInItsFirstMoveAndStartsOnTheTrailingSide() {
        ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, 16);
        ScrollMotion.Segment grip = motion.grip(0, 1, DEAD, SPEED, -1, false);
        assertTrue(grip.fresh() && grip.keepDown());
        assertTrue(grip.fromY() - grip.toY() > SLOP);
        assertTrue("Finger moving up starts low in the container", grip.fromY() > 1500);
        assertEquals("Cross-axis stays at the cursor", 500, grip.fromX());
    }

    @Test public void regripAfterCancelSendsItsMoveAtOnceAndCoversTheMissingTime() {
        ScrollMotion motion = gripped(1);
        for (int i = 0; i < 20; i++) motion.next(0, 1, DEAD, SPEED, true, false);
        motion.lost();
        assertFalse(motion.down());
        ScrollMotion.Segment urgent = motion.grip(0, 1, DEAD, SPEED, 10, true);
        assertEquals(1, urgent.durationMs());
        float beyondSlop = urgent.fromY() - urgent.toY() - SLOP;
        // About 11 ms of motion at roughly full speed: no jump, no lost distance.
        assertTrue(beyondSlop > 8 && beyondSlop < 20);
    }

    @Test public void edgeRegripKeepsTheEventCadence() {
        ScrollMotion motion = gripped(1);
        ScrollMotion.Segment regrip = motion.grip(0, 1, DEAD, SPEED, 5, false);
        assertEquals("Next move lands one segment after the previous finger's last move", 11, regrip.durationMs());
    }

    @Test public void reachingTheContainerEdgeAsksForARegripInsteadOfLeaving() {
        ScrollMotion motion = new ScrollMotion(new ScrollMotion.Area(100, 200, 300, 500), 220, 300, SLOP, 16);
        motion.grip(0, 1, DEAD, SPEED, -1, false);
        ScrollMotion.Segment next;
        int moves = 0;
        while ((next = motion.next(0, 1, DEAD, SPEED, true, false)) != null) {
            assertTrue(next.toY() >= 200 && next.toY() <= 500);
            assertTrue(next.keepDown());
            assertTrue("Must hit the edge", ++moves < 100);
        }
        assertTrue("Finger stays down until the re-grip replaces it", motion.down());
        ScrollMotion.Segment grip = motion.grip(0, 1, DEAD, SPEED, 2, false);
        assertTrue(grip.fresh() && grip.fromY() > 400);
    }

    @Test public void neutralStickEasesOutThenLiftsAfterAStationaryHold() {
        ScrollMotion motion = gripped(1);
        for (int i = 0; i < 20; i++) motion.next(0, 1, DEAD, SPEED, true, false);
        ScrollMotion.Segment last = null, next;
        int easing = 0;
        while ((next = motion.next(0, 0, DEAD, SPEED, false, false)).keepDown()) {
            if (last != null) assertTrue("Slows down", last.fromY() - last.toY() + 1 >= next.fromY() - next.toY());
            last = next; easing++;
        }
        assertTrue("Short ease-out", easing > 0 && easing < 10);
        assertEquals(next.fromY(), next.toY());
        assertEquals(ScrollMotion.STOP_HOLD_MS, next.durationMs());
        assertTrue(motion.ended());
    }

    @Test public void blockedScrollingStopsAtOnce() {
        ScrollMotion motion = gripped(1);
        for (int i = 0; i < 20; i++) motion.next(0, 1, DEAD, SPEED, true, false);
        ScrollMotion.Segment lift = motion.next(0, 1, DEAD, SPEED, false, true);
        assertFalse(lift.keepDown());
        assertEquals(lift.fromY(), lift.toY());
    }

    @Test public void reversingTheStickReversesImmediately() {
        ScrollMotion motion = gripped(1);
        for (int i = 0; i < 5; i++) motion.next(0, 1, DEAD, SPEED, true, false);
        ScrollMotion.Segment reverse = motion.next(0, -1, DEAD, SPEED, true, false);
        assertTrue(reverse.toY() > reverse.fromY());
    }

    @Test public void partialDeflectionIsSlowerThanFullDeflection() {
        ScrollMotion slow = gripped(0.4f), fast = gripped(1);
        int slowDistance = 0, fastDistance = 0;
        for (int i = 0; i < 30; i++) {
            ScrollMotion.Segment a = slow.next(0, 0.4f, DEAD, SPEED, true, false);
            slowDistance += a.fromY() - a.toY();
            ScrollMotion.Segment b = fast.next(0, 1, DEAD, SPEED, true, false);
            fastDistance += b.fromY() - b.toY();
        }
        assertTrue(slowDistance > 0 && fastDistance > slowDistance);
    }

    @Test public void slowestScrollNeverSendsAnEmptyContinuedSegment() {
        // The injector reports a continued segment with no movement as a failure.
        ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, 16);
        motion.grip(0, 0.151f, DEAD, 40, -1, false);
        for (int i = 0; i < 200; i++) {
            ScrollMotion.Segment next = motion.next(0, 0.151f, DEAD, 40, true, false);
            if (next == null) break;
            assertTrue(next.fromX() != next.toX() || next.fromY() != next.toY());
        }
    }

    @Test public void firefoxRegripStartsExactlyWhereFirefoxLastSawTheFinger() {
        ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, 16);
        motion.setGecko(28);
        ScrollMotion.Segment grip = motion.gripInPlace(500, 900, 0, 1, DEAD, SPEED, 10);
        assertTrue(grip.fresh());
        assertEquals(500, grip.fromX()); assertEquals(900, grip.fromY());
        int moved = grip.fromY() - grip.toY();
        assertTrue("No slop jump, just the missed motion: " + moved, moved > 0 && moved < 20);
    }

    @Test public void firefoxFingerAtTheEdgeIsLiftedWhileMovingThenRegrippedPastItsTolerance() {
        ScrollMotion motion = new ScrollMotion(new ScrollMotion.Area(100, 200, 300, 500), 220, 300, SLOP, 16);
        motion.setGecko(28);
        motion.grip(0, 1, DEAD, SPEED, -1, false);
        ScrollMotion.Segment next;
        int moves = 0;
        do { next = motion.next(0, 1, DEAD, SPEED, true, false); assertNotNull("Never left for a cancel", next); assertTrue(++moves < 100); }
        while (next.keepDown());
        assertTrue("Lifted while moving", next.toY() < next.fromY());
        assertFalse(motion.down()); assertFalse("Not the end of scrolling", motion.ended());
        ScrollMotion.Segment grip = motion.grip(0, 1, DEAD, SPEED, 2, false);
        assertTrue(grip.fresh() && grip.fromY() - grip.toY() > 28);
    }

    @Test public void firefoxFingerIsNeverLiftedBeforeItPassesTheTapTolerance() {
        ScrollMotion motion = new ScrollMotion(AREA, 500, 1000, SLOP, 16);
        motion.setGecko(28);
        ScrollMotion.Segment grip = motion.gripInPlace(500, 900, 0, 1, DEAD, SPEED, 2);
        ScrollMotion.Segment topUp = motion.next(0, 1, DEAD, SPEED, false, true); // a click is waiting
        assertTrue(topUp.keepDown());
        assertTrue("Moves past Firefox's tolerance first", grip.fromY() - topUp.toY() > 28);
        ScrollMotion.Segment lift = motion.next(0, 1, DEAD, SPEED, false, true);
        assertFalse(lift.keepDown());
        assertEquals(lift.fromY(), lift.toY());
    }

    @Test public void firefoxInterruptedNearAnEdgeFinishesDraggingInsideTheContainerBeforeLifting() {
        for (int sample : new int[]{16, 11, 8, 6}) {
            for (int direction : new int[]{-1, 1}) {
                ScrollMotion.Area small = new ScrollMotion.Area(100, 200, 400, 600);
                ScrollMotion motion = new ScrollMotion(small, 250, 400, SLOP, sample);
                motion.setGecko(28);
                int start = direction > 0 ? 235 : 565;
                motion.gripInPlace(250, start, 0, direction, DEAD, SPEED, 2);
                ScrollMotion.Segment end;
                int count = 0;
                do {
                    end = motion.next(0, direction, DEAD, SPEED, true, false);
                    assertNotNull(end);
                    assertTrue("Finish within the container", small.contains(end.toX(), end.toY()));
                    assertTrue(++count < 20);
                } while (end.keepDown());
                assertTrue("The lift must not be recognized as a tap", Math.abs(end.toY() - start) > 28);
            }
        }
    }

    @Test public void noiseAndInvalidAxesStopScrolling() {
        float[][] inputs = {{0.08f, 0.08f}, {Float.NaN, 1}, {1, Float.POSITIVE_INFINITY}};
        for (float[] input : inputs) {
            ScrollMotion motion = gripped(1);
            int segments = 0;
            while (motion.next(input[0], input[1], DEAD, SPEED, true, false).keepDown()) assertTrue(++segments < 10);
            assertTrue(motion.ended());
        }
    }
}
