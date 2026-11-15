package com.briancodez.padpointer;

/** Plans a continuous virtual-finger scroll. Android stroke ownership lives in PointerService. */
final class ScrollMotion {
    static final int STEP_MS = 32;
    static final int RELEASE_MS = 80;
    record Area(float left, float top, float right, float bottom) { }
    record Segment(float fromX, float fromY, float toX, float toY, int durationMs, boolean keepDown) { }
    private final Area area;
    private final float minimumSpeed;
    private float x, y, velocityX, velocityY;

    ScrollMotion(Area area, float cursorX, float cursorY, float inputX, float inputY, float touchSlop) {
        this.area = area;
        minimumSpeed = touchSlop * 6;
        float magnitude = (float) Math.hypot(inputX, inputY);
        // Start with room to move inside the same scrollable container, instead of a screen edge.
        x = Math.abs(inputX) > magnitude * 0.2f ? position(area.left, area.right, inputX) : clamp(cursorX, area.left, area.right);
        y = Math.abs(inputY) > magnitude * 0.2f ? position(area.top, area.bottom, inputY) : clamp(cursorY, area.top, area.bottom);
    }

    Segment next(float inputX, float inputY, float deadzone, float pixelsPerSecond, boolean requested) {
        float[] input = PointerMath.velocity(inputX, inputY, deadzone, 1.35f);
        float strength = (float) Math.hypot(input[0], input[1]);
        if (!requested || strength == 0 || pixelsPerSecond <= 0) return release();
        // Enough movement to cross touch slop, even just outside the deadzone; avoid long-presses.
        float speed = Math.max(strength * pixelsPerSecond, Math.min(minimumSpeed, pixelsPerSecond));
        float targetX = -input[0] / strength * speed;
        float targetY = -input[1] / strength * speed;
        if (velocityX * targetX + velocityY * targetY < 0) velocityX = velocityY = 0;
        float alpha = (float) (1 - Math.exp(-STEP_MS / 45d));
        velocityX += (targetX - velocityX) * alpha;
        velocityY += (targetY - velocityY) * alpha;
        float nextX = clamp(x + velocityX * STEP_MS / 1000, area.left, area.right);
        float nextY = clamp(y + velocityY * STEP_MS / 1000, area.top, area.bottom);
        // Settle before lifting at an edge so the app does not add a fling to each restart.
        if (Math.hypot(nextX - x, nextY - y) < 0.05f) return release();
        Segment segment = new Segment(x, y, nextX, nextY, STEP_MS, true);
        x = nextX; y = nextY;
        return segment;
    }

    private Segment release() {
        velocityX = velocityY = 0;
        return new Segment(x, y, x, y, RELEASE_MS, false);
    }
    private static float position(float low, float high, float input) { return low + (high - low) * (input > 0 ? 0.8f : 0.2f); }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(value, high)); }
}
