package me.perdomo.dpc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps a runtime-registered package receiver alive.
 *
 * <p>ACTION_PACKAGE_ADDED is not on the implicit-broadcast exception list, so a
 * manifest-declared receiver for it is never delivered to apps targeting API 26
 * or higher. Registering at runtime still works - but only while a process is
 * alive to hold the registration, which is what this service is for.</p>
 *
 * <p>Without it, enforcement latency is whatever {@link EnforcementJobService}
 * manages, and the platform will not schedule that more often than every 15
 * minutes. With the Play Store left usable, that is a long time for an
 * unapproved app to be running.</p>
 */
public class WatchdogService extends Service {

    private static final String CHANNEL_ID = "lockdown";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean running;

    private PackageMonitorReceiver receiver;

    /** Whether the watchdog process is currently alive and registered. */
    public static boolean isRunning() {
        return running;
    }

    public static void start(Context ctx) {
        if (!Policy.RUN_INSTALL_WATCHDOG || !PolicyManager.isDeviceOwner(ctx)) {
            return;
        }
        Intent intent = new Intent(ctx, WatchdogService.class);
        try {
            ctx.startForegroundService(intent);
        } catch (Exception e) {
            Log.w(PolicyManager.TAG, "Could not start watchdog", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, WatchdogService.class));
        } catch (Exception e) {
            Log.w(PolicyManager.TAG, "Could not stop watchdog", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();

        if (receiver == null) {
            receiver = new PackageMonitorReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            registerReceiver(receiver, filter);
            running = true;
            Log.i(PolicyManager.TAG, "Install watchdog running");
        }

        // Catch anything installed while the process was dead.
        PolicyManager.enforceAllowlist(this);

        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Device policy", NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Device policy active")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A Device Owner qualifies as system-exempted. Fall back to
            // special-use on builds that refuse that type.
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } catch (Exception e) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            }
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
                // Already gone.
            }
            receiver = null;
        }
        running = false;
        Log.i(PolicyManager.TAG, "Install watchdog stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
