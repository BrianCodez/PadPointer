package com.briancodez.padpointer;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Discrete-time model of the path from ScrollDriver to an app's scroller, for JVM tests.
 *
 * <p>The system side follows AOSP's {@code MotionEventInjector} and {@code GestureDescription}
 * (android14-release): steps sampled every display frame plus at stroke ends, touch points rounded to whole
 * pixels, a continued stroke accepted only if it starts on the previous end point, queued continuations
 * scheduled after the last queued event (otherwise "now"), a fresh gesture cancelling any open one, and
 * every other MotionEvent through the accessibility input filter cancelling injected touches (Android 14-15).
 *
 * <p>The app side follows RecyclerView: dragging starts once movement exceeds touch slop, which is subtracted
 * from the first scroll delta; ACTION_CANCEL stops without a fling; ACTION_UP flings if the finger was still
 * moving in the last 40 ms. Frames read the content the way Android's InputConsumer resamples touches:
 * consume moves up to 5 ms before the frame, interpolate toward the next one (or extrapolate up to 8 ms),
 * never across a new DOWN, and leave the content unchanged on frames with no new move.
 */
final class InjectorSimulation implements ScrollDriver.Host {
    // ---- tunables ----
    double frameMs = 1000 / 60.0;
    int sampleMs = 16;
    double binderMs = 0.6, callbackMs = 1.5;
    float slop = 21, deadzone = 0.15f, speed = 1296, minFlingSpeed = 131;
    ScrollMotion.Area area = new ScrollMotion.Area(31, 263, 1048, 2104);
    /** Mean gap between controller MotionEvents while the stick is held; 0 = none (or Android 16's fix). */
    double stickEventEveryMs = 0;
    boolean stickEventsCancelTouches = true;
    /** Occasional main-thread stalls in the service: every N ms, block for M ms. */
    double stallEveryMs = 0, stallMs = 0;

    // ---- inputs the test scripts ----
    private float stickX, stickY;
    boolean blockedFlag;

    // ---- observations ----
    final List<double[]> frames = new ArrayList<>(); // {time, resampled offset}
    int downs, ups, cancels, flings, rejectedContinuations, emptySegments, endedCount;
    boolean lastEndForgotStick;
    double lastMoveTime = -1;

    final ScrollDriver driver = new ScrollDriver(this);
    private final Random random = new Random(7);
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long order;
    private double now, scheduledUntil;

    // Injector (system) state.
    private final List<double[]> pending = new ArrayList<>(); // {time, x, y, action, endOfSequence, id}
    private final List<int[]> inProgress = new ArrayList<>();   // {sequence, epoch}
    private boolean fingerScheduled;
    private int lastX, lastY, sequence;
    private double lastScheduled;
    // App state.
    private boolean touchOpen, dragging;
    private int touchLastY;
    private double offset;
    private final List<double[]> history = new ArrayList<>(); // {time, y}
    private final List<double[]> timeline = new ArrayList<>(); // {time, offset, drag}
    private int drag;
    private double consumedUntil = -1, shown;

    static final int DOWN = 0, MOVE = 1, UP = 2;

    private record Task(double time, long order, Runnable run) implements Comparable<Task> {
        public int compareTo(Task o) { return time != o.time ? Double.compare(time, o.time) : Long.compare(order, o.order); }
    }

    void at(double time, Runnable run) { queue.add(new Task(time, order++, run)); }
    /** Runs on the service's main thread; messages arriving during a stall wait until it ends. */
    void onService(double time, Runnable run) {
        if (stallEveryMs > 0) {
            double window = Math.floor(time / stallEveryMs) * stallEveryMs;
            if (window > 0 && time < window + stallMs) time = window + stallMs;
        }
        at(time, run);
    }

    void startScroll(double time, float cursorX, float cursorY) {
        onService(time, () -> driver.start(new ScrollMotion(area, cursorX, cursorY, slop, sampleMs)));
    }

