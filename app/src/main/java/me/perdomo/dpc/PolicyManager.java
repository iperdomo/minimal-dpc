package me.perdomo.dpc;

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Every DevicePolicyManager call this app makes.
 *
 * <p>All of it is idempotent: applying twice is the same as applying once, so
 * boot, the periodic sweep and the maintenance button can all just call
 * {@link #applyAll}.</p>
 */
public final class PolicyManager {

    static final String TAG = "MinimalDPC";

    private static final String PREFS = "dpc";
    private static final String KEY_MAINTENANCE_UNTIL = "maintenance_until";
    private static final String KEY_VPN_ALWAYS_ON = "vpn_always_on";
    private static final String KEY_VPN_LOCKDOWN = "vpn_lockdown";
    private static final String KEY_APPROVED = "approved_packages";
    private static final String KEY_HIDDEN = "hidden_packages";

    /** Hiding this breaks maps, push, WebView updates and often the system UI. */
    private static final String PLAY_SERVICES = "com.google.android.gms";
    /** Hiding this is the classic way to strand a device with no way back. */
    private static final String SETTINGS = "com.android.settings";

    /**
     * Typo guard for hand-entered package ids, not a strict validator.
     *
     * <p>Two or more dot-separated segments, the first starting with a letter.
     * It exists to catch "org.mozilla firefox" and "Firefox", not to police
     * every rule the platform has.</p>
     */
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");

    private PolicyManager() {}

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    public static void applyAll(Context ctx) {
        if (!isDeviceOwner(ctx)) {
            Log.w(TAG, "Not device owner - nothing to apply");
            return;
        }
        applyRestrictions(ctx);
        applyHiddenPackages(ctx);
        applyUninstallBlocked(ctx);
        applyVpn(ctx);
        enforceAllowlist(ctx);
        EnforcementJobService.schedule(ctx);
        WatchdogService.start(ctx);
    }

    // ------------------------------------------------------------------
    // Uninstall protection
    // ------------------------------------------------------------------

    /**
     * Blocks uninstall of the packages that must survive, individually.
     *
     * <p>Deliberately not the DISALLOW_UNINSTALL_APPS restriction: that would
     * also block this app from removing unapproved software, which is the
     * whole point of the allowlist.</p>
     */
    public static void applyUninstallBlocked(Context ctx) {
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);

        Set<String> blocked = new HashSet<>(Policy.UNINSTALL_BLOCKED);
        blocked.add(ctx.getPackageName());
        if (Policy.VPN_PACKAGE != null) {
            blocked.add(Policy.VPN_PACKAGE);
        }
        for (String pkg : blocked) {
            if (!isInstalled(ctx, pkg)) {
                continue;
            }
            try {
                dpm.setUninstallBlocked(admin, pkg, true);
            } catch (Exception e) {
                Log.w(TAG, "Could not block uninstall of " + pkg, e);
            }
        }
    }

    private static void releaseUninstallBlocked(Context ctx) {
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);
        Set<String> blocked = new HashSet<>(Policy.UNINSTALL_BLOCKED);
        blocked.add(ctx.getPackageName());
        if (Policy.VPN_PACKAGE != null) {
            blocked.add(Policy.VPN_PACKAGE);
        }
        for (String pkg : blocked) {
            try {
                dpm.setUninstallBlocked(admin, pkg, false);
            } catch (Exception ignored) {
                // Best effort.
            }
        }
    }

    // ------------------------------------------------------------------
    // Always-on VPN
    // ------------------------------------------------------------------

    /**
     * Desired state of the always-on VPN switch.
     *
     * <p>Stored on the device rather than compiled in, so the maintenance
     * screen can change it without a rebuild. {@link Policy#VPN_ALWAYS_ON_DEFAULT}
     * is only the value a freshly provisioned device starts with.</p>
     */
    public static boolean isVpnAlwaysOnEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_VPN_ALWAYS_ON, Policy.VPN_ALWAYS_ON_DEFAULT);
    }

    /**
     * Desired state of the lockdown switch, as the user last set it.
     *
     * <p>This is the stored preference, not what is in force: lockdown without
     * always-on does nothing, so the value actually applied is
     * {@link #isVpnLockdownEffective}. Keeping the two apart means turning
     * always-on off and back on restores the lockdown setting rather than
     * silently losing it.</p>
     */
    public static boolean isVpnLockdownEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_VPN_LOCKDOWN, Policy.VPN_LOCKDOWN_DEFAULT);
    }

    /** Lockdown as actually applied: only meaningful with always-on set. */
    public static boolean isVpnLockdownEffective(Context ctx) {
        return isVpnAlwaysOnEnabled(ctx) && isVpnLockdownEnabled(ctx);
    }

    /**
     * Records the always-on switch and applies it immediately.
     *
     * @return null on success, or a human-readable reason it did not apply.
     */
    public static String setVpnAlwaysOn(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_VPN_ALWAYS_ON, enabled).apply();
        Log.i(TAG, "Always-on VPN switch = " + enabled);
        return applyVpn(ctx);
    }

    /**
     * Records the lockdown switch and applies it immediately.
     *
     * @return null on success, or a human-readable reason it did not apply.
     */
    public static String setVpnLockdown(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_VPN_LOCKDOWN, enabled).apply();
        Log.i(TAG, "VPN lockdown switch = " + enabled);
        return applyVpn(ctx);
    }

    /**
     * Brings the platform in line with the two switches.
     *
     * <p>Called from every re-assertion path - provisioning, boot, the periodic
     * sweep and the maintenance button - so the stored switches are what the
     * device converges back to after any drift.</p>
     *
     * @return null on success, or a human-readable reason it did not apply.
     */
    public static String applyVpn(Context ctx) {
        if (Policy.VPN_PACKAGE == null) {
            return null;
        }
        if (!isVpnAlwaysOnEnabled(ctx)) {
            // Off means off at the platform, not merely lockdown=false: leaving
            // the package pinned would keep forcing the tunnel up.
            clearVpn(ctx);
            return null;
        }
        boolean lockdown = isVpnLockdownEffective(ctx);
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Set<String> exempt = new HashSet<>(Policy.VPN_LOCKDOWN_EXEMPT);
                // Never lock ourselves out of our own escape hatch.
                exempt.add(ctx.getPackageName());
                dpm.setAlwaysOnVpnPackage(admin, Policy.VPN_PACKAGE, lockdown, exempt);
            } else {
                dpm.setAlwaysOnVpnPackage(admin, Policy.VPN_PACKAGE, lockdown);
            }
            Log.i(TAG, "Always-on VPN = " + Policy.VPN_PACKAGE
                    + " lockdown=" + lockdown);
            return null;
        } catch (PackageManager.NameNotFoundException e) {
            String msg = Policy.VPN_PACKAGE + " is not installed";
            Log.w(TAG, "Always-on VPN not applied: " + msg);
            return msg;
        } catch (UnsupportedOperationException e) {
            String msg = Policy.VPN_PACKAGE + " does not support always-on mode";
            Log.w(TAG, "Always-on VPN not applied: " + msg);
            return msg;
        } catch (Exception e) {
            Log.w(TAG, "Always-on VPN not applied", e);
            return String.valueOf(e.getMessage());
        }
    }

    public static void clearVpn(Context ctx) {
        try {
            dpm(ctx).setAlwaysOnVpnPackage(admin(ctx), null, false);
        } catch (Exception e) {
            Log.w(TAG, "Could not clear always-on VPN", e);
        }
    }

    // ------------------------------------------------------------------
    // User restrictions
    // ------------------------------------------------------------------

    public static void applyRestrictions(Context ctx) {
        setRestrictions(ctx, true);
    }

    public static void releaseRestrictions(Context ctx) {
        setRestrictions(ctx, false);
    }

    private static void setRestrictions(Context ctx, boolean add) {
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);
        for (String r : Policy.USER_RESTRICTIONS) {
            // A debug build clears its skipped restrictions rather than just
            // declining to add them, so that installing a debug APK over a
            // release one hands adb back instead of leaving it severed.
            boolean apply = add && !skippedInDebug(r);
            if (add && !apply) {
                Log.w(TAG, "DEBUG BUILD - clearing " + r + " instead of applying it");
            }
            try {
                if (apply) {
                    dpm.addUserRestriction(admin, r);
                } else {
                    dpm.clearUserRestriction(admin, r);
                }
            } catch (Exception e) {
                // An unknown or not-yet-supported key on this release. Skip it
                // rather than losing the rest of the set.
                Log.w(TAG, "Restriction " + r + " skipped: " + e.getMessage());
            }
        }
    }

    /**
     * True when this build deliberately leaves a restriction unset.
     *
     * <p>Always false in a release build, whatever
     * {@link Policy#DEBUG_SKIPPED_RESTRICTIONS} contains.</p>
     */
    static boolean skippedInDebug(String restriction) {
        return BuildConfig.DEBUG
                && Policy.DEBUG_SKIPPED_RESTRICTIONS.contains(restriction);
    }

    // ------------------------------------------------------------------
    // Hidden packages
    // ------------------------------------------------------------------

    public static void applyHiddenPackages(Context ctx) {
        setHidden(ctx, hiddenPackages(ctx), true);
    }

    public static void unhidePackages(Context ctx) {
        setHidden(ctx, hiddenPackages(ctx), false);
    }

    private static void setHidden(Context ctx, Iterable<String> packages, boolean hidden) {
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);
        for (String pkg : packages) {
            if (ctx.getPackageName().equals(pkg)) {
                continue;
            }
            try {
                dpm.setApplicationHidden(admin, pkg, hidden);
                Log.i(TAG, (hidden ? "Hid " : "Unhid ") + pkg);
            } catch (Exception e) {
                Log.w(TAG, "Could not set hidden=" + hidden + " on " + pkg, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // The allowlist itself
    // ------------------------------------------------------------------

    /**
     * The live allowlist, sorted for display.
     *
     * <p>Stored on the device once the maintenance screen has touched it;
     * {@link Policy#APPROVED_PACKAGES} is only the seed a freshly provisioned
     * device starts with. {@link #pinnedPackages()} is always included, so the
     * VPN client cannot be dropped by editing.</p>
     */
    public static Set<String> approvedPackages(Context ctx) {
        Set<String> stored = prefs(ctx).getStringSet(KEY_APPROVED, null);
        // The set handed back by getStringSet() must not be modified, so copy.
        Set<String> out = new TreeSet<>(stored != null ? stored : Policy.APPROVED_PACKAGES);
        out.addAll(pinnedPackages());
        return out;
    }

    /** True once the list has been edited on the device. */
    public static boolean hasCustomApprovedList(Context ctx) {
        return prefs(ctx).contains(KEY_APPROVED);
    }

    /**
     * Packages that may not be removed from the allowlist.
     *
     * <p>The VPN client: with lockdown on, uninstalling it is how you take a
     * device off the network permanently. It is already exempt from the sweep
     * via {@link #protectedPackages}; pinning it here is what stops the list
     * from claiming otherwise.</p>
     *
     * <p>This app is not listed because it is never a candidate - it is
     * protected by {@link #protectedPackages} and is not in the allowlist to
     * begin with.</p>
     */
    public static Set<String> pinnedPackages() {
        Set<String> pinned = new HashSet<>();
        if (Policy.VPN_PACKAGE != null) {
            pinned.add(Policy.VPN_PACKAGE);
        }
        return pinned;
    }

    public static boolean isPinned(String pkg) {
        return pinnedPackages().contains(pkg);
    }

    /**
     * Adds a hand-entered package to the allowlist.
     *
     * <p>The package need not be installed: approving first and installing
     * from Play afterwards is the normal order. If it is installed and an
     * earlier sweep hid it, approving un-hides it.</p>
     *
     * @return null on success, or a human-readable reason it was rejected.
     */
    public static String addApprovedPackage(Context ctx, String pkg) {
        String name = pkg == null ? "" : pkg.trim();
        if (name.isEmpty()) {
            return "Enter a package name";
        }
        if (!PACKAGE_NAME.matcher(name).matches()) {
            return "Not a package name: " + name;
        }
        Set<String> current = approvedPackages(ctx);
        if (!current.add(name)) {
            return name + " is already approved";
        }
        writeApproved(ctx, current);

        // An app the sweep hid earlier has to come back when it is approved -
        // nothing else un-hides it, and hidden is what actually stops it
        // running. Deliberately not gated on isInstalled(): a hidden package
        // is invisible to getApplicationInfo(), so that check reads false in
        // exactly the case this exists for. On a package that really is not
        // installed the call is a harmless no-op.
        if (isDeviceOwner(ctx)) {
            try {
                dpm(ctx).setApplicationHidden(admin(ctx), name, false);
            } catch (Exception e) {
                Log.w(TAG, "Could not un-hide newly approved " + name, e);
            }
        }
        Log.i(TAG, "Approved " + name);
        return null;
    }

    /**
     * Drops a package from the allowlist.
     *
     * <p>Removal only edits the list. Acting on it is the caller's job, so
     * that an open maintenance window is still respected.</p>
     *
     * @return null on success, or a human-readable reason it was refused.
     */
    public static String removeApprovedPackage(Context ctx, String pkg) {
        if (isPinned(pkg)) {
            return pkg + " is required and cannot be removed";
        }
        Set<String> current = approvedPackages(ctx);
        if (!current.remove(pkg)) {
            return pkg + " is not on the list";
        }
        writeApproved(ctx, current);
        Log.i(TAG, "Un-approved " + pkg);
        return null;
    }

    // ------------------------------------------------------------------
    // Hidden packages
    // ------------------------------------------------------------------

    /**
     * The live hide list, sorted for display.
     *
     * <p>Unlike the allowlist this one is aimed at pre-installed software:
     * system apps are exempt from the sweep by definition, so naming one here
     * is the only way to take it off the device. The icon disappears and the
     * app cannot be launched; the APK stays on its read-only partition, which
     * is why this is hiding rather than uninstalling.</p>
     */
    public static Set<String> hiddenPackages(Context ctx) {
        Set<String> stored = prefs(ctx).getStringSet(KEY_HIDDEN, null);
        return new TreeSet<>(stored != null ? stored : Policy.HIDDEN_PACKAGES);
    }

    public static boolean hasCustomHiddenList(Context ctx) {
        return prefs(ctx).contains(KEY_HIDDEN);
    }

    /**
     * Why this package must not be hidden from the maintenance screen.
     *
     * <p>Every one of these makes the device unusable, unrecoverable, or both,
     * and the screen that would undo it is the screen doing the hiding. The
     * compile-time {@link Policy#HIDDEN_PACKAGES} is deliberately not checked
     * against this list: editing that file, rebuilding and reinstalling is a
     * clear enough statement of intent.</p>
     *
     * @return null if hiding is allowed, otherwise the reason it is refused.
     */
    public static String hideRefusalReason(Context ctx, String pkg) {
        if (ctx.getPackageName().equals(pkg)) {
            return "This app cannot hide itself";
        }
        if (pkg.equals(Policy.VPN_PACKAGE)) {
            return "The VPN client has to stay reachable";
        }
        if (PLAY_SERVICES.equals(pkg)) {
            return "Play Services: hiding it breaks maps, push and often the system UI";
        }
        if (SETTINGS.equals(pkg)) {
            return "Settings: hiding it can leave the device unrecoverable";
        }
        if (pkg.equals(currentLauncher(ctx))) {
            return "Home app: hiding it leaves no way to reach anything";
        }
        if (enabledKeyboards(ctx).contains(pkg)) {
            return "Keyboard: hiding it would block passcode entry";
        }
        return null;
    }

    /**
     * Adds a package to the hide list and hides it now.
     *
     * @return null on success, or a human-readable reason it was refused.
     */
    public static String addHiddenPackage(Context ctx, String pkg) {
        String name = pkg == null ? "" : pkg.trim();
        if (name.isEmpty()) {
            return "Enter a package name";
        }
        if (!PACKAGE_NAME.matcher(name).matches()) {
            return "Not a package name: " + name;
        }
        String refusal = hideRefusalReason(ctx, name);
        if (refusal != null) {
            return refusal;
        }
        Set<String> current = hiddenPackages(ctx);
        if (!current.add(name)) {
            return name + " is already hidden";
        }
        writeHidden(ctx, current);
        setHidden(ctx, Collections.singleton(name), true);
        Log.i(TAG, "Hide list + " + name);
        return null;
    }

    /**
     * Drops a package from the hide list and un-hides it now.
     *
     * @return null on success, or a human-readable reason it was refused.
     */
    public static String removeHiddenPackage(Context ctx, String pkg) {
        Set<String> current = hiddenPackages(ctx);
        if (!current.remove(pkg)) {
            return pkg + " is not on the list";
        }
        writeHidden(ctx, current);
        // Nothing else would ever un-hide it: applyHiddenPackages only hides.
        setHidden(ctx, Collections.singleton(pkg), false);
        Log.i(TAG, "Hide list - " + pkg);
        return null;
    }

    private static void writeHidden(Context ctx, Set<String> packages) {
        prefs(ctx).edit().putStringSet(KEY_HIDDEN, new HashSet<>(packages)).apply();
    }

    private static void writeApproved(Context ctx, Set<String> packages) {
        // Both the set passed to putStringSet and the one returned by
        // getStringSet are off-limits for later mutation, so store a copy.
        prefs(ctx).edit().putStringSet(KEY_APPROVED, new HashSet<>(packages)).apply();
    }

    // ------------------------------------------------------------------
    // Allowlist reconciliation
    // ------------------------------------------------------------------

    /**
     * Removes every non-system package that is not approved.
     *
     * <p>Hides first, then uninstalls. Hiding takes effect immediately and is
     * what actually stops the app running; the uninstall is asynchronous and
     * merely tidies up. If the uninstall fails the app stays hidden, so the
     * failure mode is safe.</p>
     *
     * <p>With the Play Store usable this is the primary control, not defence
     * in depth - so it runs from three places: the install watchdog (seconds),
     * the periodic sweep (15 minutes), and the maintenance screen.</p>
     *
     * @return the packages it acted on
     */
    public static List<String> enforceAllowlist(Context ctx) {
        List<String> acted = new ArrayList<>();
        if (!isDeviceOwner(ctx) || inMaintenanceWindow(ctx)) {
            return acted;
        }
        Set<String> approved = approvedPackages(ctx);
        Set<String> protectedPkgs = protectedPackages(ctx);
        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
            if (isApproved(ai, approved, protectedPkgs)) {
                continue;
            }
            if (enforceOne(ctx, ai.packageName)) {
                acted.add(ai.packageName);
            }
        }
        return acted;
    }

    /** Hide-then-uninstall a single package. Returns true if it was acted on. */
    static boolean enforceOne(Context ctx, String pkg) {
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);
        boolean acted = false;
        try {
            if (dpm.setApplicationHidden(admin, pkg, true)) {
                Log.i(TAG, "Hid unapproved app " + pkg);
                acted = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not hide " + pkg, e);
        }
        if (Policy.UNINSTALL_UNAPPROVED) {
            uninstall(ctx, pkg);
            acted = true;
        }
        return acted;
    }

    /** Silent uninstall. A Device Owner is not prompted for confirmation. */
    private static void uninstall(Context ctx, String pkg) {
        try {
            Intent intent = new Intent(ctx, UninstallResultReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(
                    ctx, pkg.hashCode(), intent, flags);
            ctx.getPackageManager().getPackageInstaller()
                    .uninstall(pkg, pending.getIntentSender());
        } catch (Exception e) {
            Log.w(TAG, "Could not uninstall " + pkg, e);
        }
    }

    /**
     * True when a package must be left alone.
     *
     * <p>System apps are never touched. Removing the dialer, the settings app,
     * the Play Store or a vendor component makes the device unusable and is
     * not recoverable from the maintenance screen. Deliberate exceptions go in
     * {@link Policy#HIDDEN_PACKAGES}.</p>
     */
    private static boolean isApproved(ApplicationInfo ai, Set<String> approved,
                                      Set<String> protectedPkgs) {
        return approved.contains(ai.packageName)
                || isSystem(ai)
                || protectedPkgs.contains(ai.packageName);
    }

    /** Convenience overload for single-package checks. */
    static boolean isApproved(Context ctx, String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return isApproved(ai, approvedPackages(ctx), protectedPackages(ctx));
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** Number of installed non-system packages that are not approved. */
    public static int countUnapproved(Context ctx) {
        int n = 0;
        Set<String> approved = approvedPackages(ctx);
        Set<String> protectedPkgs = protectedPackages(ctx);
        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
            if (!isApproved(ai, approved, protectedPkgs)) {
                n++;
            }
        }
        return n;
    }

    /** Packages the sweep must never touch, whatever the allowlist says. */
    private static Set<String> protectedPackages(Context ctx) {
        Set<String> keep = new HashSet<>();
        keep.add(ctx.getPackageName());
        if (Policy.VPN_PACKAGE != null) {
            keep.add(Policy.VPN_PACKAGE);
        }
        String launcher = currentLauncher(ctx);
        if (launcher != null) {
            keep.add(launcher);
        }
        keep.addAll(enabledKeyboards(ctx));
        return keep;
    }

    /** The home app. Hiding it leaves no way to reach anything. */
    static String currentLauncher(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
    }

    /**
     * Enabled keyboards. Hiding the only IME makes text entry impossible,
     * including entry of this app's own passcode.
     */
    static Set<String> enabledKeyboards(Context ctx) {
        Set<String> imes = new HashSet<>();
        try {
            InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
            if (imm != null) {
                for (InputMethodInfo imi : imm.getEnabledInputMethodList()) {
                    imes.add(imi.getPackageName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate keyboards", e);
        }
        return imes;
    }

    private static boolean isSystem(ApplicationInfo ai) {
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    // ------------------------------------------------------------------
    // Maintenance window
    // ------------------------------------------------------------------

    /** Lifts install restrictions and the sweep for a bounded period. */
    public static void beginMaintenanceWindow(Context ctx) {
        long until = System.currentTimeMillis()
                + Policy.MAINTENANCE_WINDOW_MINUTES * 60_000L;
        prefs(ctx).edit().putLong(KEY_MAINTENANCE_UNTIL, until).apply();

        releaseRestrictions(ctx);
        unhidePackages(ctx);
        // Un-hide everything the sweep previously hid, so it can be updated
        // or removed by hand during the window.
        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);
        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
            if (!isSystem(ai)) {
                try {
                    dpm.setApplicationHidden(admin, ai.packageName, false);
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
        }
        Log.i(TAG, "Maintenance window open for "
                + Policy.MAINTENANCE_WINDOW_MINUTES + " min");
    }

    public static void endMaintenanceWindow(Context ctx) {
        prefs(ctx).edit().remove(KEY_MAINTENANCE_UNTIL).apply();
        applyAll(ctx);
    }

    public static boolean inMaintenanceWindow(Context ctx) {
        return prefs(ctx).getLong(KEY_MAINTENANCE_UNTIL, 0L) > System.currentTimeMillis();
    }

    public static long maintenanceMinutesLeft(Context ctx) {
        long until = prefs(ctx).getLong(KEY_MAINTENANCE_UNTIL, 0L);
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (left / 60_000L) + 1;
    }

    // ------------------------------------------------------------------
    // Escape hatch
    // ------------------------------------------------------------------

    /**
     * Undoes everything and releases Device Owner.
     *
     * <p>Clears always-on VPN first. Dropping ownership while lockdown is
     * still set would leave a device with no network and no admin able to
     * turn it off, recoverable only by factory reset.</p>
     */
    public static void releaseOwnership(Context ctx) {
        // Un-hide BEFORE clearing prefs. unhidePackages() reads the stored hide
        // list, so clearing first would leave every hidden app hidden for good:
        // the loop below only reaches non-system packages, and hiding stock apps
        // is exactly what that list is for. There would be no Device Owner left
        // to undo it, making a factory reset the only way back.
        unhidePackages(ctx);
        prefs(ctx).edit().clear().apply();
        clearVpn(ctx);
        releaseRestrictions(ctx);
        releaseUninstallBlocked(ctx);
        WatchdogService.stop(ctx);

        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);
        for (ApplicationInfo ai : ctx.getPackageManager().getInstalledApplications(0)) {
            if (!isSystem(ai)) {
                try {
                    dpm.setApplicationHidden(admin, ai.packageName, false);
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
        }
        EnforcementJobService.cancel(ctx);
        try {
            dpm.clearDeviceOwnerApp(ctx.getPackageName());
            Log.i(TAG, "Device ownership released");
        } catch (Exception e) {
            Log.w(TAG, "Could not release device ownership", e);
        }
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    public static String describe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (BuildConfig.DEBUG) {
            sb.append("*** DEBUG BUILD - adb left enabled, do not deploy ***\n\n");
        }
        boolean owner = isDeviceOwner(ctx);
        sb.append("Device owner   : ").append(owner ? "yes" : "NO").append('\n');
        if (!owner) {
            sb.append("\nNot provisioned. Run:\n  adb shell dpm set-device-owner \\\n    ")
              .append(ctx.getPackageName()).append("/.AdminReceiver\n");
            return sb.toString();
        }

        DevicePolicyManager dpm = dpm(ctx);
        ComponentName admin = admin(ctx);

        String vpn = null;
        try {
            vpn = dpm.getAlwaysOnVpnPackage(admin);
        } catch (Exception ignored) {
            // Reported as "unknown" below.
        }
        boolean wantAlwaysOn = isVpnAlwaysOnEnabled(ctx);
        boolean wantLockdown = isVpnLockdownEnabled(ctx);
        sb.append("VPN switches   : always-on ").append(wantAlwaysOn ? "ON" : "off")
          .append(", lockdown ").append(wantLockdown ? "ON" : "off");
        if (wantLockdown && !wantAlwaysOn) {
            // Explains why the platform reads OFF while the switch reads on.
            sb.append(" (inactive)");
        }
        sb.append('\n');

        sb.append("Always-on VPN  : ").append(vpn == null ? "(none)" : vpn).append('\n');

        sb.append("VPN lockdown   : ");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                sb.append(dpm.isAlwaysOnVpnLockdownEnabled(admin) ? "on" : "OFF");
            } catch (Exception e) {
                sb.append("unknown");
            }
        } else {
            sb.append("not readable below Android 11");
        }
        sb.append('\n');

        sb.append("VPN installed  : ")
          .append(Policy.VPN_PACKAGE == null ? "n/a"
                  : (isInstalled(ctx, Policy.VPN_PACKAGE) ? "yes" : "NO"))
          .append('\n');

        int restrictionsSet = 0;
        int restrictionsExpected = 0;
        android.os.UserManager um = ctx.getSystemService(android.os.UserManager.class);
        for (String r : Policy.USER_RESTRICTIONS) {
            if (skippedInDebug(r)) {
                continue;
            }
            restrictionsExpected++;
            if (um != null && um.hasUserRestriction(r)) {
                restrictionsSet++;
            }
        }
        sb.append("Restrictions   : ").append(restrictionsSet)
          .append(" / ").append(restrictionsExpected);
        int skipped = Policy.USER_RESTRICTIONS.length - restrictionsExpected;
        if (skipped > 0) {
            // Counted out of what this build intends to apply, not out of the
            // full list - otherwise a debug build reads as a failed release one.
            sb.append("   (").append(skipped).append(" skipped: debug build)");
        }
        sb.append('\n');

        sb.append("Sideloading    : ")
          .append(um != null && um.hasUserRestriction(
                  android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                  ? "blocked" : "ALLOWED")
          .append('\n');

        sb.append("Install watch  : ");
        if (!Policy.RUN_INSTALL_WATCHDOG) {
            sb.append("off (sweep only, up to ")
              .append(Policy.SWEEP_INTERVAL_MINUTES).append(" min lag)");
        } else {
            sb.append(WatchdogService.isRunning() ? "running" : "NOT RUNNING");
        }
        sb.append('\n');

        sb.append("Approved apps  : ").append(approvedPackages(ctx).size())
          .append(hasCustomApprovedList(ctx) ? " (edited on device)" : " (from Policy.java)")
          .append('\n');
        sb.append("Hidden apps    : ").append(hiddenPackages(ctx).size())
          .append(hasCustomHiddenList(ctx) ? " (edited on device)" : " (from Policy.java)")
          .append('\n');
        sb.append("Unapproved now : ").append(countUnapproved(ctx)).append('\n');

        if (inMaintenanceWindow(ctx)) {
            sb.append("\n*** MAINTENANCE MODE - ")
              .append(maintenanceMinutesLeft(ctx))
              .append(" min left ***\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public static boolean isDeviceOwner(Context ctx) {
        DevicePolicyManager dpm = dpm(ctx);
        return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
    }

    static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Installed, counting packages this app has hidden.
     *
     * <p>A hidden package is invisible to the plain query, so without this
     * every row in the hide list would claim "not installed" - which is the
     * one list where every entry is hidden by construction.</p>
     *
     * <p>MATCH_UNINSTALLED_PACKAGES also matches a package uninstalled with its
     * data retained, so this is "the system still knows about it" rather than a
     * strict install check. That is the right answer for a display label.</p>
     */
    static boolean isInstalledIncludingHidden(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getApplicationInfo(pkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static ComponentName admin(Context ctx) {
        return new ComponentName(ctx.getApplicationContext(), AdminReceiver.class);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
