package me.perdomo.dpc;

import android.os.UserManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The entire configuration of this DPC.
 *
 * <p>This is the only file you normally edit. Change it, rebuild, reinstall,
 * open the maintenance screen and press "Apply lockdown".</p>
 */
public final class Policy {

    private Policy() {}

    // ------------------------------------------------------------------
    // VPN
    // ------------------------------------------------------------------

    /**
     * Package id of the VPN client.
     *
     * <p>It must already be installed when the policy is applied, and it must
     * support always-on mode (a VpnService that does not opt out via the
     * SUPPORTS_ALWAYS_ON metadata flag). Verify with TestDPC before trusting it.</p>
     *
     * <p>Set to null to leave always-on VPN alone entirely.</p>
     */
    public static final String VPN_PACKAGE = "com.wireguard.android";

    /**
     * Initial state of the "always-on VPN" switch on the maintenance screen.
     *
     * <p>This is a default, not the live value. It is what a freshly
     * provisioned device starts with; from then on the switch owns the setting
     * and it is stored on the device, so it survives reboots, the periodic
     * sweep and {@code adb install -r}. Read the live value with
     * {@link PolicyManager#isVpnAlwaysOnEnabled(android.content.Context)}.</p>
     *
     * <p>Off, because provisioning necessarily happens before
     * {@link #VPN_PACKAGE} is installed and long before it has a working
     * tunnel. Pinning always-on at that point either fails outright or, worse,
     * succeeds the moment the VPN app appears and forces up a tunnel that has
     * no configuration. Install the VPN client, import its profile, confirm it
     * connects, then turn this on from the maintenance screen.</p>
     */
    public static final boolean VPN_ALWAYS_ON_DEFAULT = false;

    /**
     * Initial state of the "block non-VPN traffic" switch.
     *
     * <p>This is the "no leak" switch: with it on, every packet outside the
     * tunnel is dropped whenever the tunnel is down. Like
     * {@link #VPN_ALWAYS_ON_DEFAULT} it is only the starting value; the
     * maintenance screen owns it after that.</p>
     *
     * <p>Lockdown is meaningless without always-on, so the effective value is
     * always {@code alwaysOn && lockdown}. Turning always-on off does not
     * forget this setting - it comes back when always-on returns.</p>
     *
     * <p>Off for the same reason as {@link #VPN_ALWAYS_ON_DEFAULT}, and more
     * urgently: this switch drops every packet outside the tunnel. Enabled on a
     * device whose tunnel is not yet configured, it takes the network away
     * before you have finished setting the device up - including the network
     * you needed to install the VPN profile in the first place. This app is
     * exempt from lockdown, so the maintenance screen still lets you back out,
     * but nothing else on the device will work until you do.</p>
     */
    public static final boolean VPN_LOCKDOWN_DEFAULT = false;

    /**
     * Packages allowed to bypass lockdown when the tunnel is down (Android 10+).
     *
     * <p>This app is always added automatically. Leave that alone: it is what
     * lets you reach the maintenance screen and undo a broken VPN profile
     * without a factory reset.</p>
     */
    public static final Set<String> VPN_LOCKDOWN_EXEMPT = setOf(
            // "com.android.settings"
    );

    // ------------------------------------------------------------------
    // Approved apps
    // ------------------------------------------------------------------

    /**
     * Seed for the list of non-system apps permitted on the device.
     *
     * <p>The Play Store stays fully usable. Anything installed from it that is
     * not on the allowlist gets hidden and then uninstalled, normally within a
     * couple of seconds - see the note on enforcement latency in the README.</p>
     *
     * <p>This is a starting point, not the live list: the maintenance screen
     * adds and removes packages by hand, and once it has, the stored list is
     * what enforcement reads. Editing here changes only what a freshly
     * provisioned device begins with. Read the live list with
     * {@link PolicyManager#approvedPackages(android.content.Context)}.</p>
     *
     * <p>{@link #VPN_PACKAGE} is always on the list and cannot be removed on
     * the device, whether or not it is named here.</p>
     *
     * <p>System apps, the current launcher, enabled keyboards and this DPC are
     * never touched, whatever this list says.</p>
     */
    public static final Set<String> APPROVED_PACKAGES = setOf(
            "com.wireguard.android",
            "org.mozilla.firefox"
    );

