package com.briancodez.padpointer;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** App-process Shizuku connection. All lifecycle/UI methods run on main; input IPC runs on worker. */
final class WheelBridge {
    private static WheelBridge instance;
    static WheelBridge get(Context context) {
        if (instance == null) instance = new WheelBridge(context.getApplicationContext());
        return instance;
    }
    private static final int PERMISSION_REQUEST = 401;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LatestWheelFrame mailbox = new LatestWheelFrame();
    private final HashSet<Runnable> listeners = new HashSet<>();
    private final Shizuku.UserServiceArgs args;
    private ServiceConnection connection;
    private volatile IWheelService remote;
    private volatile int generation;
    private boolean enabled, draining;
    private String status = "Accessibility scrolling selected";

    private WheelBridge(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, WheelUserService.class))
                .processNameSuffix("wheel").daemon(false).version(9);
        Shizuku.addBinderReceivedListenerSticky(() -> { if (enabled) connect(false); });
        Shizuku.addBinderDeadListener(() -> {
            disconnect();
            state(enabled ? "Shizuku stopped · start it, then reconnect" : "Accessibility scrolling selected");
        });
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code != PERMISSION_REQUEST || !enabled) return;
            if (result == PackageManager.PERMISSION_GRANTED) connect(false);
            else state("Permission denied · allow PadPointer in Shizuku, then reconnect");
        });
    }

    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }
    String status() { return status; }
    boolean ready() { return remote != null; }

    void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (value) connect(false);
        else { disconnect(); state("Accessibility scrolling selected"); }
    }

    void connect(boolean requestPermission) {
        if (!enabled || connection != null) return;
        try {
            if (!Shizuku.pingBinder()) { state("Shizuku is not running · open Shizuku and start it"); return; }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) { state("Update Shizuku to version 13 or newer"); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state("Permission needed · tap Connect and allow PadPointer");
                if (requestPermission) Shizuku.requestPermission(PERMISSION_REQUEST);
                return;
            }
            state("Connecting to Shizuku…");
            ServiceConnection next = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    if (connection != this || !enabled) return;
                    IWheelService service = IWheelService.Stub.asInterface(binder);
                    worker.execute(() -> {
                        String error;
                        try { error = service.initialize(); }
                        catch (RemoteException | RuntimeException e) { error = "Shizuku connection failed · tap Connect to retry"; }
                        String result = error;
                        main.post(() -> {
                            if (connection != this || !enabled) return;
                            if (result.isEmpty()) { remote = service; state("Connected · direct wheel scrolling ready"); }
                            else { disconnect(); state(result); }
                        });
                    });
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    if (connection != this) return;
                    disconnect(); state("Scrolling connection lost · tap Connect to retry");
                }
            };
            generation++;
            connection = next;
            Shizuku.bindUserService(args, next);
            main.postDelayed(() -> {
                if (connection == next && remote == null) {
                    disconnect(); state("Connection timed out · restart Shizuku and tap Connect");
                }
            }, 10000);
        } catch (RuntimeException e) {
            disconnect(); state("Unable to connect · check Shizuku permission and tap Connect");
        }
    }

    private void disconnect() {
        remote = null;
        generation++;
        stop();
        ServiceConnection old = connection; connection = null;
        if (old != null && Shizuku.pingBinder()) {
            try { Shizuku.unbindUserService(args, old, true); }
            catch (RuntimeException ignored) { /* Server may have stopped between ping and unbind. */ }
        }
    }

    void stop() { mailbox.clear(); }

    void scroll(float x, float y, float h, float v, long time) {
        if (!ready() || (h == 0 && v == 0)) { stop(); return; }
        synchronized (mailbox) {
            mailbox.offer(new LatestWheelFrame.Frame(x, y, h, v, time));
            if (draining) return;
            draining = true;
        }
        worker.execute(this::drain);
    }

    private void drain() {
        while (true) {
            LatestWheelFrame.Frame frame;
            synchronized (mailbox) {
                frame = mailbox.take(SystemClock.uptimeMillis());
                if (frame == null) { draining = false; return; }
            }
            int sentGeneration = generation;
            IWheelService service = remote;
            if (service == null) continue;
            boolean accepted;
            try { accepted = service.scroll(frame.x(), frame.y(), frame.h(), frame.v(), frame.time()); }
            catch (RemoteException | RuntimeException e) { accepted = false; }
            if (!accepted) {
                // Stop immediately on this thread; don't accept more frames while main is busy.
                if (generation == sentGeneration && remote == service) remote = null;
                stop();
                main.post(() -> {
                    if (generation != sentGeneration || remote != null) return;
                    disconnect();
                    state("Android rejected scrolling · check Shizuku access and tap Connect, or use Accessibility");
                });
            }
        }
    }

    private void state(String value) {
        status = value;
        for (Runnable listener : new HashSet<>(listeners)) listener.run();
    }
}
