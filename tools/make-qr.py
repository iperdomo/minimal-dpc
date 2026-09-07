#!/usr/bin/env python3
"""Build the Device Owner provisioning payload (and QR code) for this DPC.

Only needed if you provision by QR. With physical access and USB debugging,
`adb shell dpm set-device-owner` is simpler and needs none of this.

Usage:
    python3 tools/make-qr.py --apk app/build/outputs/apk/release/app-release.apk \
                             --url https://example.com/dpc.apk \
                             [--wifi-ssid SSID --wifi-password PASS] \
                             [--out qr.png]
"""
import argparse
import base64
import json
import re
import shutil
import subprocess
import sys

COMPONENT = "me.perdomo.dpc/me.perdomo.dpc.AdminReceiver"


def cert_sha256_hex(apk: str) -> str:
    """SHA-256 of the APK signing certificate, as hex."""
    if shutil.which("apksigner"):
        out = subprocess.run(
            ["apksigner", "verify", "--print-certs", apk],
            capture_output=True, text=True, check=True).stdout
        m = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", out)
        if m:
            return m.group(1)

    if shutil.which("keytool"):
        out = subprocess.run(
            ["keytool", "-printcert", "-jarfile", apk],
            capture_output=True, text=True, check=True).stdout
        m = re.search(r"SHA256:\s*((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})", out)
        if m:
            return m.group(1).replace(":", "")

    raise SystemExit(
        "Need apksigner or keytool on PATH to read the signing certificate.\n"
        "Both ship with the Android SDK / a JDK.")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True, help="signed release APK")
    p.add_argument("--url", required=True,
                   help="HTTPS URL the device downloads the APK from")
    p.add_argument("--wifi-ssid")
    p.add_argument("--wifi-password")
    p.add_argument("--wifi-security", default="WPA",
                   choices=["WPA", "WEP", "NONE"])
    p.add_argument("--out", help="write a PNG here (needs the qrcode package)")
    args = p.parse_args()

    digest = bytes.fromhex(cert_sha256_hex(args.apk))
    checksum = base64.urlsafe_b64encode(digest).decode().rstrip("=")

    payload = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": COMPONENT,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": args.url,
        # Without this, provisioning disables most system apps.
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": False,
    }
    if args.wifi_ssid:
        payload["android.app.extra.PROVISIONING_WIFI_SSID"] = args.wifi_ssid
        payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = args.wifi_security
        if args.wifi_password:
            payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = args.wifi_password

    text = json.dumps(payload, separators=(",", ":"))
    print(text)

    if args.out:
        try:
            import qrcode
        except ImportError:
            print("\n`pip install qrcode[pil]` to write a PNG, or pipe the JSON "
                  "above into: qrencode -o qr.png", file=sys.stderr)
            return 1
        qrcode.make(text).save(args.out)
        print(f"\nWrote {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
