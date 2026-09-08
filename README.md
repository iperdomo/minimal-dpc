# minimal-dpc

A single-purpose Android Device Owner app for a handful of devices you can hold in your hand.

It does three things:

1. **Always-on VPN with lockdown** — pins a VPN client as always-on and blocks every packet that is not inside the tunnel.
2. **Approved packages only** — the Play Store stays fully usable, and so does any other installer (F-Droid, a hand-installed APK), but any non-system app that arrives and is not on your allowlist is hidden and uninstalled within seconds, whatever installed it.
3. **Hidden pre-installed apps** — stock software is exempt from the allowlist by definition, so anything you do not want (YouTube, Gmail, the vendor browser) is named separately and hidden.

No server. No database. No admin console. No cloud account, quota request, or per-device fee. The policy ships compiled into the APK; the two things you change most — the approved-app list and the VPN switches — are then editable on the device itself, behind a passcode.

---

## When this is the wrong tool

Use a real MDM if you need to change policy on devices you cannot physically reach, manage more than a handful of them, distribute apps over the air, or collect inventory and compliance reporting. This app deliberately has none of that.

Also be clear about two things.

First, **the allowlist does not cover pre-installed apps.** System apps — everything shipped in the device image, which on a Google build means Chrome, Gmail, Maps, YouTube, Photos and Messages, plus whatever your OEM adds — are exempt from the sweep by definition. They are never hidden or uninstalled and they never need to be on the allowlist. So the allowlist controls what the user can *add*, not what the device already has. Removing stock software is the *Hidden apps* list's job, and it is a separate, explicit decision for each package. On the API 36 emulator this app is tested against, the stock image has 249 system packages, 19 of them with launcher icons.

Second, be clear about what "approved packages only" means, because it is **reactive, not preventive**.

A genuinely *filtered* Play Store — where the store shows only your approved titles and refuses to install anything else — is a Google backend feature available exclusively to commercial EMM vendors through the Android Management API. No device-side API can reproduce it: AOSP has no per-package install allowlist for a DPC. What this app does instead is let the install complete and then undo it, normally within a second or two.

The practical consequences:

- The user browses the full Play catalogue — or any F-Droid repository, or any APK they can get onto the device — and can start any install.
- An unapproved app is hidden and uninstalled almost immediately, but there is a window in which its code has run at least once.
- The user sees the app appear and then vanish, which is confusing unless you tell them why.

If a hard preventive guarantee is what you need, this design cannot give it to you and neither can any self-hosted alternative. Setting `DISALLOW_INSTALL_APPS` in `Policy.USER_RESTRICTIONS` converts it into a hard block — at the cost of the Play Store no longer being able to install anything at all.

---

## Requirements

- A device that can be factory reset, running Android 8.0 (API 26) or newer.
- A VPN client that supports always-on mode. Most do; verify yours before trusting it (see *Validate first* below).
- JDK 17 and the Android SDK, or just Android Studio.

## Layout

```
app/src/main/java/me/perdomo/dpc/
  Policy.java                  ← the only file you normally edit
  PolicyManager.java           ← every DevicePolicyManager call
  AdminReceiver.java           ← Device Owner entry point
  MaintenanceActivity.java     ← passcode-gated recovery screen
  WatchdogService.java         ← keeps the install receiver registered
  PackageMonitorReceiver.java  ← reacts to new installs (~1 second)
  EnforcementJobService.java   ← periodic reconciliation backstop
  UninstallResultReceiver.java ← logs silent uninstall outcomes
  BootReceiver.java            ← re-applies policy after reboot
  Passcode.java                ← PBKDF2 verification
tools/
  hash-passcode.py             ← generates the passcode hash
  make-qr.py                   ← builds a provisioning QR payload
```

---

## Validate first (15 minutes, before you build anything)

Install Google's **TestDPC** on a spare device as Device Owner and use its UI to set always-on VPN with lockdown for your chosen VPN client. This confirms that your VPN app actually supports always-on and that lockdown behaves on your specific hardware — two things that vary by app and by OEM, and that are much easier to discover now than after you have locked a device.

TestDPC is not the answer for a deployed device, because it has no access control: anyone holding the phone can open it and reverse every policy. That is what the passcode in this app is for.

