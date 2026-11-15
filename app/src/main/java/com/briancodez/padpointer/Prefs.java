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
