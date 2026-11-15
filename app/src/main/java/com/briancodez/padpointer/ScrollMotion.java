package com.briancodez.padpointer;

/**
 * Plans the virtual finger for joystick scrolling. Pure geometry and timing; ScrollDriver owns dispatch.
 *
 * <p>Positions are whole pixels because Android's gesture injector rounds every touch point and only
 * accepts a continued stroke that starts exactly where the previous one ended.
 *
 * <p>Firefox-engine apps (GeckoView) need different handling, enabled with {@link #setGecko}. GeckoView discards
 * the ACTION_CANCEL that Android's injector sends when it replaces or cancels a finger, because that event's down
 * time is not the finger's. Firefox then still thinks the old finger is panning, ignores the next finger's DOWN,
 * and pans from where the old finger was: a new finger placed elsewhere makes the page jump. So in Firefox a
 * replacement finger goes down exactly where the old one was ({@link #gripInPlace}), a finger at the container edge
 * is lifted properly instead of cancelled, and no finger is lifted before it has moved past Firefox's tap
 * tolerance, which would otherwise count as a tap.
 */
final class ScrollMotion {
    /** Stationary time before the final lift, so apps measure no fling velocity (they assume a stop after 40 ms). */
    static final int STOP_HOLD_MS = 80;
    /** Queue at least this much movement ahead so the touch never pauses between dispatches. */
    static final int LOOKAHEAD_MS = 40;
    /** Typical time for a callback to reach the service plus a new gesture to reach the injector. */
    static final int DISPATCH_LATENCY_MS = 2;
    private static final float ACCEL_TAU_MS = 45, DECEL_TAU_MS = 30;
    private static final float ANCHOR_MARGIN = 0.1f;

    record Area(float left, float top, float right, float bottom) {
        boolean contains(float x, float y) { return x >= left && x <= right && y >= top && y <= bottom; }
    }
    /** One stroke. {@code fresh} puts a new finger down; otherwise it continues the current finger. */
    record Segment(int fromX, int fromY, int toX, int toY, int durationMs, boolean fresh, boolean keepDown) { }

    final int sampleMs, segmentMs, lookahead;
    private final float slop, minimumSpeed;
    private Area area;
    private float idealX, idealY, velocityX, velocityY;
    private int lastX, lastY;
    private boolean down, ended;
    /** Firefox's tap/pan tolerance in pixels, or 0 for apps that handle injected cancels normally. */
    private int geckoTolerance;
    /** How far the current finger has moved since its DOWN, and in which direction it last moved. */
    private float travel, lastDirX, lastDirY = -1;

    /**
     * @param sampleMs the injector's sampling period (one display frame); segments are whole multiples of it
     *                 so every touch event lands on an even cadence.
     */
    ScrollMotion(Area area, float cursorX, float cursorY, float touchSlop, int sampleMs) {
        this.area = area;
        this.sampleMs = Math.max(1, sampleMs);
        segmentMs = this.sampleMs * Math.max(1, Math.round(16f / this.sampleMs));
        lookahead = Math.max(2, (int) Math.ceil(LOOKAHEAD_MS / (double) segmentMs));
        slop = Math.max(1, touchSlop);
        minimumSpeed = slop * 6; // Crosses slop well before a long-press, even just outside the deadzone.
        idealX = clamp(cursorX, area.left, area.right);
        idealY = clamp(cursorY, area.top, area.bottom);
        lastX = Math.round(idealX); lastY = Math.round(idealY);
    }

    boolean down() { return down; }
    boolean ended() { return ended; }
    boolean gecko() { return geckoTolerance > 0; }
    /** Treat the target as a Firefox-engine app whose tap and pan tolerance is {@code tolerancePx}. */
    void setGecko(int tolerancePx) { geckoTolerance = Math.max(0, tolerancePx); }
    void setArea(Area area) { if (area != null) this.area = area; }
    /** The current finger was cancelled by Android (for example by the controller's own input events). */
    void lost() { down = false; }

