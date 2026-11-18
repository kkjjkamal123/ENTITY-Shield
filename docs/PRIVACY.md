# Privacy and security of ENTITY Shield itself

A security app that leaks data would defeat its purpose. This page states exactly what Shield touches.

## What stays on the phone

| Data | Used for | Leaves the phone? |
|---|---|---|
| Incoming SMS text and sender | SMS detector, alert, Llama explanation | **No** |
| Inbox (READ_SMS) | Inbox audit only, when you press Scan | **No.** The export file holds counts only |
| Links you share or open with Shield | URL detector | **No.** No page is fetched to check a link |
| DNS lookups of your apps | Network Guard scoring and per-app view | Forwarded unchanged to **your own network's resolver** (the same place they went before). Blocked ones go nowhere |
| Connections from the Wi-Fi to the decoy ports | Tripwire alert | **No.** The first bytes are read for the alert, then the connection is closed |
| Alert history | The Alerts list | **No** (`alerts.jsonl` in app-private storage; no backup: `allowBackup=false`) |

There is no account, no analytics SDK, no crash reporter, no cloud model and no remote configuration.

## Permissions and why

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Check each SMS as it arrives |
| `READ_SMS` | Inbox audit (optional, user-started) |
| `POST_NOTIFICATIONS` | Show the alert |
| `INTERNET` | **Only** Network Guard's DNS forwarding. Without Network Guard, Shield opens no sockets |
| `ACCESS_NETWORK_STATE` | Find the real network's resolver and the phone's IP |
| `QUERY_ALL_PACKAGES` | Name the app behind a lookup ("Chrome tried to open …") |
| `BIND_VPN_SERVICE` (system) | The local DNS-only VPN, after Android's one-time consent dialog |

## Offline proof

Everything that decides and explains works in airplane mode: the detectors, Llama and the fixed steps.
The demo video shows it with mobile data and Wi-Fi off. Network Guard needs a network only because it
forwards your existing lookups.

## The app's own attack surface

- **Network Guard's forwarder** answers only DNS queries arriving on the private TUN interface (no
  listening socket on the network). Packets that are not IPv4/UDP/53 are dropped. Lengths are
  bounds-checked (`DnsPacket`, unit-tested), and compressed or oversized names are rejected.
- **The tripwire** never serves anything. It reads at most 128 bytes with a 1.5 s timeout, then closes.
- **Debug hooks** (`DebugReceiver`) exist only in debug builds (`src/debug`), never in the release APK.
- **The Llama output** is shown as plain text, never executed or opened as a link. Phone numbers and
  links shown as actions come from fixed text (1930, cybercrime.gov.in), not from the model.
- **Allow-lists** are bundled and read-only. There is no remote update channel an attacker could abuse.