    /**
     * Packages the user may not uninstall.
     *
     * <p>This app and {@link #VPN_PACKAGE} are added automatically. Applied
     * with setUninstallBlocked(), which is targeted - unlike the blunt
     * DISALLOW_UNINSTALL_APPS restriction, it does not also block this app
     * from removing unapproved software.</p>
     */
    public static final Set<String> UNINSTALL_BLOCKED = setOf(
            "com.wireguard.android"
    );

    /**
     * Seed for the list of packages hidden outright: the icon disappears and
     * they cannot be launched.
     *
     * <p>This is where pre-installed software is dealt with. System apps are
     * exempt from the allowlist sweep by definition, so naming one here is the
     * only way to take it off the device. The APK stays on its read-only
     * partition - a Device Owner cannot delete it - so this is hiding, not
     * uninstalling.</p>
     *
     * <p>Like {@link #APPROVED_PACKAGES} this is only a seed: the maintenance
     * screen edits the live list, and once it has, the stored list wins. Read
     * it with {@link PolicyManager#hiddenPackages(android.content.Context)}.</p>
     *
     * <p>Empty by default. Add "com.android.vending" here if you ever decide to
     * remove the Play Store after all.</p>
     *
     * <p>Never add com.google.android.gms. Hiding Play Services breaks maps,
     * push, WebView updates and often the system UI itself. The maintenance
     * screen refuses that one along with Settings, the home app, keyboards, the
     * VPN client and this app - see
     * {@link PolicyManager#hideRefusalReason(android.content.Context, String)}.
     * This file is deliberately not checked against those refusals: editing it,
     * rebuilding and reinstalling is a clear enough statement of intent.</p>
     */
    public static final Set<String> HIDDEN_PACKAGES = setOf();

    /**
     * Uninstall unapproved apps rather than only hiding them.
     *
     * <p>Hiding happens first either way, because it takes effect immediately.
     * Uninstalling is the honest end state when the Play Store is usable: the
     * app shows as "not installed" again rather than silently vanishing.</p>
     */
    public static final boolean UNINSTALL_UNAPPROVED = true;

    // ------------------------------------------------------------------
    // User restrictions
    // ------------------------------------------------------------------

    /**
     * Applied with addUserRestriction(). Unknown or too-new keys are skipped
     * individually rather than aborting the whole set.
     *
     * <p>Deliberately absent, because they would break a working Play Store:</p>
     * <ul>
     *   <li>DISALLOW_INSTALL_APPS - blocks every install, including from Play.</li>
     *   <li>DISALLOW_UNINSTALL_APPS - would also stop this app removing
     *       unapproved software. Use {@link #UNINSTALL_BLOCKED} instead.</li>
     *   <li>DISALLOW_MODIFY_ACCOUNTS - Play needs a signed-in Google account.
     *       Add it back if you want to pin the account once it is set up.</li>
     * </ul>
     *
     * <p>DISALLOW_INSTALL_UNKNOWN_SOURCES stays, and is doing real work: it
     * leaves Play as the only way software can arrive, which is the one
     * channel this app watches.</p>
     */
    public static final String[] USER_RESTRICTIONS = {
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
    };

