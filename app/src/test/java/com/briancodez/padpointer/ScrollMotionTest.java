package com.briancodez.padpointer;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScrollMotionTest {
    private ScrollMotion motion(float x, float y) {
        return new ScrollMotion(new ScrollMotion.Area(20, 30, 980, 1970), 500, 1000, x, y, 12);
    }
    @Test public void heldStickKeepsTheFingerDownAndJoinsEverySegment() {
        ScrollMotion motion = motion(0, 1);
        ScrollMotion.Segment previous = motion.next(0, 1, 0.15f, 1000, true);
        for (int i = 0; i < 20; i++) {
            ScrollMotion.Segment next = motion.next(0, 1, 0.15f, 1000, true);
            assertTrue(previous.keepDown()); assertTrue(next.keepDown());
            assertEquals(previous.toX(), next.fromX(), 0);
            assertEquals(previous.toY(), next.fromY(), 0);
            assertTrue(next.toY() < next.fromY());
            previous = next;
        }
    }
    @Test public void neutralStickStopsMotionAndReleasesWithoutAnotherFlick() {
        ScrollMotion motion = motion(0, 1);
        ScrollMotion.Segment moving = motion.next(0, 1, 0.15f, 1000, true);
        ScrollMotion.Segment stopped = motion.next(0, 0, 0.15f, 1000, true);
        assertFalse(stopped.keepDown());
        assertEquals(moving.toY(), stopped.fromY(), 0);
        assertEquals(stopped.fromY(), stopped.toY(), 0);
        assertEquals(ScrollMotion.RELEASE_MS, stopped.durationMs());
    }
    @Test public void tapNavigationAndDisableRequestsReleaseTheCurrentTouch() {
        ScrollMotion motion = motion(0, 1);
        motion.next(0, 1, 0.15f, 1000, true);
        ScrollMotion.Segment interrupted = motion.next(0, 1, 0.15f, 1000, false);
        assertFalse(interrupted.keepDown());
        assertEquals(interrupted.fromY(), interrupted.toY(), 0);
    }
    @Test public void reversingTheStickDoesNotContinueInTheOldDirection() {
        ScrollMotion motion = motion(0, 1);
        for (int i = 0; i < 5; i++) motion.next(0, 1, 0.15f, 1000, true);
        ScrollMotion.Segment reverse = motion.next(0, -1, 0.15f, 1000, true);
        assertTrue(reverse.toY() > reverse.fromY());
    }
    @Test public void nestedContainerBoundsAreNeverCrossedAndTheEdgeSettles() {
        ScrollMotion motion = new ScrollMotion(new ScrollMotion.Area(100, 200, 300, 500), 220, 300, 0, 1, 12);
        boolean released = false;
        for (int i = 0; i < 100; i++) {
            ScrollMotion.Segment next = motion.next(0, 1, 0.15f, 1000, true);
            assertTrue(next.fromX() >= 100 && next.toX() <= 300);
            assertTrue(next.toY() >= 200 && next.fromY() <= 500);
            if (!next.keepDown()) {
                assertEquals(next.fromY(), next.toY(), 0); released = true; break;
            }
        }
        assertTrue("A bounded finger must eventually lift and re-anchor", released);
    }
    @Test public void partialStickDeflectionProducesSlowerScrollThanFullDeflection() {
        ScrollMotion slow = motion(0, 0.4f), fast = motion(0, 1);
        float slowDistance = 0, fastDistance = 0;
        for (int i = 0; i < 8; i++) {
            ScrollMotion.Segment a = slow.next(0, 0.4f, 0.15f, 1000, true);
            ScrollMotion.Segment b = fast.next(0, 1, 0.15f, 1000, true);
            slowDistance += a.fromY() - a.toY(); fastDistance += b.fromY() - b.toY();
        }
        assertTrue(slowDistance > 0 && fastDistance > slowDistance * 2);
    }
    @Test public void veryLightScrollingStillCrossesTouchSlopBeforeALongPress() {
        ScrollMotion motion = motion(0, 0.151f);
        float distance = 0;
        for (int i = 0; i < 8; i++) {
            ScrollMotion.Segment next = motion.next(0, 0.151f, 0.15f, 200, true);
            assertTrue(next.keepDown()); distance += next.fromY() - next.toY();
        }
        assertTrue("Move beyond 12 px of touch slop in 256 ms", distance > 12);
    }
    @Test public void noiseAndInvalidAxesDoNotContinueScrolling() {
        assertFalse(motion(0, 1).next(0.08f, 0.08f, 0.15f, 1000, true).keepDown());
        assertFalse(motion(0, 1).next(Float.NaN, 1, 0.15f, 1000, true).keepDown());
    }
}
