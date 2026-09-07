package me.perdomo.dpc;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Device Owner entry point.
 *
 * <p>Referenced by name from the provisioning QR payload and from
 * {@code adb shell dpm set-device-owner}, so its fully qualified name is part
 * of this app's external contract. Renaming it invalidates existing QR codes.</p>
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(PolicyManager.TAG, "Device admin enabled");
        // Applied here as well as on provisioning complete, because
        // `adb dpm set-device-owner` fires onEnabled but not the
        // provisioning callback.
        PolicyManager.applyAll(context);
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
