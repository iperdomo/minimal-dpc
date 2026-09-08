package me.perdomo.dpc;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

/**
 * The only user interface this app has.
 *
 * <p>Locked behind a passcode, because it can undo every policy on the device.
 * With physical access to the hardware this screen is the recovery path: it is
 * how you install a new app, fix a broken VPN profile, or hand the device
 * back.</p>
 */
public class MaintenanceActivity extends Activity {

    private static final int MAX_ATTEMPTS = 3;
    private static final long LOCKOUT_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView status;
    private LinearLayout lockedPanel;
    private LinearLayout adminPanel;
    private EditText passcode;
    private Button unlock;
    private Switch vpnAlwaysOn;
    private Switch vpnLockdown;
    private EditText organizationName;
    private LinearLayout approvedList;
    private EditText newPackage;
    private TextView approvedHeader;
    private LinearLayout hiddenList;
    private EditText newHidden;
    private TextView hiddenHeader;

    private int failedAttempts;
    private long lockedOutUntil;

    /** True while refresh() is writing the switches, so listeners stay quiet. */
    private boolean syncingSwitches;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance);

        status = findViewById(R.id.status);
        lockedPanel = findViewById(R.id.lockedPanel);
        adminPanel = findViewById(R.id.adminPanel);
        passcode = findViewById(R.id.passcode);
        unlock = findViewById(R.id.unlock);
        vpnAlwaysOn = findViewById(R.id.vpnAlwaysOn);
        vpnLockdown = findViewById(R.id.vpnLockdown);
        organizationName = findViewById(R.id.organizationName);
        approvedList = findViewById(R.id.approvedList);
        newPackage = findViewById(R.id.newPackage);
        approvedHeader = findViewById(R.id.approvedHeader);
        hiddenList = findViewById(R.id.hiddenList);
        newHidden = findViewById(R.id.newHidden);
        hiddenHeader = findViewById(R.id.hiddenHeader);

        vpnAlwaysOn.setOnCheckedChangeListener((b, checked) -> onVpnSwitch(true, checked));
        vpnLockdown.setOnCheckedChangeListener((b, checked) -> onVpnSwitch(false, checked));

        unlock.setOnClickListener(v -> attemptUnlock());
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.applyLockdown).setOnClickListener(v -> applyLockdown());
        findViewById(R.id.maintenanceMode).setOnClickListener(v -> beginMaintenance());
        findViewById(R.id.setOrganizationName).setOnClickListener(v -> setOrganizationName());
        findViewById(R.id.addPackage).setOnClickListener(v -> addApproved());
        findViewById(R.id.addHidden).setOnClickListener(v -> addHidden());
        findViewById(R.id.releaseOwnership).setOnClickListener(v -> confirmRelease());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Re-lock as soon as the screen loses focus. Leaving the admin panel
        // open in the recents list would defeat the passcode entirely.
        relock();
    }

    // ------------------------------------------------------------------

    private void attemptUnlock() {
        if (Passcode.isUnconfigured()) {
            toast("No passcode set. See tools/hash-passcode.py");
            return;
        }
        if (System.currentTimeMillis() < lockedOutUntil) {
            toast("Too many attempts - wait 30 seconds");
            return;
        }
        String entered = passcode.getText().toString();
        if (TextUtils.isEmpty(entered)) {
            return;
        }
        unlock.setEnabled(false);
        char[] chars = entered.toCharArray();

        // PBKDF2 at 120k iterations is slow enough to be worth moving off the
        // main thread on low-end hardware.
        new Thread(() -> {
            boolean ok = Passcode.verify(chars);
            handler.post(() -> {
                unlock.setEnabled(true);
                passcode.setText("");
                if (ok) {
                    failedAttempts = 0;
                    lockedPanel.setVisibility(View.GONE);
                    adminPanel.setVisibility(View.VISIBLE);
                    refresh();
                } else if (++failedAttempts >= MAX_ATTEMPTS) {
                    failedAttempts = 0;
                    lockedOutUntil = System.currentTimeMillis() + LOCKOUT_MS;
                    toast("Too many attempts - wait 30 seconds");
                } else {
                    toast("Wrong passcode");
                }
            });
        }).start();
    }

    private void relock() {
        adminPanel.setVisibility(View.GONE);
        lockedPanel.setVisibility(View.VISIBLE);
        passcode.setText("");
    }

    // ------------------------------------------------------------------

    private void applyLockdown() {
        if (!PolicyManager.isDeviceOwner(this)) {
            toast("Not device owner - cannot apply");
            return;
        }
        PolicyManager.endMaintenanceWindow(this);
        String vpnProblem = PolicyManager.applyVpn(this);
        List<String> hidden = PolicyManager.enforceAllowlist(this);

        StringBuilder msg = new StringBuilder("Lockdown applied");
        if (!hidden.isEmpty()) {
            msg.append(" - hid ").append(hidden.size()).append(" unapproved app(s)");
        }
        if (vpnProblem != null) {
            msg.append("\nVPN NOT set: ").append(vpnProblem);
        }
        toast(msg.toString());
        refresh();
    }

    private void beginMaintenance() {
        PolicyManager.beginMaintenanceWindow(this);
        toast("Install restrictions lifted for "
                + Policy.MAINTENANCE_WINDOW_MINUTES + " minutes.\n"
                + "Press \"Apply lockdown\" when finished.");
        refresh();
    }

    private void confirmRelease() {
        new AlertDialog.Builder(this)
                .setTitle("Release device ownership?")
                .setMessage("Clears always-on VPN, all restrictions and Device "
                        + "Owner status.\n\nDevice Owner cannot be re-established "
                        + "without a factory reset.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Release", (d, w) -> {
                    PolicyManager.releaseOwnership(this);
                    toast("Ownership released");
                    relock();
                    refresh();
                })
                .show();
    }

    /**
     * A VPN switch moved.
     *
     * <p>The stored switch is the intent, and every re-assertion path applies
     * it - so a failure here is not rolled back. Turning always-on on before
     * the VPN client is installed leaves the switch on and the status panel
     * reading "VPN installed : NO"; the sweep applies it as soon as the app
     * arrives, which is exactly the provisioning order in the README.</p>
     */
    private void onVpnSwitch(boolean alwaysOnSwitch, boolean checked) {
        if (syncingSwitches) {
            return;
        }
        if (!PolicyManager.isDeviceOwner(this)) {
            toast("Not device owner - cannot apply");
            refresh();
            return;
        }
        String problem = alwaysOnSwitch
                ? PolicyManager.setVpnAlwaysOn(this, checked)
                : PolicyManager.setVpnLockdown(this, checked);
        if (problem != null) {
            toast("VPN NOT set: " + problem);
        }
        refresh();
    }

    // ------------------------------------------------------------------
    // Organization
    // ------------------------------------------------------------------

    /**
     * Names the organization on the lock screen, or clears the name.
     *
     * <p>Takes effect the moment it is stored - there is no "apply lockdown"
     * step for it - because the whole point is being able to read the result
     * off the lock screen straight away.</p>
     */
    private void setOrganizationName() {
        String name = organizationName.getText().toString().trim();
        String problem = PolicyManager.setOrganizationName(this, name);
        if (problem != null) {
            toast(problem);
            return;
        }
        toast(name.isEmpty()
                ? "Cleared - the lock screen goes back to \"your organization\""
                : "Lock screen now reads \"This device belongs to " + name + "\"");
        refresh();
    }

    // ------------------------------------------------------------------
    // Approved apps
    // ------------------------------------------------------------------

    private void addApproved() {
        String pkg = newPackage.getText().toString().trim();
        String problem = PolicyManager.addApprovedPackage(this, pkg);
        if (problem != null) {
            toast(problem);
            return;
        }
        newPackage.setText("");
        toast(PolicyManager.isInstalled(this, pkg)
                ? "Approved " + pkg
                : "Approved " + pkg + "\nNot installed yet - install it from Play.");
        refresh();
    }

    private void confirmRemove(String pkg) {
        String consequence = PolicyManager.isInstalled(this, pkg)
                ? "\n\nIt is installed, so it will be hidden and uninstalled now."
                : "";
        new AlertDialog.Builder(this)
                .setTitle("Remove from approved apps?")
                .setMessage(pkg + consequence)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> removeApproved(pkg))
                .show();
    }

    private void removeApproved(String pkg) {
        String problem = PolicyManager.removeApprovedPackage(this, pkg);
        if (problem != null) {
            toast(problem);
            refresh();
            return;
        }
        // Removing only edits the list. Acting on it here rather than inside
        // PolicyManager is what lets an open maintenance window survive: the
        // user asked for enforcement to pause, and this is enforcement.
        if (PolicyManager.inMaintenanceWindow(this)) {
            toast("Removed " + pkg + "\nUninstalled when the maintenance window closes.");
        } else if (PolicyManager.isInstalled(this, pkg) && PolicyManager.isDeviceOwner(this)) {
            PolicyManager.enforceOne(this, pkg);
            toast("Removed and uninstalling " + pkg);
        } else {
            toast("Removed " + pkg);
        }
        refresh();
    }

    // ------------------------------------------------------------------
    // Hidden apps
    // ------------------------------------------------------------------

    private void addHidden() {
        String pkg = newHidden.getText().toString().trim();
        String problem = PolicyManager.addHiddenPackage(this, pkg);
        if (problem != null) {
            toast(problem);
            return;
        }
        newHidden.setText("");
        toast(PolicyManager.isInstalled(this, pkg)
                ? "Hidden " + pkg
                : "Added " + pkg + "\nNot on this device - hidden if it ever appears.");
        refresh();
    }

    private void confirmUnhide(String pkg) {
        new AlertDialog.Builder(this)
                .setTitle("Stop hiding this app?")
                .setMessage(pkg + "\n\nIts icon comes back and it can be launched again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Un-hide", (d, w) -> {
                    String problem = PolicyManager.removeHiddenPackage(this, pkg);
                    toast(problem != null ? problem : "Un-hid " + pkg);
                    refresh();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // Row rendering
    // ------------------------------------------------------------------

    /** Rebuilds both lists. Small enough that a full rebuild is simplest. */
    private void rebuildLists() {
        Set<String> approved = PolicyManager.approvedPackages(this);
        approvedList.removeAllViews();
        for (String pkg : approved) {
            approvedList.addView(packageRow(pkg, PolicyManager.isPinned(pkg),
                    () -> confirmRemove(pkg)));
        }
        approvedHeader.setText("Approved apps (" + approved.size() + ")");

        Set<String> hidden = PolicyManager.hiddenPackages(this);
        hiddenList.removeAllViews();
        for (String pkg : hidden) {
            hiddenList.addView(packageRow(pkg, false, () -> confirmUnhide(pkg)));
        }
        hiddenHeader.setText("Hidden apps (" + hidden.size() + ")");
    }

    /**
     * How a package id is labelled in a list.
     *
     * <p>Three states worth telling apart: present and visible, present but
     * hidden by this app, and not on the device at all. A hidden package is
     * invisible to the plain query, so without the second case every row in
     * the hide list would read "not installed".</p>
     */
    private String rowLabel(String pkg) {
        if (PolicyManager.isInstalled(this, pkg)) {
            return pkg;
        }
        if (PolicyManager.isInstalledIncludingHidden(this, pkg)) {
            return pkg + "  (hidden)";
        }
        return pkg + "  (not installed)";
    }

    /** One package row: the id, and either a button or a "required" label. */
    private View packageRow(String pkg, boolean pinned, Runnable onButton) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(rowLabel(pkg));
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextSize(13f);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        if (pinned) {
            // The VPN client. Removing it from a locked-down device is how you
            // take it off the network for good, so it gets a label, not a button.
            TextView label = new TextView(this);
            label.setText("required");
            label.setTextSize(12f);
            row.addView(label);
        } else {
            Button button = new Button(this);
            button.setText("Remove");
            button.setOnClickListener(v -> onButton.run());
            row.addView(button);
        }
        return row;
    }

    // ------------------------------------------------------------------

    private void refresh() {
        status.setText(PolicyManager.describe(this));
        rebuildLists();

        // setChecked() fires the listeners, which would apply the policy again
        // on every resume - and recurse, since applying calls refresh().
        syncingSwitches = true;
        boolean alwaysOn = PolicyManager.isVpnAlwaysOnEnabled(this);
        vpnAlwaysOn.setChecked(alwaysOn);
        vpnLockdown.setChecked(PolicyManager.isVpnLockdownEnabled(this));
        // Lockdown without always-on does nothing, so do not offer it alone.
        vpnLockdown.setEnabled(alwaysOn && Policy.VPN_PACKAGE != null);
        vpnAlwaysOn.setEnabled(Policy.VPN_PACKAGE != null);
        syncingSwitches = false;

        // Rewritten from the stored value on every refresh, so a half-typed
        // name that was never submitted does not survive as if it had been.
        // Nothing calls refresh() while the field has focus except the Set
        // button itself, which has just stored what is in it.
        organizationName.setText(PolicyManager.organizationName(this));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
