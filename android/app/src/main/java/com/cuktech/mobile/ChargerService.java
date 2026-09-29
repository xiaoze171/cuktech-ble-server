package com.cuktech.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the Python runtime and GATT transport independently of the visible activity. */
public final class ChargerService extends Service {
    public static final String ACTION_START = "com.cuktech.mobile.START";
    public static final String ACTION_STOP = "com.cuktech.mobile.STOP";
    public static final String ACTION_STATE = "com.cuktech.mobile.STATE";
    private static final String CHANNEL = "charger_connection";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "ChargerService";

    // Shared between service instances: destruction and a rapid recreation cannot race Python.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "charger-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Snapshot snapshot = new Snapshot("stopped", "", "");
    private static ChargerService snapshotOwner;

    public static final class Snapshot {
        public final String state;
        public final String url;
        public final String error;
        Snapshot(String state, String url, String error) {
            this.state = state;
            this.url = url;
            this.error = error;
        }
    }

    public static Snapshot snapshot() { return snapshot; }

    public static boolean hasBluetoothPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RuntimeGeneration generation = new RuntimeGeneration();
    private volatile boolean destroyed;
    private int latestStartId;
    // Worker-thread owned fields.
    private PyObject runtime;
    private GattBridge bridge;

    @Override public void onCreate() {
        super.onCreate();
        snapshotOwner = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_description));
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop(startId);
            return START_NOT_STICKY;
        }
        if (!hasBluetoothPermissions(this)) {
            setSnapshot("error", "", getString(R.string.permission_required));
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification(getString("running".equals(snapshot.state)
                    ? R.string.notification_running : R.string.preparing));
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Cannot enter foreground", error);
            setSnapshot("error", "", getString(R.string.service_start_failed) + "\n" + error.getMessage());
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        long token = generation.requestStart();
        if (token == 0) return KeepAlivePreferences.isEnabled(this) ? START_STICKY : START_NOT_STICKY;
        setSnapshot("starting", "", "");
        WORKER.execute(() -> initialize(token, startId));
        return KeepAlivePreferences.isEnabled(this) ? START_STICKY : START_NOT_STICKY;
    }

    private void initialize(long token, int startId) {
        if (destroyed || !generation.isCurrent(token)) return;
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
            runtime = Python.getInstance().getModule("android_runtime");
            // A prior service's shutdown may have timed out. Never reuse its closed bridge.
            runtime.callAttr("stop");
            bridge = new GattBridge(getApplicationContext());
            String url = runtime.callAttr("start", getFilesDir().getAbsolutePath(), bridge).toString();
            LocalUrlPolicy policy = new LocalUrlPolicy(url);
            main.post(() -> {
                if (destroyed || !generation.isCurrent(token)) return;
                setSnapshot("running", policy.baseUrl, "");
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification(getString(R.string.notification_running)));
                scheduleHealthCheck(token, startId);
            });
        } catch (Exception error) {
            Log.e(TAG, "Runtime startup failed", error);
            stopRuntime();
            reportFailure(token, startId, error.getMessage());
        }
    }

    private void scheduleHealthCheck(long token, int startId) {
        main.postDelayed(() -> {
            if (destroyed || !generation.isCurrent(token)) return;
            WORKER.execute(() -> {
                if (destroyed || !generation.isCurrent(token) || runtime == null) return;
                try {
                    JSONObject status = new JSONObject(runtime.callAttr("status").toString());
                    if (!status.optBoolean("running")) {
                        throw new IllegalStateException(status.optString("error", getString(R.string.runtime_stopped)));
                    }
                    String notice = status.optString("notice", "");
                    main.post(() -> {
                        if (destroyed || !generation.isCurrent(token)) return;
                        updateNotification(notice);
                        scheduleHealthCheck(token, startId);
                    });
                } catch (Exception error) {
                    Log.e(TAG, "Runtime health check failed", error);
                    stopRuntime();
                    reportFailure(token, startId, error.getMessage());
                }
            });
        }, 5000);
    }

    /** Keep the foreground notification honest while the BLE stack needs a manual reset. */
    private void updateNotification(String notice) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(NOTIFICATION_ID, notification(
                "ble_stuck_need_radio_reset".equals(notice)
                        ? getString(R.string.notification_ble_stuck)
                        : getString(R.string.notification_running)));
    }

    private void reportFailure(long token, int startId, String detail) {
        main.post(() -> {
            if (destroyed || !generation.isCurrent(token)) return;
            generation.requestStop();
            setSnapshot("error", "", detail == null ? getString(R.string.service_start_failed) : detail);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(latestStartId);
        });
    }

    private void requestStop(int startId) {
        generation.requestStop();
        main.removeCallbacksAndMessages(null);
        setSnapshot("stopping", "", "");
        WORKER.execute(() -> {
            stopRuntime();
            main.post(() -> {
                if (destroyed || generation.isActive()) return;
                setSnapshot("stopped", "", "");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(startId);
            });
        });
    }

    private void stopRuntime() {
        // Release native waits first so Python's shutdown can finish promptly.
        if (bridge != null) {
            try { bridge.close(); } catch (Exception error) { Log.w(TAG, "GATT cleanup failed", error); }
            bridge = null;
        }
        try {
            if (runtime != null) runtime.callAttr("stop");
        } catch (Exception error) {
            Log.w(TAG, "Runtime shutdown reported an error", error);
        } finally {
            runtime = null;
        }
    }

    private void setSnapshot(String state, String url, String error) {
        if (snapshotOwner != this) return;
        snapshot = new Snapshot(state, url, error);
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ChargerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
    }

    @Override public void onDestroy() {
        destroyed = true;
        generation.requestStop();
        main.removeCallbacksAndMessages(null);
        WORKER.execute(this::stopRuntime);
        if (!"error".equals(snapshot.state)) setSnapshot("stopped", "", "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!KeepAlivePreferences.isEnabled(this)) {
            requestStop(latestStartId);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
