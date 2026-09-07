package me.perdomo.dpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Reacts to a newly installed package, within a second or so of it appearing.
 *
 * <p>Registered at runtime by {@link WatchdogService}, never in the manifest:
 * ACTION_PACKAGE_ADDED is not on the implicit-broadcast exception list, so a
 * manifest declaration is silently never delivered to an app targeting API 26
 * or higher. Runtime registration still works, which is why the watchdog
 * service has to stay alive to hold it.</p>
 */
public class PackageMonitorReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        String pkg = data.getSchemeSpecificPart();
        if (pkg == null || pkg.equals(context.getPackageName())) {
            return;
        }
        if (PolicyManager.inMaintenanceWindow(context)) {
            Log.i(PolicyManager.TAG, "Allowing " + pkg + " - maintenance window open");
            return;
        }
        if (PolicyManager.isApproved(context, pkg)) {
            Log.i(PolicyManager.TAG, "Approved app installed: " + pkg);
            return;
        }
        Log.w(PolicyManager.TAG, "Unapproved app installed: " + pkg);
        PolicyManager.enforceOne(context, pkg);
    }
}
