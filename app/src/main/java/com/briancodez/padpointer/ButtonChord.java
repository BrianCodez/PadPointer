package com.briancodez.padpointer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recognizes the user's pointer on/off buttons: one button, or several held together in any order.
 * Pure logic; PointerService decides what each press does.
 *
 * <p>A button that belongs to a combination and would otherwise do something in pointer mode is held back
 * briefly ({@link #press} returns WAIT). If the rest of the combination follows, the held buttons do nothing
 * else; otherwise {@link #expire} hands them back to run their own action.
 *
 * <p>Releases are not reliable: whenever the service changes its settings (which every toggle does, to start or
 * stop reading the sticks), Android sends the releases of buttons still held to the app instead. So a toggle
 * forgets the held buttons, a fresh press replaces any stale state, and a press counts as held for at most
 * {@link #STALE_MS} unless key repeats show the button is still down.
 */
final class ButtonChord {
    /** How long a held-back button waits for the rest of its combination. */
    static final int WINDOW_MS = 250;
    /** A press with no release or repeat for this long no longer counts as held. */
    static final int STALE_MS = 2000;

    enum Press { TOGGLE, WAIT, NORMAL }

    private final Set<Integer> combo;
    private final Map<Integer, Long> down = new HashMap<>(); // button → last sign of being held (ms)
    private final List<Integer> waiting = new ArrayList<>();
    private final Set<Integer> swallow = new HashSet<>();

    ButtonChord(int[] keys) {
        combo = new HashSet<>();
        for (int key : keys) combo.add(key);
        if (combo.isEmpty()) throw new IllegalArgumentException("A combination needs at least one button");
    }

    boolean matches(int[] keys) {
        Set<Integer> other = new HashSet<>();
        for (int key : keys) other.add(key);
        return combo.equals(other);
    }
    boolean isMember(int key) { return combo.contains(key); }
    boolean isWaiting(int key) { return waiting.contains(key); }
    boolean isWaiting() { return !waiting.isEmpty(); }
    /** The button's press was consumed by the combination, so its repeats and release must be too. */
    boolean isSwallowed(int key) { return swallow.contains(key); }

    /**
     * A controller button went down.
     *
     * @param mayWait the button's own action can be delayed: the pointer is on and the button would not be
     *                passed to the app anyway (a button that already went to the app cannot be taken back)
     */
    Press press(int key, boolean mayWait, long nowMs) {
        down.values().removeIf(time -> nowMs - time > STALE_MS);
        down.put(key, nowMs);
        swallow.remove(key); // a fresh press supersedes a release that never arrived
        if (!combo.contains(key)) return Press.NORMAL;
        if (down.keySet().containsAll(combo)) {
            swallow.addAll(waiting);
            waiting.clear();
            swallow.add(key);
            // Toggling changes the service's settings, after which Android delivers these releases to the app.
            down.keySet().removeAll(combo);
            return Press.TOGGLE;
        }
        if (mayWait) { waiting.add(key); return Press.WAIT; }
        return Press.NORMAL;
    }

    /** Held-back buttons that should now run their own action, oldest first. */
    List<Integer> expire() {
        List<Integer> expired = new ArrayList<>(waiting);
        waiting.clear();
        return expired;
    }

    /** A key repeat: the button is still held. */
    void stillHeld(int key, long nowMs) { down.computeIfPresent(key, (k, time) -> nowMs); }

    /** A controller button went up. @return true if its release belongs to the combination and must be swallowed. */
    boolean release(int key) {
        down.remove(key);
        return swallow.remove(key);
    }

    /** Forget held buttons, for example after the controller disconnects. */
    void reset() { down.clear(); waiting.clear(); swallow.clear(); }

    static int[] parse(String value, int[] fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int[] keys = Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt).distinct().sorted().toArray();
            return keys.length == 0 ? fallback : keys;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static String format(int[] keys) {
        StringBuilder out = new StringBuilder();
        for (int key : Arrays.stream(keys).distinct().sorted().toArray()) {
            if (out.length() > 0) out.append(',');
            out.append(key);
        }
        return out.toString();
    }
}