    /** The controller reports a new right-stick position: one MotionEvent through the input filter. */
    void stick(double time, float x, float y) {
        at(time, () -> {
            if (stickEventsCancelTouches) cancelAnyPending();
            onService(now + callbackMs, () -> { stickX = x; stickY = y; driver.stickMoved(); });
        });
    }

    /** A real finger touches the screen: the injector cancels injected touches with no controller event. */
    void realFingerTouch(double time) { at(time, this::cancelAnyPending); }

    void run(double until) {
        for (double t = Math.ceil(scheduledUntil / frameMs + 1e-9) * frameMs; t <= until; t += frameMs) {
            double frameTime = t;
            at(frameTime, () -> frames.add(new double[]{frameTime, resample(frameTime)}));
        }
        for (double t = Math.ceil(scheduledUntil / 16 + 1e-9) * 16; t <= until; t += 16) onService(t, () -> { if (driver.active()) driver.feed(); });
        scheduledUntil = until;
        if (stickEventEveryMs > 0) scheduleStickEvent(now, until);
        while (!queue.isEmpty() && queue.peek().time <= until) {
            Task task = queue.poll();
            now = task.time;
            task.run.run();
        }
        now = until;
    }

    private void scheduleStickEvent(double from, double until) {
        double t = from - Math.log(1 - random.nextDouble()) * stickEventEveryMs;
        if (t > until) return;
        at(t, () -> {
            // Sensor noise while the stick is held off-center; MotionEventInjector.onMotionEvent cancels first.
            if (Math.hypot(stickX, stickY) > deadzone) {
                if (stickEventsCancelTouches) cancelAnyPending();
                onService(now + callbackMs, driver::stickMoved);
            }
            scheduleStickEvent(now, until);
        });
    }

    // ---- ScrollDriver.Host ----
    @Override public boolean send(ScrollMotion.Segment segment, int epoch) {
        at(now + binderMs, () -> inject(segment, epoch));
        return true;
    }
    @Override public float stickX() { return stickX; }
    @Override public float stickY() { return stickY; }
    @Override public float deadzone() { return deadzone; }
    @Override public float speed() { return speed; }
    @Override public boolean stickHeld() { return Math.hypot(stickX, stickY) > deadzone; }
    @Override public boolean blocked() { return blockedFlag; }
    @Override public ScrollMotion.Area refreshArea() { return area; }
    @Override public long now() { return (long) Math.floor(now); }
    @Override public void ended(boolean forgetStick) { endedCount++; lastEndForgotStick = forgetStick; }

    // ---- MotionEventInjector ----
    private void inject(ScrollMotion.Segment s, int epoch) {
        if (s.fresh()) cancelAnyPending();
        else if (!fingerScheduled || s.fromX() != lastX || s.fromY() != lastY) {
            rejectedContinuations++;
            cancelAnyPending();
            notifyService(epoch, false);
            return;
        }
        double base = inProgress.isEmpty() ? now : lastScheduled;
        List<double[]> events = new ArrayList<>();
        int id = sequence++;
        List<Integer> times = new ArrayList<>();
        for (int t = 0; t < s.durationMs(); t += sampleMs) times.add(t);
        times.add(s.durationMs());
        for (int i = 0; i < times.size(); i++) {
            int t = times.get(i);
            int x = Math.round(s.fromX() + (s.toX() - s.fromX()) * (t / (float) s.durationMs()));
            int y = Math.round(s.fromY() + (s.toY() - s.fromY()) * (t / (float) s.durationMs()));
            if (i == 0 && s.fresh()) { events.add(new double[]{base + t, x, y, DOWN, 0, id}); fingerScheduled = true; }
            else if (x != lastX || y != lastY) events.add(new double[]{base + t, x, y, MOVE, 0, id});
            lastX = x; lastY = y;
            if (i == times.size() - 1 && !s.keepDown()) { events.add(new double[]{base + t, x, y, UP, 0, id}); fingerScheduled = false; }
        }
        if (events.isEmpty()) { emptySegments++; notifyService(epoch, false); return; }
        events.get(events.size() - 1)[4] = 1;
        inProgress.add(new int[]{id, epoch});
        for (double[] event : events) {
            pending.add(event);
            lastScheduled = event[0];
            at(Math.max(now, event[0]), () -> deliver(event));
        }
    }

