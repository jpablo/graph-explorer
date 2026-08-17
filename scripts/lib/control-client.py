#!/usr/bin/env python3
"""One RPC call against the desktop's control socket.

The gates used to reach the desktop with `curl`. There is no HTTP any more
(docs/desktop-gx-v2-architecture.md D4), and bash cannot speak a unix socket on
its own -- `nc -U` exists on macOS and on Linux with the openbsd netcat, which
is two platforms' worth of "probably". python3 is already a hard dependency of
the disk->UI gate, so it is the one interpreter every POSIX runner is known to
have.

This deliberately does NOT go through `gx`. The gates test the desktop's
contract, and routing them through the reference client would test one client's
view of it instead -- the same reasoning that moved them off the Rust `gx` in
P3.

Usage:
    control-client.py <method> [json-params] [--socket PATH] [--timeout SECONDS]

Prints the response frame to stdout. Exit status:
    0  the call returned ok
    1  the call returned an error frame (the frame is still printed)
    2  could not reach the desktop at all
"""

import json
import os
import socket
import sys

DEFAULT_CONTROL = os.path.join(
    os.path.expanduser("~"), ".graph-explorer", "runtime", "control.json"
)


def socket_path(explicit):
    """Where the desktop said its socket is.

    Read from the runtime file rather than reconstructed, so the gate and the
    desktop cannot disagree about the path.
    """
    if explicit:
        return explicit
    control = os.environ.get("CONTROL_RUNTIME_FILE", DEFAULT_CONTROL)
    try:
        with open(control, "r", encoding="utf-8") as handle:
            return json.load(handle).get("socket") or ""
    except (OSError, ValueError):
        return ""


def main(argv):
    method = None
    params = {}
    explicit_socket = ""
    timeout = 10.0

    positional = []
    index = 0
    while index < len(argv):
        argument = argv[index]
        if argument == "--socket":
            index += 1
            explicit_socket = argv[index]
        elif argument == "--timeout":
            index += 1
            timeout = float(argv[index])
        else:
            positional.append(argument)
        index += 1

    if not positional:
        print("usage: control-client.py <method> [json-params]", file=sys.stderr)
        return 2
    method = positional[0]
    if len(positional) > 1 and positional[1].strip():
        params = json.loads(positional[1])

    path = socket_path(explicit_socket)
    if not path:
        print("no control socket recorded; is the desktop running?", file=sys.stderr)
        return 2

    # A socket file outlives a crashed desktop, so its existence proves nothing.
    # connect() is what settles it -- and ECONNREFUSED here is the ordinary
    # "no desktop" case, not a broken gate.
    try:
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(path)
    except OSError as error:
        print(f"cannot reach the desktop at {path}: {error}", file=sys.stderr)
        return 2

    try:
        # The path travels as a JSON string. No percent-encoding, and so no
        # percent-decoding to get wrong -- which is the bug class D4 retires.
        request = json.dumps({"id": 1, "method": method, "params": params})
        client.sendall((request + "\n").encode("utf-8"))

        # Frames are newline-delimited. Bytes are accumulated and decoded once:
        # a recv can split mid-character, and decoding each chunk would corrupt
        # exactly the non-ASCII paths V-16 is about.
        buffer = bytearray()
        while b"\n" not in buffer:
            chunk = client.recv(65536)
            if not chunk:
                break
            buffer.extend(chunk)
    except OSError as error:
        print(f"control call failed: {error}", file=sys.stderr)
        return 2
    finally:
        client.close()

    line, _, _ = bytes(buffer).partition(b"\n")
    if not line:
        print("desktop closed the connection without answering", file=sys.stderr)
        return 2

    text = line.decode("utf-8")
    print(text)
    try:
        return 0 if json.loads(text).get("ok") else 1
    except ValueError:
        print("unparseable response frame", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
