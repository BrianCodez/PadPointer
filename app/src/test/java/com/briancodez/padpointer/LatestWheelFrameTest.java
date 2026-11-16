package com.briancodez.padpointer;

import org.junit.Test;
import static org.junit.Assert.*;

public class LatestWheelFrameTest {
    @Test public void slowConsumerGetsOnlyNewestFrameWithoutBacklog() {
        LatestWheelFrame mailbox = new LatestWheelFrame();
        for (int i = 0; i <= 1000; i++) mailbox.offer(new LatestWheelFrame.Frame(10, 20, 0, -.1f, i));
        assertEquals(1000, mailbox.take(1000).time());
        assertNull(mailbox.take(1000));
    }
    @Test public void expiredAndFutureEventsAreDiscarded() {
        LatestWheelFrame mailbox = new LatestWheelFrame();
        mailbox.offer(new LatestWheelFrame.Frame(0, 0, 0, 1, 100));
        assertNull(mailbox.take(181));
        mailbox.offer(new LatestWheelFrame.Frame(0, 0, 0, 1, 200));
        assertNull(mailbox.take(199));
    }
    @Test public void stopClearsPendingMovementAndNewSessionStartsFresh() {
        LatestWheelFrame mailbox = new LatestWheelFrame();
        mailbox.offer(new LatestWheelFrame.Frame(0, 0, 0, 1, 100));
        mailbox.clear(); assertNull(mailbox.take(100));
        mailbox.offer(new LatestWheelFrame.Frame(20, 30, 0, -1, 200));
        assertEquals(-1, mailbox.take(200).v(), 0);
    }
}
