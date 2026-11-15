package com.briancodez.padpointer;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ButtonChordTest {
    private long t = 1000; // event time in ms; each press advances it
    // Raw key codes keep this a plain JVM test: L1 102, R1 103, L3 106, Start 108, Select 109, A 96.
    private static final int L1 = 102, R1 = 103, L3 = 106, START = 108, SELECT = 109, A = 96;

    @Test public void singleButtonTogglesAndSwallowsItsRelease() {
        ButtonChord chord = new ButtonChord(new int[]{L3});
        assertEquals(ButtonChord.Press.TOGGLE, chord.press(L3, false, t += 50));
        assertTrue(chord.isSwallowed(L3));
        assertTrue(chord.release(L3));
        assertFalse(chord.release(L3));
    }

    @Test public void combinationTogglesInEitherOrder() {
        for (int[] order : new int[][]{{L1, R1}, {R1, L1}}) {
            ButtonChord chord = new ButtonChord(new int[]{L1, R1});
            assertEquals(ButtonChord.Press.WAIT, chord.press(order[0], true, t += 50));
            assertEquals(ButtonChord.Press.TOGGLE, chord.press(order[1], true, t += 50));
            assertTrue("Held-back button never runs its own action", chord.expire().isEmpty());
            assertTrue(chord.release(order[0]));
            assertTrue(chord.release(order[1]));
        }
    }

    @Test public void quickPressOfOneMemberRunsItsOwnAction() {
        ButtonChord chord = new ButtonChord(new int[]{L1, R1});
        assertEquals(ButtonChord.Press.WAIT, chord.press(L1, true, t += 50));
        assertTrue(chord.isWaiting(L1));
        assertEquals(List.of(L1), chord.expire());
        assertFalse("Its release belongs to the ordinary press", chord.release(L1));
    }

    @Test public void holdingOneMemberPastTheWindowThenAddingTheOtherStillToggles() {
        ButtonChord chord = new ButtonChord(new int[]{SELECT, START});
        chord.press(SELECT, true, t += 50);
        assertEquals(List.of(SELECT), chord.expire()); // window elapsed: Select did its own action
        assertEquals(ButtonChord.Press.TOGGLE, chord.press(START, true, t += 50));
        assertFalse("Select's release goes with its own action", chord.release(SELECT));
        assertTrue(chord.release(START));
    }

    @Test public void pointerOffPassesMembersThroughAndOnlyTheCompletingButtonIsTaken() {
        ButtonChord chord = new ButtonChord(new int[]{L1, R1});
        assertEquals("Games still get L1", ButtonChord.Press.NORMAL, chord.press(L1, false, t += 50));
        assertEquals(ButtonChord.Press.TOGGLE, chord.press(R1, false, t += 50));
        assertFalse(chord.release(L1));
        assertTrue(chord.release(R1));
    }

    @Test public void otherButtonsAreUnaffected() {
        ButtonChord chord = new ButtonChord(new int[]{L1, R1});
        assertEquals(ButtonChord.Press.NORMAL, chord.press(A, true, t += 50));
        assertFalse(chord.isMember(A));
        assertFalse(chord.release(A));
    }

    @Test public void threeButtonCombination() {
        ButtonChord chord = new ButtonChord(new int[]{L1, R1, A});
        assertEquals(ButtonChord.Press.WAIT, chord.press(A, true, t += 50));
        assertEquals(ButtonChord.Press.WAIT, chord.press(L1, true, t += 50));
        assertEquals(ButtonChord.Press.TOGGLE, chord.press(R1, true, t += 50));
        assertTrue(chord.expire().isEmpty());
    }

    @Test public void disconnectForgetsHeldButtons() {
        ButtonChord chord = new ButtonChord(new int[]{L1, R1});
        chord.press(L1, true, t += 50);
        chord.reset();
        assertFalse(chord.isWaiting());
        assertEquals("L1 is no longer considered held", ButtonChord.Press.WAIT, chord.press(R1, true, t += 50));
    }

    @Test public void releasesLostAfterAToggleDoNotCauseASecondToggle() {
        // Android delivers the releases to the app once the toggle changes the service's settings.
        ButtonChord chord = new ButtonChord(new int[]{L1, R1});
        chord.press(L1, true, t += 50);
        assertEquals(ButtonChord.Press.TOGGLE, chord.press(R1, true, t += 50));
        assertEquals("R1 alone later must not count L1 as still held", ButtonChord.Press.WAIT, chord.press(R1, true, t += 500));
        assertEquals("A fresh press replaces the release that never came", List.of(R1), chord.expire());
        assertFalse(chord.release(R1));
    }

    @Test public void aPressWithNoReleaseOrRepeatGoesStale() {
        ButtonChord chord = new ButtonChord(new int[]{SELECT, START});
        chord.press(SELECT, false, t += 50); // its release never arrives
        assertEquals(ButtonChord.Press.NORMAL, chord.press(START, false, t += ButtonChord.STALE_MS + 1));
        assertFalse(chord.release(START));
        assertEquals("Repeats keep a long hold alive", ButtonChord.Press.NORMAL, chord.press(SELECT, false, t += 50));
        for (int i = 0; i < 6; i++) chord.stillHeld(SELECT, t += 500);
        assertEquals(ButtonChord.Press.TOGGLE, chord.press(START, false, t += 50));
    }

    @Test public void savedValuesRoundTripAndBadValuesFallBack() {
        int[] fallback = {L3};
        assertArrayEquals(new int[]{L1, R1}, ButtonChord.parse(ButtonChord.format(new int[]{R1, L1, R1}), fallback));
        assertArrayEquals(fallback, ButtonChord.parse("", fallback));
        assertArrayEquals(fallback, ButtonChord.parse("L1,R1", fallback));
        assertArrayEquals(fallback, ButtonChord.parse(null, fallback));
        assertTrue(new ButtonChord(new int[]{R1, L1}).matches(new int[]{L1, R1}));
        assertThrows(IllegalArgumentException.class, () -> new ButtonChord(new int[0]));
    }
}
