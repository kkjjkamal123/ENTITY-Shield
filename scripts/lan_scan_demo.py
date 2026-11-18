"""LAN tripwire demo: probe YOUR OWN phone from a laptop on the same Wi-Fi.

  python scripts/lan_scan_demo.py <phone-ip> [--brute] [--adb]

What it does (TCP connect only, no exploits, no payloads beyond a banner line):
  default  touches the tripwire ports once each, like a quick port scan     -> "port scan" alert
  --brute  connects to 2222 seven times in a row, like password guessing     -> "password-guessing" alert
  --adb    sends an ADB 'CNXN' handshake to 5555, like ADB.Miner-style worms -> "ADB probe" alert

Only use it against a device you own, on a network you control. The phone's IP is shown on the
Network screen of ENTITY Shield when Network Guard is on.
"""
import argparse
import socket
import time

PORTS = [2222, 2323, 8080, 8443, 9999, 5555]


def touch(ip, port, payload=b"", timeout=1.5):
    t0 = time.perf_counter()
    try:
        with socket.create_connection((ip, port), timeout=timeout) as s:
            if payload:
                s.sendall(payload)
            return "open", (time.perf_counter() - t0) * 1000
    except ConnectionRefusedError:
        return "closed", (time.perf_counter() - t0) * 1000
    except OSError as e:
        return f"no answer ({e.__class__.__name__})", (time.perf_counter() - t0) * 1000


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ip")
    ap.add_argument("--brute", action="store_true")
    ap.add_argument("--adb", action="store_true")
    a = ap.parse_args()
    start = time.time()
    if a.brute:
        for i in range(7):
            st, ms = touch(a.ip, 2222, b"SSH-2.0-OpenSSH_9.6\r\nroot:password%d\r\n" % i)
            print(f"login attempt {i + 1} -> 2222 {st} ({ms:.0f} ms)")
            time.sleep(0.3)
    elif a.adb:
        st, ms = touch(a.ip, 5555, b"CNXN\x00\x00\x00\x01\x00\x10\x00\x00host::\x00")
        print(f"ADB handshake -> 5555 {st} ({ms:.0f} ms)")
    else:
        for p in PORTS[:-1]:
            st, ms = touch(a.ip, p, b"GET / HTTP/1.0\r\n\r\n" if p in (8080, 8443) else b"")
            print(f"port {p:5d} {st} ({ms:.0f} ms)")
    print(f"done in {time.time() - start:.1f} s at {time.strftime('%H:%M:%S')} -> check the phone for the alert")


if __name__ == "__main__":
    main()
