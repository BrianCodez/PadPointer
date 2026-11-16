package com.briancodez.padpointer;

/** Frame-time-based native wheel movement. Fractional wheel units preserve slow stick motion. */
final class WheelMotion {
    private float vx, vy;
    void reset() { vx = vy = 0; }

    float[] step(float x, float y, float deadzone, float speed, float dt,
                 float horizontalFactor, float verticalFactor) {
        if (!Float.isFinite(dt) || dt <= 0 || !Float.isFinite(speed) || speed <= 0
                || !Float.isFinite(horizontalFactor) || horizontalFactor <= 0
                || !Float.isFinite(verticalFactor) || verticalFactor <= 0) {
            reset(); return new float[]{0, 0};
        }
        float[] input = PointerMath.velocity(x, y, deadzone, 1.35f);
        // Neutral, disconnect and mode changes stop immediately; no autonomous fling/tail.
        if (input[0] == 0 && input[1] == 0) { reset(); return new float[]{0, 0}; }
        dt = Math.min(dt, 0.032f); // Drop missed time after a stall instead of producing a catch-up jump.
        float tx = input[0] * speed, ty = -input[1] * speed;
        if (vx * tx + vy * ty < 0) reset();
        float alpha = (float) (1 - Math.exp(-dt / 0.045f));
        vx += (tx - vx) * alpha; vy += (ty - vy) * alpha;
        return new float[]{vx * dt / horizontalFactor, vy * dt / verticalFactor};
    }
}