    private void deliver(double[] event) {
        if (!pending.remove(event)) return; // removed by a cancellation
        app((int) event[3], (int) event[2]);
        if (event[4] == 1) {
            int[] done = inProgress.remove(0);
            notifyService(done[1], true);
        }
    }

    private void cancelAnyPending() {
        if (!pending.isEmpty()) {
            pending.clear();
            if (touchOpen) appCancel();
            for (int[] seq : inProgress) notifyService(seq[1], false);
            inProgress.clear();
        } else if (fingerScheduled && touchOpen) appCancel();
        fingerScheduled = false;
    }

    private void notifyService(int epoch, boolean completed) {
        onService(now + callbackMs, () -> { if (completed) driver.completed(epoch); else driver.cancelled(epoch); });
    }

    // ---- RecyclerView ----
    private void app(int action, int y) {
        if (action == DOWN) {
            downs++; touchOpen = true; dragging = false; touchLastY = y; history.clear(); drag++;
        } else if (action == MOVE) {
            int dy = touchLastY - y;
            if (!dragging && Math.abs(dy) > slop) { dy = dy > 0 ? dy - (int) slop : dy + (int) slop; dragging = true; }
            if (dragging) { touchLastY = y; offset += dy; lastMoveTime = now; timeline.add(new double[]{now, offset, drag}); }
        } else {
            ups++; touchOpen = false;
            if (dragging && velocity() > minFlingSpeed) flings++;
            dragging = false;
        }
        history.add(new double[]{now, y});
    }
    private void appCancel() { cancels++; touchOpen = false; dragging = false; }
    private double velocity() {
        double[] last = history.get(history.size() - 1);
        if (now - last[0] > 40) return 0; // Android assumes the pointer stopped.
        for (double[] h : history) {
            if (now - h[0] <= 100 && now - h[0] > 0) return Math.abs(h[1] - last[1]) / (now - h[0]) * 1000;
        }
        return 0;
    }

    /** Content offset as rendered on a frame, after Android's touch resampling. */
    private double resample(double frameTime) {
        double sampleTime = frameTime - 5;
        int last = -1;
        for (int i = timeline.size() - 1; i >= 0; i--) if (timeline.get(i)[0] <= sampleTime) { last = i; break; }
        if (last < 0 || timeline.get(last)[0] <= consumedUntil) return shown; // nothing new to consume
        consumedUntil = timeline.get(last)[0];
        double[] current = timeline.get(last);
        shown = current[1];
        if (last + 1 < timeline.size() && timeline.get(last + 1)[2] == current[2] && timeline.get(last + 1)[0] <= frameTime) {
            double[] next = timeline.get(last + 1);
            double delta = next[0] - current[0];
            if (delta >= 2) shown = current[1] + (next[1] - current[1]) * (sampleTime - current[0]) / delta;
        } else if (last > 0 && timeline.get(last - 1)[2] == current[2]) {
            double[] previous = timeline.get(last - 1);
            double delta = current[0] - previous[0];
            if (delta >= 2 && delta <= 20) {
                double predictTo = Math.min(sampleTime, current[0] + Math.min(delta / 2, 8));
                shown = previous[1] + (current[1] - previous[1]) * (predictTo - previous[0]) / delta;
            }
        }
        return shown;
    }

    // ---- measurements ----
    double offset() { return offset; }
    double now0() { return now; }

    /** Per-frame content movement between two times. */
    double[] deltas(double from, double to) {
        List<Double> d = new ArrayList<>();
        double[] previous = null;
        for (double[] f : frames) {
            if (f[0] < from || f[0] > to) continue;
            if (previous != null) d.add(f[1] - previous[1]);
            previous = f;
        }
        double[] out = new double[d.size()];
        for (int i = 0; i < out.length; i++) out[i] = d.get(i);
        return out;
    }
}
