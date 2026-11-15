package com.briancodez.padpointer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.ProgressBar;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener, InputManager.InputDeviceListener {
    private static final int PICK_CURSOR = 20;
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private LinearLayout page;
    private TextView status;
    private Switch enable;
    private CursorView cursorPreview;
    private Spinner stylePicker;
    private TextView colorSummary, imageSummary, stickHeader, leftValues, rightValues;
    private ProgressBar leftMeter, rightMeter;
    private Button importButton;
    private InputManager inputs;
    private int testDeviceId = -1;
    private float leftX, leftY, rightX, rightY;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        page.setPadding(dp(24), dp(32), dp(24), dp(32));
        scroll.addView(page); setContentView(scroll);
        label("PADPOINTER", 12, 0xFF7BE0C3).setLetterSpacing(0.2f);
        TextView title = label("Your controller.\nYour whole Android.", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label("Move, tap, scroll, and type with the keyboard you already use.", 16, 0xFFB7C5CE);
        status = label("", 14, 0xFF7BE0C3);
        button("1 · Enable accessibility service", () -> new AlertDialog.Builder(this)
                .setTitle("Control Android with your gamepad")
                .setMessage("PadPointer uses accessibility to receive controller input, draw a pointer, tap the screen, and use system navigation. It reads window information. Taps can change data in the apps you control. PadPointer has no network permission and does not record text or screen content.\n\nIn the next screen, choose PadPointer controller mouse and turn it on.")
                .setPositiveButton("Open settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null).show());
        enable = toggle("2 · Pointer enabled", "enabled", false);
        enable.setOnCheckedChangeListener((view, checked) -> {
            prefs.data.edit().putBoolean("enabled", checked).apply();
            if (checked && !PointerService.connected) new AlertDialog.Builder(this).setMessage("Enable PadPointer in accessibility settings first.").setPositiveButton("OK", null).show();
            refresh();
        });
        section("Controls");
        label("L3 · Pointer on / off\nLeft stick · Move     Right stick · Scroll\nD-pad · Precision move\nA · Tap; hold to drag\nB · Back     X · Double tap     Y · Long press\nStart · Home     Select · Recents\nL1 / R1 · Volume down / up\nR3 · Pass controller controls to your keyboard; press again to return", 15, 0xFFD8E3E9);
        section("Native keyboard");
        label("Text fields receive a screen tap and keep focus. Android opens your selected keyboard. Use the pointer to tap its keys, or press R3 if your keyboard supports controller navigation.", 15, 0xFFB7C5CE);
        toggle("Show on-screen keyboard with a controller connected", "keyboard", true);
        label("The keyboard and target app retain their own behavior, just as with a finger tap. This option allows the keyboard when Android identifies your controller as a hardware keyboard.", 13, 0xFFB7C5CE);
        label("Android 14+ · Joystick capture leaves app focus intact.", 13, 0xFF7BE0C3);
        section("Cursor appearance");
        cursorPreview = new CursorView(this);
        cursorPreview.setShowHotspot(true);
        cursorPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cursorPreview.setContentDescription("Live cursor preview");
        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setGravity(android.view.Gravity.CENTER);
        previewRow.setPadding(dp(16), dp(16), dp(16), dp(16));
        previewRow.setBackgroundColor(0xFF24333F);
        previewRow.addView(cursorPreview, new LinearLayout.LayoutParams(dp(prefs.size()), dp(prefs.size())));
        page.addView(previewRow, new LinearLayout.LayoutParams(-1, dp(164)));
        stylePicker = new Spinner(this);
        stylePicker.setContentDescription("Cursor style");
        stylePicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Prefs.CURSOR_NAMES));
        stylePicker.setSelection(styleIndex());
        page.addView(stylePicker, new LinearLayout.LayoutParams(-1, dp(52)));
        stylePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = Prefs.CURSOR_IDS[position];
                if (selected.equals(prefs.cursorStyle())) return;
                if (selected.equals("image") && !CursorImage.file(MainActivity.this).exists()) {
                    stylePicker.setSelection(styleIndex()); pickImage(); return;
                }
                prefs.data.edit().putString("cursorStyle", selected).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        colorSummary = label("", 14, 0xFFD8E3E9);
        button("Choose cursor color", this::chooseColor);
        importButton = button("Import cursor image…", this::pickImage);
        imageSummary = label("", 13, 0xFFB7C5CE);
        label("Color applies to built-in styles. Imported images keep their own colors and transparency. The preview marker shows the image's click point: 0% / 0% is the top-left, 50% / 50% is the center.", 13, 0xFFB7C5CE);
        slider("Image click point X", "imageHotspotX", 0, 100, 0, "%");
        slider("Image click point Y", "imageHotspotY", 0, 100, 0, "%");
        section("Pointer feel");
        slider("Speed", "speed", 200, 2200, 900, " px / second");
        slider("Acceleration curve", "curve", 10, 30, 16, " / 10");
        slider("Stick deadzone", "deadzone", 5, 40, 15, "%");
        slider("Cursor size", "size", 12, 128, 30, " dp");
        slider("Scroll speed", "scroll", 25, 200, 100, "%");
        slider("Auto-hide after", "timeout", 0, 30, 5, " seconds (0 = never)");
        slider("Repeat-click interval", "repeatMs", 100, 3000, 500, " ms");
        toggle("Controller vibration", "haptics", true);
        section("Stick input test");
        stickHeader = label("", 14, 0xFFB7C5CE);
        leftValues = label("", 14, 0xFF7BE0C3);
        leftMeter = meter("Left stick magnitude", 0xFF7BE0C3);
        rightValues = label("", 14, 0xFF84BFFF);
        rightMeter = meter("Right stick magnitude", 0xFF84BFFF);
        label("Turn the pointer off, then move either stick. Readings show raw axes, magnitude, and whether the stick is inside your deadzone. Left-stick output also reflects your acceleration curve.", 13, 0xFFB7C5CE);
        section("Button mapping");
        mapping("A", KeyEvent.KEYCODE_BUTTON_A); mapping("B", KeyEvent.KEYCODE_BUTTON_B);
        mapping("X", KeyEvent.KEYCODE_BUTTON_X); mapping("Y", KeyEvent.KEYCODE_BUTTON_Y);
        mapping("L1", KeyEvent.KEYCODE_BUTTON_L1); mapping("R1", KeyEvent.KEYCODE_BUTTON_R1);
        mapping("L2", KeyEvent.KEYCODE_BUTTON_L2); mapping("R2", KeyEvent.KEYCODE_BUTTON_R2);
        mapping("Start", KeyEvent.KEYCODE_BUTTON_START); mapping("Select", KeyEvent.KEYCODE_BUTTON_SELECT);
        label("L3 and R3 stay reserved so you can always leave pointer mode. Repeat click uses a saved location; press the mapped button again to stop. Pointer off, screen off, and controller disconnect also stop it.", 13, 0xFFB7C5CE);
        button("Allow brightness control (optional)", () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
        section("Try your keyboard");
        label("Move the pointer over a field and press A. Your selected keyboard should open and stay open. These fields do not save anything.", 15, 0xFFB7C5CE);
        field("Text", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field("Email", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        field("Number", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Stop pointer", () -> { prefs.data.edit().putBoolean("enabled", false).apply(); refresh(); });
        label("PadPointer 0.2 · Local controller control\nNo account. No ads. No network access.", 12, 0xFF8195A4);
        refreshAppearance(); updateStickTest();
        refresh();
    }
    @Override public void onStart() { super.onStart(); prefs.data.registerOnSharedPreferenceChangeListener(this); }
    @Override public void onStop() { prefs.data.unregisterOnSharedPreferenceChangeListener(this); super.onStop(); }
    @Override public void onResume() {
        super.onResume();
        inputs = (InputManager) getSystemService(INPUT_SERVICE);
        inputs.registerInputDeviceListener(this, null);
        if (prefs != null) { refresh(); refreshAppearance(); updateStickTest(); }
    }
    @Override public void onPause() {
        if (inputs != null) inputs.unregisterInputDeviceListener(this);
        leftX = leftY = rightX = rightY = 0; testDeviceId = -1;
        super.onPause();
    }
    @Override public void onDestroy() { imageWorker.shutdown(); super.onDestroy(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences data, String key) {
        refresh(); refreshAppearance();
        if ("enabled".equals(key)) { leftX = leftY = rightX = rightY = 0; testDeviceId = -1; }
        updateStickTest();
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!prefs.enabled() && event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            testDeviceId = event.getDeviceId();
            leftX = event.getAxisValue(MotionEvent.AXIS_X); leftY = event.getAxisValue(MotionEvent.AXIS_Y);
            rightX = GamepadAxes.rightX(event); rightY = GamepadAxes.rightY(event);
            updateStickTest(); return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public void onInputDeviceAdded(int deviceId) { }
    @Override public void onInputDeviceChanged(int deviceId) { }
    @Override public void onInputDeviceRemoved(int deviceId) {
        if (deviceId == testDeviceId) { testDeviceId = -1; leftX = leftY = rightX = rightY = 0; updateStickTest(); }
    }
    private ProgressBar meter(String description, int color) {
        ProgressBar meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(1000); meter.setProgressTintList(ColorStateList.valueOf(color)); meter.setContentDescription(description);
        page.addView(meter, new LinearLayout.LayoutParams(-1, dp(20))); return meter;
    }
    private void updateStickTest() {
        if (stickHeader == null) return;
        InputDevice device = testDeviceId == -1 ? null : InputDevice.getDevice(testDeviceId);
        stickHeader.setText(prefs.enabled() ? "Paused · turn the pointer off to test" : device == null ? "Pointer off · move a connected controller stick" : device.getName());
        updateStick(leftValues, leftMeter, "L-stick · Move", leftX, leftY, true);
        updateStick(rightValues, rightMeter, "R-stick · Scroll", rightX, rightY, false);
    }
    private void updateStick(TextView text, ProgressBar meter, String name, float x, float y, boolean moving) {
        float magnitude = (float) Math.hypot(x, y);
        boolean active = magnitude > prefs.deadzone();
        float[] velocity = PointerMath.velocity(x, y, prefs.deadzone(), prefs.curve());
        String output = moving ? String.format(Locale.US, " · output %.2f", Math.hypot(velocity[0], velocity[1])) : "";
        text.setText(String.format(Locale.US, "%s\nX %+.2f   Y %+.2f   · magnitude %.2f\n%s (%.2f)%s", name, x, y, magnitude, active ? "Outside deadzone" : "Inside deadzone", prefs.deadzone(), output));
        meter.setProgress(Math.min(1000, Math.round(magnitude * 1000)));
    }
    private int styleIndex() { return Math.max(0, Arrays.asList(Prefs.CURSOR_IDS).indexOf(prefs.cursorStyle())); }
    private void refreshAppearance() {
        if (cursorPreview == null) return;
        cursorPreview.applyAppearance(prefs);
        cursorPreview.setLayoutParams(new LinearLayout.LayoutParams(dp(prefs.size()), dp(prefs.size())));
        if (stylePicker.getSelectedItemPosition() != styleIndex()) stylePicker.setSelection(styleIndex());
        colorSummary.setText(String.format(Locale.US, "Cursor color · #%06X", prefs.cursorColor() & 0xFFFFFF));
        imageSummary.setText(CursorImage.file(this).exists() ? "Custom image saved on this device · import again to replace it" : "Import a PNG, WebP, or JPEG; transparent PNG works well for cursors");
    }
    private void chooseColor() {
        String[] names = {"Mint", "Orange", "White", "Black", "Blue", "Red", "Green", "Custom hex color…"};
        int[] colors = {0xFF7BE0C3, 0xFFFFA000, 0xFFFFFFFF, 0xFF000000, 0xFF2196F3, 0xFFF44336, 0xFF4CAF50};
        new AlertDialog.Builder(this).setTitle("Cursor color").setItems(names, (dialog, which) -> {
            if (which < colors.length) prefs.data.edit().putInt("cursorColor", colors[which]).apply();
            else customColor();
        }).show();
    }
    private void customColor() {
        EditText edit = new EditText(this);
        edit.setSingleLine(true); edit.setHint("#FFA000"); edit.setContentDescription("Hex RGB cursor color");
        edit.setText(String.format(Locale.US, "#%06X", prefs.cursorColor() & 0xFFFFFF));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Custom cursor color").setView(edit)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = edit.getText().toString().trim();
            if (!value.matches("#?[0-9a-fA-F]{6}")) { edit.setError("Enter six hex digits, such as #FFA000"); return; }
            prefs.data.edit().putInt("cursorColor", Color.parseColor(value.startsWith("#") ? value : "#" + value)).apply();
            dialog.dismiss();
        }));
        dialog.show();
    }
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_CURSOR);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_CURSOR || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); Context application = getApplicationContext();
        importButton.setEnabled(false);
        imageWorker.execute(() -> {
            String error = null;
            try {
                CursorImage.importImage(application, uri);
                new Prefs(application).data.edit().putLong("imageRevision", System.nanoTime()).putString("cursorStyle", "image").apply();
            } catch (Exception failure) { error = "Could not import that image. Choose a readable PNG, WebP, or JPEG."; }
            String message = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                importButton.setEnabled(true); refreshAppearance();
                Toast.makeText(this, message == null ? "Custom cursor imported" : message, Toast.LENGTH_LONG).show();
            });
        });
    }
    private void refresh() {
        if (status == null) return;
        status.setText(!PointerService.connected ? "Service is off · enable it below" : prefs.enabled() ? "Service connected · pointer on" : "Service connected · press L3 or enable below");
        if (enable != null && enable.isChecked() != prefs.enabled()) enable.setChecked(prefs.enabled());
    }
    private TextView label(String value, int sp, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(sp); text.setTextColor(color);
        text.setPadding(0, dp(8), 0, dp(10)); text.setLineSpacing(dp(3), 1); page.addView(text); return text;
    }
    private void section(String value) { TextView text = label(value, 20, Color.WHITE); text.setPadding(0, dp(30), 0, dp(8)); text.setTypeface(Typeface.DEFAULT, Typeface.BOLD); }
    private Button button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false);
        button.setTextColor(0xFF101820); button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF7BE0C3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54)); lp.setMargins(0, dp(10), 0, dp(8));
        page.addView(button, lp); button.setOnClickListener(view -> action.run());
        return button;
    }
    private Switch toggle(String value, String key, boolean fallback) {
        Switch view = new Switch(this); view.setText(value); view.setTextSize(15); view.setTextColor(0xFFE7EFF3);
        view.setPadding(0, dp(12), 0, dp(12)); view.setChecked(prefs.data.getBoolean(key, fallback));
        view.setOnCheckedChangeListener((button, checked) -> prefs.data.edit().putBoolean(key, checked).apply());
        page.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void slider(String title, String key, int min, int max, int fallback, String unit) {
        TextView text = label(title + " · " + prefs.data.getInt(key, fallback) + unit, 14, 0xFFD8E3E9);
        SeekBar slider = new SeekBar(this); slider.setMax(max - min); slider.setProgress(prefs.data.getInt(key, fallback) - min);
        slider.setContentDescription(title); page.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) { int value = min + progress; text.setText(title + " · " + value + unit); if (fromUser) prefs.data.edit().putInt(key, value).apply(); }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
        });
    }
    private void mapping(String name, int key) {
        LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView text = new TextView(this); text.setText(name); text.setTextSize(16); text.setTextColor(Color.WHITE);
        row.addView(text, new LinearLayout.LayoutParams(dp(70), dp(52)));
        Spinner spinner = new Spinner(this); spinner.setContentDescription(name + " action");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Prefs.ACTIONS);
        spinner.setAdapter(adapter); spinner.setSelection(Arrays.asList(Prefs.ACTIONS).indexOf(prefs.action(key)));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { String value = Prefs.ACTIONS[position]; if (!value.equals(prefs.action(key))) prefs.data.edit().putString("map_" + key, value).apply(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(0, dp(52), 1)); page.addView(row);
    }
    private void field(String hint, int inputType) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setInputType(inputType);
        edit.setTextColor(Color.WHITE); edit.setHintTextColor(0xFF9DB0BC); edit.setSingleLine(true);
        edit.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xFF1B2933); background.setCornerRadius(dp(10)); background.setStroke(dp(1), 0xFF3D5667); edit.setBackground(background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56)); lp.setMargins(0, dp(8), 0, dp(6)); page.addView(edit, lp);
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
