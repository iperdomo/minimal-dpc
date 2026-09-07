package me.perdomo.dpc;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * The two handshake screens QR provisioning requires on Android 11+.
 *
 * <p>Neither shows any UI. They exist because {@code ManagedProvisioning}
 * insists on them: a DPC that targets API 30 or higher and does not answer
 * both is refused part-way through with "Can't set up device - Contact your IT
 * admin for help", after the APK has already been downloaded, checksum-checked
 * and installed. The failure says nothing about what is missing.</p>
 *
 * <p>{@code adb shell dpm set-device-owner} does not go through this flow at
 * all, which is why the adb path can work on a device where the QR path
 * fails.</p>
 *
 * <ul>
 *   <li>{@code GET_PROVISIONING_MODE} asks what to provision. This DPC only
 *       ever manages whole devices, so it answers
 *       {@code PROVISIONING_MODE_FULLY_MANAGED_DEVICE} without asking
 *       anyone.</li>
 *   <li>{@code ADMIN_POLICY_COMPLIANCE} is the post-provisioning handoff,
 *       where a DPC would normally make the user agree to something. There is
 *       nothing to agree to here, so it returns immediately; the real setup
 *       work happens on the maintenance screen afterwards.</li>
 * </ul>
 *
 * <p>Both are declared with {@code BIND_DEVICE_ADMIN} in the manifest so only
 * the system can start them.</p>
 */
public class ProvisioningActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent() == null ? null : getIntent().getAction();
        Log.i(PolicyManager.TAG, "Provisioning handshake: " + action);

        if (DevicePolicyManager.ACTION_GET_PROVISIONING_MODE.equals(action)) {
            Intent result = new Intent();
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE);
            setResult(RESULT_OK, result);
        } else {
            // ADMIN_POLICY_COMPLIANCE, or anything else the platform decides to
            // send here. RESULT_OK means "nothing left to do, carry on".
            setResult(RESULT_OK);
        }
        finish();
    }
}