---

## Setup

### 1. Configure the policy

Edit `app/src/main/java/me/perdomo/dpc/Policy.java`:

```java
public static final String VPN_PACKAGE = "com.wireguard.android";
public static final boolean VPN_ALWAYS_ON_DEFAULT = true;
public static final boolean VPN_LOCKDOWN_DEFAULT = true;

public static final Set<String> APPROVED_PACKAGES = setOf(
        "com.wireguard.android",
        "com.android.chrome"
);

public static final Set<String> HIDDEN_PACKAGES = setOf();   // seed; empty: Play stays
```

The two VPN booleans are **defaults, not the live setting**. They are what a freshly provisioned device starts with; after that the two switches on the maintenance screen own them and the values are stored on the device. See *Turning the VPN on and off* below.

`APPROVED_PACKAGES` is a **seed**, not the live list: it is what a freshly provisioned device starts with, and from then on the maintenance screen owns it. See *Approving a new app* below.

It only needs your **non-system** apps. System apps are never touched — hiding the dialer, the settings app or the Play Store itself would make the device unusable and unrecoverable from the maintenance screen. To remove a system app deliberately, name it in `HIDDEN_PACKAGES`.

Never put `com.google.android.gms` in `HIDDEN_PACKAGES`. Hiding Play Services breaks maps, push, WebView updates and frequently the system UI.

Two restrictions are deliberately **absent** from the defaults because they would break a working Play Store, and `Policy.java` documents why: `DISALLOW_INSTALL_APPS` (blocks every install, Play included) and `DISALLOW_UNINSTALL_APPS` (would also stop this app removing unapproved software — `UNINSTALL_BLOCKED` does that job precisely instead).

Two more are **given up** in `Policy.RELINQUISHED_RESTRICTIONS`, which is a different thing: those keys are passed to `clearUserRestriction()` on every apply, so a device provisioned back when they were still enforced actually has them taken off. Dropping a key from `USER_RESTRICTIONS` alone would not do that — an absent key is never cleared either, and the restriction would stay set for the life of the device.

- `DISALLOW_INSTALL_UNKNOWN_SOURCES` — given up so software can come from somewhere other than Play. It blocked the per-app *install unknown apps* toggle that F-Droid, or any other installer, needs. The allowlist is unaffected: enforcement watches for a package appearing, not for the store it came from. What changes is the shape of the guarantee — Play is no longer a chokepoint, and the allowlist sweep is the only thing between the user and an arbitrary APK.
- `DISALLOW_DEBUGGING_FEATURES` — given up so USB debugging can be turned on from Developer options. Clearing it only *permits* debugging; adbd does not come back on its own, so it still has to be switched on by hand.

To put either back, move the key into `USER_RESTRICTIONS` and take it out of `RELINQUISHED_RESTRICTIONS` — leaving it in both would clear it immediately after setting it.

### 2. Set the maintenance passcode

```bash
python3 tools/hash-passcode.py
```

Paste the two constants it prints into `Policy.java`. Until you do, the maintenance screen refuses to unlock — the shipped default is a placeholder, not a working passcode.

Use ASCII characters. Java and Python agree on UTF-8 for PBKDF2, but there is no reason to test that claim on a device you are about to lock.

### 3. Build

```bash
./gradlew assembleRelease
```

The release build is signed with the key described by `keystore.properties` at the repo root. Without that file the build falls back to the debug key, so a fresh clone still assembles. Create the real key once:

```bash
keytool -genkeypair -alias release -keyalg RSA -keysize 4096 -validity 10000 \
        -keystore release.jks -storetype PKCS12 \
        -dname "CN=minimal-dpc release, O=example.com"

cat > keystore.properties <<'EOF'
storeFile=release.jks
storePassword=...
keyAlias=release
keyPassword=...
EOF
```

Both files are gitignored. Back them up somewhere you will still have in ten years: the provisioning QR pins this certificate, so losing the key means every QR code already in circulation stops working and every provisioned device needs a factory reset to move to a new one. The long `-validity` is for the same reason.

There is also `./gradlew assembleDebug`, which exists only so that there is a build you can keep adb attached to. See *Testing on an emulator* below. Never deploy it.

### 4. Provision

