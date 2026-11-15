package com.briancodez.padpointer;

/**
 * Feeds {@link ScrollMotion} segments to Android's gesture injector.
 *
 * <p>The injector starts a continued stroke right where the previous one ends only while that one is still
 * queued; otherwise it starts "now", leaving a gap. So the driver keeps a few segments in flight.
 *
 * <p>On Android 14 and 15 the injector cancels every injected touch whenever any MotionEvent passes through
 * the accessibility input filter, including the controller events this service intercepts. Holding a stick
 * produces a stream of such events, so the driver treats a cancellation that coincides with controller input
 * as routine and immediately re-grips with a new finger.
 */
final class ScrollDriver {
    interface Host {
        /** Dispatch a segment and report it back through completed/cancelled(epoch). False if Android refused. */
        boolean send(ScrollMotion.Segment segment, int epoch);
        float stickX();
        float stickY();
        float deadzone();
        float speed();
        /** The right stick is outside its deadzone. */
        boolean stickHeld();
        /** A click, action, or mode change must end scrolling now. */
        boolean blocked();
        /** Current bounds of the container being scrolled, or null to keep the previous ones. */
        ScrollMotion.Area refreshArea();
        long now();
        /** The session is over. If {@code forgetStick}, ignore the stick until it reports a new position. */
        void ended(boolean forgetStick);
    }

    /** A cancellation counts as controller-caused when controller input arrived this close to it. */
    static final int ATTRIBUTION_MS = 80;
    static final int AREA_REFRESH_MS = 250;

    private final Host host;
    private ScrollMotion motion;
    private int epoch, inFlight, grips;
    private long lastMoveAt = -1, cancelledAt = -1, lastStickAt = Long.MIN_VALUE / 2, areaAt;

    ScrollDriver(Host host) { this.host = host; }

    boolean active() { return motion != null; }
    int inFlight() { return inFlight; }
    /** Fingers placed since construction; for diagnostics and tests. */
    int grips() { return grips; }

    void start(ScrollMotion motion) {
        this.motion = motion;
        lastMoveAt = cancelledAt = -1;
        areaAt = host.now();
        feed();
    }

    /** Call for every intercepted controller MotionEvent. */
    void stickMoved() {
        lastStickAt = host.now();
        if (cancelledAt >= 0) feed();
    }

    void completed(int segmentEpoch) {
        inFlight--;
        if (segmentEpoch == epoch) lastMoveAt = host.now();
        feed();
    }

    void cancelled(int segmentEpoch) {
        inFlight--;
        if (segmentEpoch == epoch && motion != null) {
            epoch++; // Everything still queued for that finger is doomed; Android reports each one.
            if (!motion.ended()) {
                long now = host.now();
                if (lastMoveAt >= 0) {
                    // Moves of the interrupted segment that already reached the app (segments span several frames
                    // on high refresh-rate displays).
                    long delivered = (now - lastMoveAt) / motion.sampleMs * motion.sampleMs;
                    lastMoveAt += Math.min(delivered, motion.segmentMs - motion.sampleMs);
                }
                motion.lost();
                cancelledAt = now;
            }
        }
        feed();
    }

    /** Forget the session without touching Android (service shutdown). */
    void abandon() { motion = null; epoch++; cancelledAt = -1; }

    void feed() {
        while (motion != null) {
            long now = host.now();
            boolean blocked = host.blocked();
            boolean requested = !blocked && host.stickHeld();
            if (motion.ended()) { if (inFlight == 0) finish(false); return; }
            if (!motion.down()) {
                boolean urgent = cancelledAt >= 0;
                if (!requested) { finish(false); return; }
                if (urgent && lastStickAt < cancelledAt - ATTRIBUTION_MS) {
                    // Probably a real finger on the screen. Wait briefly for the controller event that would explain
                    // it; if none arrives, stop instead of fighting the user's touch.
                    if (now - cancelledAt < ATTRIBUTION_MS) return;
                    finish(true); return;
                }
                // Doomed segments from a cancelled finger reach Android before this new one, so no need to wait.
                if (!urgent && inFlight > 0) return;
                if (!grip(now, urgent)) return;
                continue;
            }
            if (inFlight >= motion.lookahead) return;
            ScrollMotion.Segment segment = motion.next(host.stickX(), host.stickY(), host.deadzone(), host.speed(), requested, blocked);
            if (segment == null) {
                // No room left in the container: let the queued path finish, then start a new finger at the far side.
                // That new touch makes Android cancel this one, so the app sees no lift and no fling.
                if (inFlight > 0) return;
                if (!grip(now, false)) return;
                continue;
            }
            if (!send(segment) || !segment.keepDown()) return;
        }
    }

    private boolean grip(long now, boolean urgent) {
        if (now - areaAt >= AREA_REFRESH_MS) { motion.setArea(host.refreshArea()); areaAt = now; }
        int since = lastMoveAt < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, now - lastMoveAt);
        ScrollMotion.Segment segment = motion.grip(host.stickX(), host.stickY(), host.deadzone(), host.speed(), since, urgent);
        cancelledAt = -1;
        grips++;
        return send(segment);
    }

    private boolean send(ScrollMotion.Segment segment) {
        inFlight++;
        if (host.send(segment, epoch)) return true;
        inFlight--;
        finish(true);
        return false;
    }

    private void finish(boolean forgetStick) {
        motion = null;
        epoch++;
        cancelledAt = -1;
        host.ended(forgetStick);
    }
}
