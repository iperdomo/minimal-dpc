package me.perdomo.dpc;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Device Owner entry point.
 *
 * <p>Referenced by name from the provisioning QR payload and from
 * {@code adb shell dpm set-device-owner}, so its fully qualified name is part
 * of this app's external contract. Renaming it invalidates existing QR codes.</p>
 */
public class AdminReceiver extends DeviceAdminReceiver {

    /**
     * Fired when the admin is activated. Note this is *not* the same instant
     * as becoming Device Owner.
     *
     * <p>{@code adb shell dpm set-device-owner} activates the admin first and
     * records ownership immediately afterwards, so this callback frequently
     * arrives while {@link PolicyManager#isDeviceOwner} is still false. The
     * old unconditional {@code applyAll} here logged "Not device owner -
     * nothing to apply" and returned, which left the device owned but with no
     * policy applied at all - a silent failure that looks exactly like a
     * successful provision.</p>
     *
     * <p>So: try immediately, and if ownership has not landed yet, poll
     * briefly. {@link #OWNERSHIP_ATTEMPTS} attempts at
     * {@link #OWNERSHIP_RETRY_MS} covers the gap with room to spare. The
     * broadcast is kept alive across the retries with {@code goAsync()}.
     * Anything slower than that window is picked up by {@link BootReceiver} or
     * the periodic sweep, so the failure mode is a delay, never a device that
     * stays unenforced forever.</p>
     */
    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(PolicyManager.TAG, "Device admin enabled");
        if (PolicyManager.isDeviceOwner(context)) {
            PolicyManager.applyAll(context);
            return;
        }
        Log.i(PolicyManager.TAG, "Not device owner yet - waiting for ownership");
        awaitOwnership(context, goAsync(), 0);
    }

    /** Number of times to re-check for ownership after onEnabled. */
    private static final int OWNERSHIP_ATTEMPTS = 20;

    /** Delay between those checks, in milliseconds. */
    private static final long OWNERSHIP_RETRY_MS = 250L;

    private static void awaitOwnership(Context context, PendingResult pending, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (PolicyManager.isDeviceOwner(context)) {
                Log.i(PolicyManager.TAG, "Ownership confirmed after "
                        + (attempt + 1) * OWNERSHIP_RETRY_MS + "ms - applying policy");
                PolicyManager.applyAll(context);
                pending.finish();
            } else if (attempt + 1 < OWNERSHIP_ATTEMPTS) {
                awaitOwnership(context, pending, attempt + 1);
            } else {
                // Not an error on its own: onProfileProvisioningComplete may
                // still be on its way, and both BootReceiver and the sweep
                // reapply unconditionally.
                Log.w(PolicyManager.TAG, "Ownership did not arrive within "
                        + OWNERSHIP_ATTEMPTS * OWNERSHIP_RETRY_MS
                        + "ms - leaving it to provisioning-complete or the sweep");
                pending.finish();
            }
        }, OWNERSHIP_RETRY_MS);
    }

    /**
     * Fired at the end of QR / NFC provisioning. At this point the VPN client
     * is usually not installed yet, so {@link PolicyManager#applyVpn} will
     * report "not installed" - that is expected. Install the VPN app, then
     * press "Apply lockdown" on the maintenance screen.
     */
    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        Log.i(PolicyManager.TAG, "Provisioning complete");
        PolicyManager.applyAll(context);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.w(PolicyManager.TAG, "Device admin disabled");
        EnforcementJobService.cancel(context);
    }
}