    /**
     * Restrictions from {@link #USER_RESTRICTIONS} that stay unset until
     * always-on VPN has been armed from the maintenance screen.
     *
     * <p>DISALLOW_CONFIG_VPN blocks the system VPN consent dialog, which is
     * exactly what importing a WireGuard tunnel needs. Applied at provisioning
     * time - before {@link #VPN_PACKAGE} is even installed - it makes step 5 of
     * the README unreachable: you cannot configure the VPN the policy exists to
     * enforce. It is deferred until {@code VPN_ALWAYS_ON} is on, which is the
     * operator's signal that the tunnel is configured and working.</p>
     *
     * <p>Deferred, not dropped. Once always-on is enabled the restriction goes
     * on and stays on, so a locked-down device is no weaker than before - the
     * window where a user could reconfigure the VPN is the setup window, when
     * they are holding an unprovisioned device anyway.</p>
     */
    public static final Set<String> VPN_DEPENDENT_RESTRICTIONS = setOf(
            UserManager.DISALLOW_CONFIG_VPN
    );

    /**
     * Restrictions from {@link #USER_RESTRICTIONS} that a debug build leaves unset.
     *
     * <p>DISALLOW_DEBUGGING_FEATURES turns off USB debugging, and
     * AdminReceiver.onEnabled applies the whole policy the instant ownership is
     * set - so on a release build the adb link is gone one step after
     * {@code dpm set-device-owner}, before you can install anything, read a log
     * or press a button. That is correct on a deployed device and useless on a
     * test one.</p>
     *
     * <p>A debug build does not merely skip these: it clears them, so a debug
     * APK installed over a release one hands adb back on the next apply rather
     * than leaving it severed.</p>
     *
     * <p>That is not a rescue for a device whose release build has already cut
     * the link - installing anything needs the adb that is gone. Recovery is
     * on the device itself: maintenance screen, passcode, "Suspend lockdown"
     * or "Release device ownership". Note that clearing the restriction only
     * permits debugging again; observed behaviour is that adbd does not come
     * back on its own, so USB debugging has to be switched on again by hand in
     * Developer options. If the screen is unreachable, a factory reset is the
     * only way out.</p>
     *
     * <p>Ignored entirely by release builds, so nothing here can weaken a
     * device you actually deploy.</p>
     */
    public static final Set<String> DEBUG_SKIPPED_RESTRICTIONS = setOf(
            UserManager.DISALLOW_DEBUGGING_FEATURES
    );

    // ------------------------------------------------------------------
    // Maintenance passcode
    // ------------------------------------------------------------------
    //
    // Generate with:  python3 tools/hash-passcode.py
    // and paste the two values it prints here.
    //
    // A checked-in placeholder hash means the maintenance screen cannot be
    // unlocked at all, so a build that reaches a device has had real values
    // pasted here. Which passcode they stand for is not recoverable from this
    // file - keep it wherever you keep the signing key.
    //
    // These constants are not a secret store. The APK is fetched over the
    // network during QR provisioning, so treat anything compiled into it as
    // readable; the salt and iteration count are all that stand between the
    // hash and an offline guess at the passcode. Pick one worth that.

    public static final String PASSCODE_SALT_B64 = "Y2hhbmdlbWUtc2FsdC0xMjM0";
    public static final String PASSCODE_HASH_B64 = "REPLACE_ME";
    public static final int PASSCODE_ITERATIONS = 120000;

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    /**
     * Run a foreground service that watches for new installs.
     *
     * <p>This is what gets enforcement latency down to seconds. Without it the
     * only backstop is the periodic sweep below, which the platform will not
     * run more often than every 15 minutes - a long time for an unapproved app
     * to be running.</p>
     *
     * <p>The cost is a permanent low-priority notification. Set to false if you
     * would rather have a clean status bar than fast enforcement.</p>
     */
    public static final boolean RUN_INSTALL_WATCHDOG = true;

    /** How long "suspend lockdown" lasts before enforcement resumes, in minutes. */
    public static final int MAINTENANCE_WINDOW_MINUTES = 30;

    /** Reconciliation sweep interval, in minutes. 15 is the platform floor. */
    public static final int SWEEP_INTERVAL_MINUTES = 15;

    // ------------------------------------------------------------------

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