    /**
     * Places a new finger and moves it past touch slop at once, so the app resumes dragging on its next frame.
     * Apps drop the slop distance from the first drag delta, so the content continues without a jump.
     *
     * @param sinceMoveMs time since the previous finger's last delivered move, or negative when there was none
     * @param urgent      true after a cancellation: send the first move almost immediately
     */
    Segment grip(float inputX, float inputY, float deadzone, float pixelsPerSecond, int sinceMoveMs, boolean urgent) {
        boolean fresh = sinceMoveMs < 0;
        steer(inputX, inputY, deadzone, pixelsPerSecond, true, false);
        float speed = (float) Math.hypot(velocityX, velocityY);
        float dirX, dirY;
        if (speed > 0) { dirX = velocityX / speed; dirY = velocityY / speed; }
        else {
            float magnitude = (float) Math.hypot(inputX, inputY);
            if (!(magnitude > 0) || !Float.isFinite(magnitude)) { dirX = 0; dirY = -1; }
            else { dirX = -inputX / magnitude; dirY = -inputY / magnitude; }
        }
        // Start at the trailing side of the container so the finger has the most room to travel.
        float anchorX = Math.abs(dirX) > 0.2f ? edge(area.left, area.right, dirX) : clamp(idealX, area.left, area.right);
        float anchorY = Math.abs(dirY) > 0.2f ? edge(area.top, area.bottom, dirY) : clamp(idealY, area.top, area.bottom);
        // Keep the even event cadence after an edge re-grip; after a cancel, every millisecond counts.
        int duration = urgent ? 1 : fresh ? segmentMs : Math.max(1, Math.min(segmentMs, segmentMs - sinceMoveMs));
        // Content movement covers the time the finger was missing (plus the round trip through Android),
        // so speed stays steady however often the finger is replaced.
        int elapsed = fresh ? segmentMs : (int) Math.min((long) sinceMoveMs + duration + DISPATCH_LATENCY_MS, 2L * segmentMs);
        // The app ignores the first `slop` pixels along the drag axis, then scrolls by the rest.
        boolean alongX = Math.abs(dirX) >= Math.abs(dirY);
        float major = Math.max(Math.abs(dirX), Math.abs(dirY));
        // A Firefox page that was flinging needs its own (larger) tolerance crossed before it pans again.
        int threshold = Math.max((int) Math.ceil(slop), geckoTolerance);
        int majorStep = threshold + Math.max(1, Math.round(speed * major * elapsed / 1000f));
        float distance = majorStep / major;
        int fromX = Math.round(anchorX), fromY = Math.round(anchorY);
        int toX = fromX + (alongX ? (dirX >= 0 ? majorStep : -majorStep) : Math.round(dirX * distance));
        int toY = fromY + (alongX ? Math.round(dirY * distance) : (dirY >= 0 ? majorStep : -majorStep));
        toX = (int) clamp(toX, (float) Math.ceil(area.left), (float) Math.floor(area.right));
        toY = (int) clamp(toY, (float) Math.ceil(area.top), (float) Math.floor(area.bottom));
        idealX = toX; idealY = toY; lastX = toX; lastY = toY;
        down = true; ended = false;
        travel = (float) Math.hypot(toX - fromX, toY - fromY);
        remember(toX - fromX, toY - fromY);
        return new Segment(fromX, fromY, toX, toY, duration, true, true);
    }

    /**
     * Firefox only: places the new finger exactly where Firefox last saw the old one. Firefox ignored the cancel
     * and keeps panning from that point, so the page continues with no jump and no tolerance to cross.
     *
     * @param sinceMoveMs time since that point was delivered, or negative at the start of a scroll
     */
    Segment gripInPlace(int x, int y, float inputX, float inputY, float deadzone, float pixelsPerSecond, int sinceMoveMs) {
        steer(inputX, inputY, deadzone, pixelsPerSecond, true, false);
        boolean fresh = sinceMoveMs < 0;
        int duration = fresh ? segmentMs : 1;
        int elapsed = fresh ? segmentMs : (int) Math.min((long) sinceMoveMs + duration + DISPATCH_LATENCY_MS, 2L * segmentMs);
        float stepX = velocityX * elapsed / 1000f, stepY = velocityY * elapsed / 1000f;
        if (Math.hypot(stepX, stepY) < 1) { // always move at least a pixel: the injector rejects an empty stroke
            float magnitude = (float) Math.hypot(inputX, inputY);
            stepX = magnitude > 0 ? -inputX / magnitude : 0; stepY = magnitude > 0 ? -inputY / magnitude : -1;
        }
        int toX = Math.max(0, Math.round(x + stepX)), toY = Math.max(0, Math.round(y + stepY));
        if (toX == x && toY == y) toY = y > 0 ? y - 1 : y + 1;
        idealX = toX; idealY = toY; lastX = toX; lastY = toY;
        down = true; ended = false;
        travel = (float) Math.hypot(toX - x, toY - y);
        remember(toX - x, toY - y);
        return new Segment(x, y, toX, toY, duration, true, true);
    }

