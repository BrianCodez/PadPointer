package com.briancodez.padpointer;

/** Single-slot mailbox: a slow Binder call must never build a backlog of scroll events. */
final class LatestWheelFrame {
    static final long MAX_AGE_MS = 80;
    record Frame(float x, float y, float h, float v, long time) { }
    private Frame pending;
    synchronized void offer(Frame value) { pending = value; }
    synchronized void clear() { pending = null; }
    synchronized Frame take(long now) {
        Frame value = pending; pending = null;
        return value != null && now >= value.time && now - value.time <= MAX_AGE_MS ? value : null;
    }
}