Device Owner can only be established on a device with no configured accounts. In practice: factory reset first.

**With adb** (simplest, and you have physical access):

```bash
# Factory reset, then walk through setup WITHOUT adding a Google account.
# Enable Developer options → USB debugging.

adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner me.perdomo.dpc/.AdminReceiver
```

If that fails with "not allowed to set the device owner because there are already several users", the device has an account or a secondary user. Factory reset and try again, skipping account setup.

**With a QR code** (no cable needed):

```bash
python3 tools/make-qr.py \
    --apk app/build/outputs/apk/release/app-release.apk \
    --url https://your-host.example/dpc.apk \
    --out qr.png
```

Factory reset the device, then tap the first setup screen six times to open the QR scanner and scan it. The APK must be reachable over HTTPS from the device.

On Android 11+ the platform runs a short handshake with the DPC during this flow, and refuses to finish without it: `ProvisioningActivity` answers `GET_PROVISIONING_MODE` and `ADMIN_POLICY_COMPLIANCE`. Remove those intent filters and provisioning fails with a bare "Can't set up device - Contact your IT admin for help", *after* the APK has downloaded and installed, saying nothing about what is missing. `adb shell dpm set-device-owner` skips the handshake entirely, so the adb path keeps working and hides the problem.

### 5. Add the Google account and configure the approved apps

Order matters here. Provisioning required a device with no accounts, so sign in to Google now if you want the Play Store to work. The VPN client must be installed *before* always-on VPN can be set, and you want its tunnel working before you enable lockdown.

```bash
adb install -r wireguard.apk
adb install -r <each approved app>.apk
```

Open the VPN client, import its configuration, and confirm the tunnel connects. This is a one-time manual step — with no server there is nothing to push a profile from.

Do not skip the "confirm it connects" part. The next step arms a switch that drops every packet outside the tunnel; if the tunnel does not come up, the device has no network at all.

### 6. Lock it down

Open **Device Lockdown** on the device, enter your passcode, then:

1. Turn on **always-on VPN**, and confirm the tunnel is still up.
2. Turn on **block non-VPN traffic**.
3. Press **Apply lockdown**.

Both VPN switches start off (`Policy.VPN_ALWAYS_ON_DEFAULT`, `Policy.VPN_LOCKDOWN_DEFAULT`), because provisioning always happens before the VPN client exists — arming them earlier would strand the device without the network it needs to finish setup. This app is exempt from lockdown, so the maintenance screen remains reachable if the tunnel breaks.

The status panel should then read:

```
Device owner   : yes
VPN switches   : always-on ON, lockdown ON
Always-on VPN  : com.wireguard.android
VPN lockdown   : on
VPN installed  : yes
Restrictions   : 4 / 4
Sideloading    : allowed by policy (allowlist still enforced)
USB debugging  : permitted (turn on in Developer options)
Install watch  : running
Approved apps  : 3 (from Policy.java)
Hidden apps    : 0 (from Policy.java)
Unapproved now : 0
```

Verify by turning the tunnel off and confirming that nothing reaches the network.

---

## Day-to-day

**Approving a new app.** Maintenance screen → passcode → type the package id under *Approved apps* and press **Add**. The user can then install it from Play themselves and it will survive. No rebuild.

You need the package id, not the app name — `org.mozilla.firefox`, not "Firefox". It is the `id=` parameter in the app's Play Store URL. The field checks the shape of what you type, which catches typos but cannot tell you the package actually exists, so paste it rather than typing from memory: approving `org.mozilla.firefx` silently approves nothing.

The app does not have to be installed when you approve it — approve first, install from Play afterwards, which is the normal order. Unapproved apps that an earlier sweep hid are un-hidden when you approve them.

**Un-approving an app.** Press **Remove** on its row and confirm. If it is installed it is hidden and uninstalled immediately, unless a maintenance window is open, in which case it goes when the window closes.

`VPN_PACKAGE` is pinned to the list and shows *required* instead of a Remove button. On a device with lockdown on, uninstalling the VPN client is how you take it off the network permanently, so the list will not let you.

The list is stored on the device and survives reboots and `adb install -r`. *Release device ownership* clears it, so a released device falls back to the `Policy.java` seed. The status panel says which is in force:

