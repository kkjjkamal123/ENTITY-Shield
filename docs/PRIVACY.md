# Privacy and security of ENTITY Shield itself

A security app that leaks data would defeat its purpose. This page states exactly what Shield touches.

## What stays on the phone

| Data | Used for | Leaves the phone? |
|---|---|---|
| Incoming SMS text and sender | SMS detector, alert, Llama explanation | **No** |
| Inbox (READ_SMS) | Inbox audit only, when you press Scan | **No.** The export file holds counts only |
| Links you share or open with Shield | URL detector, then the link sandbox | The address check never leaves the phone. The **sandbox visits the link** only when you check it or open it with Shield, never on its own (not for incoming SMS). The visit is a plain GET with a generic mobile browser identity: no cookies, no referrer, no account, no JavaScript. Private and local addresses (routers, 192.168.x.x) are refused. The site sees a visit from your phone's network, as it would if you opened the link yourself |
| DNS lookups of your apps | Network Guard scoring and per-app view | Forwarded unchanged to **your own network's resolver** (the same place they went before). Blocked ones go nowhere |
| Connections from the Wi-Fi to the decoy ports | Tripwire alert | **No.** The first bytes are read for the alert, then the connection is closed |
| Alert history | The Alerts list | **No** (`alerts.jsonl` in app-private storage; no backup: `allowBackup=false`). Message text is deleted on its own: safe messages after 7 days, alerts after 30 |
| Your "Scam / Not a scam" answers | Messages like it follow your answer (`sms_labels.json`) | **No.** Kept as the message's words with every number, code and amount removed and links cut to their domain; at most 500, oldest dropped. A one- or two-word message from a number is kept only for that number |
| Contacts (READ_CONTACTS, optional) | A flagged SMS from a saved contact is not called a scam | **No.** Only "is this number saved?" is asked; names and other numbers are never read or stored |
| Allowed sites | Sites you trust, for Network Guard and SMS links | **No** (app preferences) |
| The Llama model files | Explanations | **Downloaded once** if you tap Download Llama (a plain file download from Hugging Face, the official ExecuTorch export, with the project's GitHub release as fallback; SHA-256 checked); nothing about you is sent. Importing from storage needs no network |

There is no account, no analytics SDK, no crash reporter, no cloud model and no remote configuration.

## Permissions and why

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Check each SMS as it arrives |
| `READ_SMS` | Inbox audit (optional, user-started) |
| `READ_CONTACTS` | Optional: is the sender a saved contact? (a lookup, nothing copied) |
| `POST_NOTIFICATIONS` | Show the alert |
| `RECEIVE_BOOT_COMPLETED` | Turn Network Guard back on after a restart, if it was on |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask (one system dialog) that Android not pause Shield in the background |
| `FOREGROUND_SERVICE` (data sync, short service) | Model download; keeping an explanation running when you switch apps |
| `INTERNET` | Network Guard's DNS forwarding, link sandbox visits you start, the one-time model download. Nothing else: no analytics, no cloud model, no uploads |
| `ACCESS_NETWORK_STATE` | Find the real network's resolver and the phone's IP |
| `QUERY_ALL_PACKAGES` | Name the app behind a lookup ("Chrome tried to open …") |
| `BIND_VPN_SERVICE` (system) | The local DNS-only VPN, after Android's one-time consent dialog |

## Data used to build and test the detectors

- Training and test data are public datasets (UCI SMS Spam Collection, PhishTank, OpenPhish, Tranco,
  Cisco Umbrella) and Indian message templates written for this project (`training/sms/indian_sms.py`,
  `training/sms/challenge.py`). No real person's messages are in them.
- One real inbox was checked, the developer's own phone, with his consent: on the phone, by the app's
  Inbox audit (only counts leave the phone) and once on a laptop from a local copy that was deleted
  afterwards. No message, number or name from it is in this repository or in any dataset.

## Offline proof

Everything that decides and explains works in airplane mode: the detectors, Llama and the fixed steps.
The demo video shows it with mobile data and Wi-Fi off. Network Guard needs a network only because it
forwards your existing lookups.

## The app's own attack surface

- **Network Guard's forwarder** answers only DNS queries arriving on the private TUN interface (no
  listening socket on the network). Packets that are not IPv4/UDP/53 are dropped. Lengths are
  bounds-checked (`DnsPacket`, unit-tested), and compressed or oversized names are rejected.
- **The tripwire** never serves anything. It reads at most 128 bytes with a 1.5 s timeout, then closes.
- **The link sandbox** parses untrusted pages only inside an Android `isolatedProcess` service: a
  throw-away UID with no permissions, no access to Shield's files and no network (verified on the
  device: reading Shield's files and opening a socket both fail). The page arrives as read-only shared
  memory; the fetcher caps size (768 KB), time (10 s), redirects (8) and decompression (zip-bomb safe).
  Page text is never given to Llama, so a page cannot instruct the model; Llama gets only the findings.
- **Debug hooks** (`DebugReceiver`) exist only in debug and internal QA builds (`src/debug`), never in
  the release APK, and are protected by `android.permission.DUMP`, which only the adb shell holds.
- **The Llama output** is shown as plain text, never executed or opened as a link. Phone numbers and
  links shown as actions come from fixed text (1930, cybercrime.gov.in), not from the model.
- **Allow-lists** are bundled and read-only. There is no remote update channel an attacker could abuse.
