package me.perdomo.dpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Logs the outcome of a silent uninstall requested by {@link PolicyManager}. */
public class UninstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(PolicyManager.TAG, "Uninstalled unapproved app " + pkg);
        } else {
            // The app stays hidden either way, so it cannot be launched.
            Log.w(PolicyManager.TAG, "Uninstall of " + pkg + " failed (status "
                    + status + "): "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
