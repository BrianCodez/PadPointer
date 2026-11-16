package com.briancodez.padpointer;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

final class Prefs {
    static final String FILE = "pointer";
    static final String[] CURSOR_IDS = {"arrow", "compact", "dot", "crosshair", "image"};
    static final String[] CURSOR_NAMES = {"Classic arrow", "Compact pointer", "Dot", "Crosshair", "Custom image"};
    static final String[] ACTIONS = {"Click", "Back", "Double click", "Long press", "Drag toggle", "Home", "Recents", "Volume up", "Volume down", "Notifications", "Screenshot", "Play / pause", "Brightness up", "Brightness down", "Repeat click", "Pass through"};
    final SharedPreferences data;
    Prefs(Context context) { data = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }
    float speed() { return data.getInt("speed", 900); }
    float curve() { return data.getInt("curve", 16) / 10f; }
    float deadzone() { return data.getInt("deadzone", 15) / 100f; }
    int size() { return data.getInt("size", 30); }
    String cursorStyle() { return data.getString("cursorStyle", "arrow"); }
    int cursorColor() { return data.getInt("cursorColor", 0xFF7BE0C3); }
    float imageHotspotX() { return data.getInt("imageHotspotX", 0) / 100f; }
    float imageHotspotY() { return data.getInt("imageHotspotY", 0) / 100f; }
    int timeout() { return data.getInt("timeout", 5); }
    int repeatMs() { return data.getInt("repeatMs", 500); }
    boolean enabled() { return data.getBoolean("enabled", false); }
    boolean forceKeyboard() { return data.getBoolean("keyboard", true); }
    boolean haptics() { return data.getBoolean("haptics", true); }
    boolean nativeScroll() { return data.getBoolean("nativeScroll", false); }
    /** Buttons that turn the pointer on and off, held together. */
    static final int[] DEFAULT_POINTER_BUTTONS = {KeyEvent.KEYCODE_BUTTON_THUMBL};
    int[] pointerButtons() { return ButtonChord.parse(data.getString("pointerButtons", null), DEFAULT_POINTER_BUTTONS); }
    String pointerButtonsName() { return buttonsName(pointerButtons()); }

    static String buttonsName(int[] keys) {
        StringBuilder out = new StringBuilder();
        for (int key : keys) { if (out.length() > 0) out.append(" + "); out.append(buttonName(key)); }
        return out.toString();
    }
    static String buttonName(int key) {
        return switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A -> "A";
            case KeyEvent.KEYCODE_BUTTON_B -> "B";
            case KeyEvent.KEYCODE_BUTTON_C -> "C";
            case KeyEvent.KEYCODE_BUTTON_X -> "X";
            case KeyEvent.KEYCODE_BUTTON_Y -> "Y";
            case KeyEvent.KEYCODE_BUTTON_Z -> "Z";
            case KeyEvent.KEYCODE_BUTTON_L1 -> "L1";
            case KeyEvent.KEYCODE_BUTTON_R1 -> "R1";
            case KeyEvent.KEYCODE_BUTTON_L2 -> "L2";
            case KeyEvent.KEYCODE_BUTTON_R2 -> "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3";
            case KeyEvent.KEYCODE_BUTTON_START -> "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT -> "Select";
            case KeyEvent.KEYCODE_BUTTON_MODE -> "Guide";
            case KeyEvent.KEYCODE_DPAD_UP -> "D-pad up";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad down";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad left";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad right";
            case KeyEvent.KEYCODE_DPAD_CENTER -> "D-pad center";
            case KeyEvent.KEYCODE_BACK -> "Back";
            default -> {
                String name = KeyEvent.keyCodeToString(key).replace("KEYCODE_BUTTON_", "").replace("KEYCODE_", "");
                yield name.isEmpty() ? "Button " + key : name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.US).replace('_', ' ');
            }
        };
    }
    String action(int key) {
        String fallback = switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A -> "Click";
            case KeyEvent.KEYCODE_BUTTON_B -> "Back";
            case KeyEvent.KEYCODE_BUTTON_X -> "Double click";
            case KeyEvent.KEYCODE_BUTTON_Y -> "Long press";
            case KeyEvent.KEYCODE_BUTTON_START -> "Home";
            case KeyEvent.KEYCODE_BUTTON_SELECT -> "Recents";
            case KeyEvent.KEYCODE_BUTTON_L1 -> "Volume down";
            case KeyEvent.KEYCODE_BUTTON_R1 -> "Volume up";
            default -> "Pass through";
        };
        return data.getString("map_" + key, fallback);
    }
}