```
Approved apps  : 2 (from Policy.java)      ← untouched, still the compiled seed
Approved apps  : 4 (edited on device)      ← the stored list is what enforcement reads
```

**Installing something without approving it permanently.** Maintenance screen → passcode → *Suspend lockdown for 30 min*. Enforcement pauses, so anything installed in that window stays. When the window closes the sweep removes whatever is still unapproved, so this is for temporary work, not a way to smuggle apps past the allowlist.

Everything *else* — restrictions, hidden packages, uninstall blocking, the passcode — still lives in the binary and still needs a rebuild. That is the deliberate trade for having no server, and it is why the allowlist and the VPN switches are the two things worth exposing on the device: they are the ones that change in normal use.

**Turning the VPN on and off.** Maintenance screen → passcode → the two switches at the top:

- **Always-on VPN** pins `VPN_PACKAGE` as the always-on client. Off clears it at the platform rather than merely relaxing lockdown, so the tunnel is no longer forced up.
- **Block non-VPN traffic (lockdown)** is the no-leak switch. It needs always-on and is greyed out without it — turning always-on off does not forget the setting, it comes back when always-on returns.

Both take effect immediately, are stored on the device, and are re-asserted by boot, the 15-minute sweep and *Apply lockdown* — so nothing drifts back to the compiled-in default. Neither survives *Release device ownership*, which clears the stored state along with everything else.

The status panel reports the switches and the platform separately:

```
VPN switches   : always-on ON, lockdown ON     ← what you asked for
Always-on VPN  : com.wireguard.android         ← what the platform has
VPN lockdown   : on
```

They should agree. Two cases where they will not, both benign: `isAlwaysOnVpnLockdownEnabled()` is Android 11+, so below that the platform line reads "not readable below Android 11" and the switch line is the only answer; and turning always-on on before the VPN client is installed leaves the switch on with `VPN installed : NO`, which the next sweep resolves as soon as the app arrives.

Note that `DISALLOW_CONFIG_VPN` is a separate restriction and stays on regardless. Turning always-on off does not hand VPN configuration back to the user — it only stops forcing the tunnel. Remove it from `USER_RESTRICTIONS` if that is what you want.

**Hiding a pre-installed app.** Maintenance screen → passcode → *Hidden apps* → type the package id and press **Add**. The icon disappears immediately and the app cannot be launched. Press **Remove** on its row to bring it back.

This is hiding, not uninstalling: a Device Owner cannot delete an APK from a read-only partition, so the app stays on disk and reappears the moment you un-hide it or release ownership. Storage is not reclaimed.

To find package ids for what is actually on a device:

```bash
# everything with a launcher icon - the apps a user can actually see
adb shell cmd package query-activities --brief \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    | sed -n 's/.*  \([a-z0-9_.]*\)\/.*/\1/p' | sort -u
```

Six packages are **refused** by the maintenance screen, because hiding them makes the device unusable or unrecoverable and the screen that would undo it is the screen doing the hiding:

| Refused | Why |
|---|---|
| `com.google.android.gms` | Breaks maps, push, WebView updates, often the system UI |
| `com.android.settings` | Can leave the device unrecoverable |
| the current home app | Nothing left to reach anything from |
| any enabled keyboard | No way to type the passcode |
| `VPN_PACKAGE` | The tunnel has to stay reachable |
| this app | It would hide its own escape hatch |

The Play Store is *not* refused — hiding `com.android.vending` is a legitimate choice, and it is the one that turns this from "reactive allowlist" into something much closer to a hard guarantee, at the cost of the user no longer being able to install anything themselves.

`Policy.HIDDEN_PACKAGES` is deliberately **not** checked against those refusals. Editing that file, rebuilding and reinstalling is a clear enough statement of intent; the guard exists to stop a fat-fingered entry on a live device, not to overrule you.

**Changing policy.** Edit `Policy.java`, `./gradlew assembleRelease`, `adb install -r`, press *Apply lockdown*. Reinstalling the APK does not disturb Device Owner status.

**Handing a device back.** Maintenance screen → *Release device ownership*. The device is left as it was found: always-on VPN cleared, restrictions dropped, hidden apps visible again, uninstall blocking removed, and both lists forgotten.

