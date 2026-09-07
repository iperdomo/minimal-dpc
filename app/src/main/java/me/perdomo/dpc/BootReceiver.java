package me.perdomo.dpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Re-asserts the policy after a reboot and re-arms the periodic sweep. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(PolicyManager.TAG, "Boot completed - reapplying policy");
        // A reboot ends any open maintenance window: an unattended device
        // must never come back up unlocked.
        PolicyManager.endMaintenanceWindow(context);
    }
}