    /**
     * Next continued segment for the current finger. Returns a stationary lift when scrolling should stop, or
     * {@code null} when the finger has no room left in the container and must be re-gripped.
     *
     * @param abrupt stop immediately (a click or action is waiting) instead of easing out
     */
    Segment next(float inputX, float inputY, float deadzone, float pixelsPerSecond, boolean requested, boolean abrupt) {
        if (!down) throw new IllegalStateException("No finger to continue");
        boolean moving = steer(inputX, inputY, deadzone, pixelsPerSecond, requested, abrupt);
        float stepX = velocityX * segmentMs / 1000f, stepY = velocityY * segmentMs / 1000f;
        if (!moving && Math.hypot(stepX, stepY) < 1) { velocityX = velocityY = 0; return lift(); }
        float nextX = idealX + stepX, nextY = idealY + stepY;
        if (!area.contains(nextX, nextY)) {
            if (moving) {
                // Firefox would mistake a replacement finger for this one jumping, so lift it properly (still moving,
                // so the page flings on for the few milliseconds until the next finger lands).
                return gecko() ? liftMoving(nextX, nextY) : null;
            }
            velocityX = velocityY = 0; return lift(); // The easing tail reached the edge; just stop there.
        }
        int toX = Math.round(nextX), toY = Math.round(nextY);
        if (toX == lastX && toY == lastY) {
            // A sub-pixel step would give the injector nothing to send, which it reports as a failure.
            if (Math.abs(stepX) >= Math.abs(stepY)) toX += stepX >= 0 ? 1 : -1; else toY += stepY >= 0 ? 1 : -1;
            if (!area.contains(toX, toY)) return moving ? null : lift();
            nextX = toX; nextY = toY;
        }
        Segment segment = new Segment(lastX, lastY, toX, toY, segmentMs, false, true);
        travel += (float) Math.hypot(toX - lastX, toY - lastY);
        remember(toX - lastX, toY - lastY);
        idealX = nextX; idealY = nextY; lastX = toX; lastY = toY;
        return segment;
    }

    /** @return whether the stick is asking for movement. */
    private boolean steer(float inputX, float inputY, float deadzone, float pixelsPerSecond, boolean requested, boolean abrupt) {
        float[] input = PointerMath.velocity(inputX, inputY, deadzone, 1.35f);
        float strength = (float) Math.hypot(input[0], input[1]);
        boolean moving = requested && strength > 0 && pixelsPerSecond > 0;
        float targetX = 0, targetY = 0, tau = DECEL_TAU_MS;
        if (moving) {
            float speed = Math.max(strength * pixelsPerSecond, Math.min(minimumSpeed, pixelsPerSecond));
            // The finger moves against the stick, like dragging content with a thumb.
            targetX = -input[0] / strength * speed;
            targetY = -input[1] / strength * speed;
            if (velocityX * targetX + velocityY * targetY < 0) velocityX = velocityY = 0;
            tau = ACCEL_TAU_MS;
        } else if (abrupt) {
            velocityX = velocityY = 0;
            return false;
        }
        float alpha = (float) (1 - Math.exp(-segmentMs / tau));
        velocityX += (targetX - velocityX) * alpha;
        velocityY += (targetY - velocityY) * alpha;
        return moving;
    }

    private Segment lift() {
        if (geckoTolerance > 0 && travel <= geckoTolerance) {
            // Firefox would read lifting this finger as a tap on whatever is under it. Move it just past the
            // tolerance first (in the direction it was going, or back if there is no room), then lift.
            float need = geckoTolerance + 1 - travel;
            int toX = Math.round(lastX + lastDirX * need), toY = Math.round(lastY + lastDirY * need);
            if (!area.contains(toX, toY)) { toX = Math.round(lastX - lastDirX * need); toY = Math.round(lastY - lastDirY * need); }
            toX = Math.max(0, toX); toY = Math.max(0, toY);
            travel = geckoTolerance + 1;
            if (toX != lastX || toY != lastY) {
                Segment segment = new Segment(lastX, lastY, toX, toY, segmentMs, false, true);
                idealX = lastX = toX; idealY = lastY = toY;
                return segment;
            }
        }
        down = false; ended = true;
        return new Segment(lastX, lastY, lastX, lastY, STOP_HOLD_MS, false, false);
    }

    /** Lifts the finger while it is still moving; a new finger follows (not the end of the scroll). */
    private Segment liftMoving(float nextX, float nextY) {
        int toX = Math.max(0, Math.round(nextX)), toY = Math.max(0, Math.round(nextY));
        if (toX == lastX && toY == lastY) toY += lastDirY >= 0 ? 1 : -1;
        Segment segment = new Segment(lastX, lastY, toX, toY, segmentMs, false, false);
        idealX = lastX = toX; idealY = lastY = toY;
        down = false; ended = false;
        return segment;
    }

    private void remember(float dx, float dy) {
        float length = (float) Math.hypot(dx, dy);
        if (length > 0) { lastDirX = dx / length; lastDirY = dy / length; }
    }
    private static float edge(float low, float high, float direction) {
        float margin = (high - low) * ANCHOR_MARGIN;
        return direction > 0 ? low + margin : high - margin;
    }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(value, high)); }
}
