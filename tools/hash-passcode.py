#!/usr/bin/env python3
"""Generate the PBKDF2 salt/hash pair for Policy.java.

Usage:
    python3 tools/hash-passcode.py            # prompts, input hidden
    python3 tools/hash-passcode.py 'secret'   # non-interactive
"""
import base64
import getpass
import hashlib
import os
import sys

ITERATIONS = 120_000   # must match Policy.PASSCODE_ITERATIONS
KEY_BYTES = 32


def main() -> int:
    if len(sys.argv) > 1:
        passcode = sys.argv[1]
    else:
        passcode = getpass.getpass("New maintenance passcode: ")
        if passcode != getpass.getpass("Repeat: "):
            print("Passcodes do not match.", file=sys.stderr)
            return 1

    if len(passcode) < 6:
        print("Use at least 6 characters.", file=sys.stderr)
        return 1

    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", passcode.encode("utf-8"), salt, ITERATIONS, dklen=KEY_BYTES
    )

    print()
    print("Paste these into app/src/main/java/me/perdomo/dpc/Policy.java:")
    print()
    print(f'    public static final String PASSCODE_SALT_B64 = '
          f'"{base64.b64encode(salt).decode()}";')
    print(f'    public static final String PASSCODE_HASH_B64 = '
          f'"{base64.b64encode(digest).decode()}";')
    print(f'    public static final int PASSCODE_ITERATIONS = {ITERATIONS};')
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
