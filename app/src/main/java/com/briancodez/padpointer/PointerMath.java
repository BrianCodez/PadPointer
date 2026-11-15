package com.briancodez.padpointer;

final class PointerMath {
    private PointerMath() {}

    static float[] velocity(float x, float y, float deadzone, float curve) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return new float[]{0, 0};
        float magnitude = (float) Math.hypot(x, y);
        if (magnitude <= deadzone) return new float[]{0, 0};
        float scaled = Math.min(1f, (magnitude - deadzone) / (1f - deadzone));
        float strength = (float) Math.pow(scaled, curve);
        return new float[]{x / magnitude * strength, y / magnitude * strength};
    }

    static float clamp(float value, float max) { return Math.max(0, Math.min(value, Math.max(0, max - 1))); }
}