`releaseOwnership()` is **order-sensitive**, and two of the steps are load-bearing rather than incidental. Anyone rearranging them should know why they sit where they do:

1. **Un-hide before clearing stored state.** `unhidePackages()` reads the stored hide list, so wiping it first makes that call a no-op and every hidden app stays hidden. The sweep that follows only reaches non-system packages, and hiding *stock* apps is precisely what the hide list is for — so a device released in the wrong order keeps a permanently crippled launcher with no Device Owner left to restore it. Factory reset would be the only way back.
2. **Clear always-on VPN before dropping ownership.** Releasing while lockdown was still set leaves a device with no network and no admin able to turn it off.

Both failures share a shape: the escape hatch has to finish undoing the policy *while it still has the authority and the information to do so*. It has exactly one pass.

After release, `Policy.java` is the only policy that exists again — both lists revert to their compiled seeds, and so do the VPN switches. Nothing carries over.

---

## How enforcement actually works

Three layers:

1. **`WatchdogService` + `PackageMonitorReceiver`** is the fast path, reacting to a new install within about a second. It listens for the package appearing, so Play, F-Droid and `adb install` all go through it alike.
2. **`EnforcementJobService`** is the backstop: a full reconciliation every 15 minutes that also restarts the watchdog if its process was killed.

There used to be a third layer in front of these: `DISALLOW_INSTALL_UNKNOWN_SOURCES` narrowed the problem to a single channel by making Play the only way software could arrive. It is now relinquished (see above), so both remaining layers carry the whole load. Nothing about them changes — they never inspected the installer — but a sideloaded app is unapproved for a second or two rather than never installing at all.

The watchdog exists for a specific reason worth knowing before you try to delete it. `ACTION_PACKAGE_ADDED` is **not** on Android's [implicit broadcast exception list](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions), so a manifest-declared receiver for it is silently never delivered to an app targeting API 26 or higher. Runtime registration still works, but only while a process is alive to hold it — hence a foreground service, and hence the permanent low-priority notification.

If you would rather have a clean status bar, set `Policy.RUN_INSTALL_WATCHDOG = false`. Enforcement then falls back to the 15-minute sweep, which is the platform's floor for periodic jobs. Fifteen minutes is a long time for an unapproved app to be running.

Everything is idempotent, so boot, the watchdog, the sweep, and the maintenance button all just call into the same code.

---

## Testing on an emulator

Most of this app is plain AOSP `DevicePolicyManager`, and an emulator runs it faithfully — an AVD is also a device you can factory reset in two seconds, which is what iterating on a Device Owner actually needs. What an emulator cannot tell you is the OEM-skin behaviour warned about below, or whether your real VPN client's tunnel holds.

Use an image with **no Google account configured**; Device Owner cannot be established otherwise. A Play-enabled image is fine and lets you exercise the real Play install path, as long as you provision before signing in.

Build and install the debug variant, not the release one:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`DISALLOW_DEBUGGING_FEATURES` is now relinquished, so this no longer bites — but it is worth knowing why the debug variant exists, and it comes straight back if you ever put the restriction into `USER_RESTRICTIONS` again. `AdminReceiver.onEnabled` applies the whole policy the instant ownership is set, and that restriction turns off USB debugging — so a release build would drop your adb link one step after `dpm set-device-owner`, before you could install anything, read a log or press a button. This is measured, not theoretical:

```
19:10:00.815 DevicePolicyManager: Changing user restriction no_debugging_features on user 0 to: true
19:10:00.930 AdbService:          setAdbEnabled(false), mIsAdbUsbEnabled=true
```

115 ms from the restriction landing to `adbd` being shut down, and `adb devices` reads `offline` from then on. Worse, undoing it is not symmetrical: clearing the restriction only *permits* debugging again, and adbd does not restart by itself — USB debugging has to be switched back on by hand in Developer options. If you cannot reach the device screen, the only way out is a factory reset. The debug variant clears the restrictions named in `Policy.DEBUG_SKIPPED_RESTRICTIONS` instead of applying them; that set is empty today, because the one entry it held is now given up on every build. Its status panel still says which variant you are running, and reports the skipped count rather than pretending the set is short:

```
*** DEBUG BUILD - adb left enabled, do not deploy ***

Device owner   : yes
Restrictions   : 4 / 4
```

An AVD that has finished setup refuses `dpm set-device-owner`, so clear the setup flags around it:

```bash
adb shell settings put global device_provisioned 0
adb shell settings put secure user_setup_complete 0
adb shell dpm set-device-owner me.perdomo.dpc/.AdminReceiver
adb shell settings put global device_provisioned 1
adb shell settings put secure user_setup_complete 1
```

Set a real passcode first (step 2). With the shipped `REPLACE_ME` hash the maintenance screen cannot be unlocked at all, which also removes your on-device recovery path.

To watch enforcement, install any small APK whose package is not in `APPROVED_PACKAGES` and follow the log:

```bash
adb logcat -s MinimalDPC:V
```

To exercise always-on VPN without a real tunnel, a stub app with an empty `VpnService` and the right package name is enough — `setAlwaysOnVpnPackage` only requires an installed service the platform can start. Confirm it landed with:

```bash
adb shell settings get secure always_on_vpn_app
adb shell settings get secure always_on_vpn_lockdown
```

---

## Things that will bite you

**Lockdown plus a broken VPN profile means no network at all.** This app adds itself to the lockdown exemption list on Android 10+ so the maintenance screen stays reachable, but on Android 8–9 the platform has no exemption list. Test your tunnel before enabling lockdown, and keep physical access — which, for this design, you have by definition. The maintenance screen itself is local and needs no network on any version, so the lockdown switch is always reachable to undo this.

**Play needs a signed-in Google account**, and Device Owner provisioning requires a device with *no* accounts. So the order is: provision first, then add the Google account, then apply lockdown. `DISALLOW_MODIFY_ACCOUNTS` is left out of the defaults for this reason; add it back once the account is set up if you want to pin it.

**Enforcement is reactive.** Repeated because it is the one thing people misremember about this design: an unapproved app runs briefly before it is removed. See *When this is the wrong tool*.

**A killed watchdog degrades enforcement silently.** The status panel reports `Install watch : NOT RUNNING` when this has happened, and the 15-minute sweep restarts it, but that is the ceiling on how long you can be running blind. Aggressive OEM battery management is the usual cause.

**Device Owner cannot be re-established without a factory reset.** There is no undo for *Release device ownership*. It is also the only chance to put the device back the way it was — see the ordering note under *Handing a device back*, because a release that skips a step leaves damage no later run can repair.

**`adb shell dpm remove-active-admin` will not remove this app.** The platform refuses it for any admin not marked `android:testOnly`, which this one deliberately is not. The maintenance screen's *Release device ownership* is the only removal path short of a wipe — which is another reason the passcode has to be set before you provision anything.

**OEM variation is real.** `setApplicationHidden`, `setPackagesSuspended` and always-on VPN all behave inconsistently on heavily-skinned ROMs, particularly some Chinese OEM builds. Test on your exact hardware, not on an emulator.

---

## Status

Compiles clean and passes a full functional run on an API 36 emulator: provisioning, all six restrictions, always-on VPN with lockdown, the passcode gate, hide-and-uninstall of an unapproved app (hidden in ~40 ms, gone in ~150 ms), the maintenance window, reboot persistence, the periodic sweep, and release of ownership.

The on-device controls are covered too, driven through the real UI rather than by calling into the code:

- **VPN switches** — every transition, including that lockdown is remembered across an always-on off/on round trip, and that the stored setting survives the sweep, a reboot and `adb install -r` instead of reverting to the compiled default.
- **Approved apps** — add, remove, and rejection of malformed, duplicate and blank entries; an unapproved install removed in about a second and an approved one left alone; removing an installed app uninstalls it; approving a hidden app un-hides it. Deleting the pinned VPN client straight out of the prefs XML on disk does not remove it from the list.
- **Hidden apps** — 30 entries applied by the sweep, ten real stock apps hidden (launcher icons 19 → 10), all six refusals held, and un-hiding restores the app.
- **Release of ownership** — the ordering contract above, verified by releasing a device with ten stock apps hidden and confirming all ten came back.

Both lists were exercised with 30 entries each to check the screen still scrolls and stays usable.
