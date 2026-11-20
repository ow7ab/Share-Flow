# ShareFlow — Manual Two-Device P2P Discovery & Connection Testing Checklist

Use this checklist to validate ShareFlow's peer-to-peer Device Discovery and Connection Engine on **two physical Android devices** (recommended: one running Android 13–16 and one running Android 10–12L to verify version-specific permissions and APIs).

---

## Prerequisites
- [ ] Install the same ShareFlow debug/release build on **Device A** (Receiver) and **Device B** (Sender).
- [ ] Verify **zero internet requirement**: turn off Mobile Data or test on an isolated router / Wi-Fi Direct / Local-Only Hotspot with no WAN uplink.
- [ ] Confirm both devices have unique **Device Names** in **Settings → Device Information** and note each device's **Short Device ID** and **Key Fingerprint** in **Nearby Devices & Pairing**.

---

## 1. Android Version Permission & Capability Handling
- [ ] **Android 13+ (API 33+)**: Open **Nearby Devices & Pairing** → tap **Grant Permission** on the permission banner. Verify Android requests **Nearby Wi-Fi Devices** (`NEARBY_WIFI_DEVICES`) and does **NOT** request Location access.
- [ ] **Android 7–12L (API 24–32)**: Verify Android requests **Fine Location** (`ACCESS_FINE_LOCATION`) only when Wi-Fi Direct or Local Hotspot is used.
- [ ] **Permission Denial**: Deny the permission once → confirm the app does **not** crash, displays the **Needs Permission** status pill on Wi-Fi Direct / Hotspot, and still allows **Local Wi-Fi (NSD)** discovery without location permissions.
- [ ] **Wi-Fi Turned Off**: Manually turn off Wi-Fi in Quick Settings → verify ShareFlow displays **Wi-Fi Off** with an **Open Settings** button (and does not attempt to force-enable Wi-Fi programmatically).
- [ ] **Location Services Off (API ≤ 32 only)**: Turn off Location in Quick Settings → verify ShareFlow displays the Location guidance banner with **Open Settings**.
- [ ] **No Wi-Fi Direct Hardware (Emulator/Older Tablet)**: Confirm Wi-Fi Direct shows **Unsupported** cleanly without crashing, while Local Wi-Fi and QR pairing remain usable.

---

## 2. Local Wi-Fi Network Discovery (NSD / mDNS)
- [ ] Connect both Device A and Device B to the **same Wi-Fi network** (ensure router AP/client isolation is disabled).
- [ ] On **Device A**, open **Receive** (or **Nearby Devices & Pairing**) and tap **Start Receiving** / **Make Discoverable**.
  - Confirm status transitions to **Listening on TCP :&lt;port&gt;** and Local Wi-Fi shows **Active**.
- [ ] On **Device B**, open **Nearby Devices & Pairing** and tap **Scan Nearby**.
  - Confirm **Device A** appears under **Nearby ShareFlow Devices** with:
    - [ ] Correct **Device Name**
    - [ ] Matching **Short Device ID** (`ID: ...`)
    - [ ] Transport label **Local Wi-Fi**
    - [ ] Status badge **Available**
  - Confirm **Device B** does **not** list itself and does **not** duplicate Device A even across repeated scans.
  - Confirm non-ShareFlow mDNS devices on the same LAN (printers, Chromecasts, TVs) are filtered out.

---

## 3. Wi-Fi Direct (P2P DNS-SD) Discovery & Connection
- [ ] Disconnect both phones from any router Wi-Fi (keep Wi-Fi radio ON on both).
- [ ] Grant required runtime permission on both devices.
- [ ] On **Device A**, tap **Make Discoverable** (verify Wi-Fi Direct status becomes **Active**).
- [ ] On **Device B**, tap **Scan Nearby**.
  - Confirm **Device A** appears with transport label **Wi-Fi Direct** and non-ShareFlow Wi-Fi Direct TVs/printers are excluded.
- [ ] Tap **Device A** on **Device B** → approve Android's system Wi-Fi Direct group prompt if shown → proceed to **Section 6 (Mutual Authentication & Approval)**.

---

## 4. Local-Only Hotspot + QR Pairing
- [ ] On **Device A** (Android 8.0+ with permission granted), enable **Enable Local-Only Hotspot**, then tap **Start Receiving** / **Make Discoverable**.
  - Confirm Android allocates a local hotspot and displays **Hotspot SSID: ...**.
- [ ] On **Device A**, open **QR Code Pairing** (**My QR Code** tab).
  - Confirm a crisp, scannable QR code appears alongside:
    - [ ] **Includes Local Hotspot Credentials** badge
    - [ ] Live token expiration countdown (`Token expires in ...s`)
    - [ ] Random session ID and cryptographic key fingerprint
- [ ] On **Device B**, open **QR Code Pairing** → switch to **Scan Peer QR**:
  - [ ] Tap **Allow Camera** when prompted (test denying first → confirm graceful fallback to manual pairing URI paste!).
  - [ ] Scan Device A's QR code with the live CameraX preview.
  - [ ] On Android 10+, approve Android's system Wi-Fi network connection prompt to join Device A's local hotspot.
  - [ ] Confirm encrypted handshake initiates automatically and Device A displays the **Incoming Connection Request** dialog with the **Short-Lived QR Token Verified** badge.

---

## 5. Discovery Timeout & Failure Handling
- [ ] Turn off ShareFlow on Device A, then tap **Scan Nearby** on Device B.
- [ ] Wait 30 seconds without stopping manually.
  - Confirm scan stops automatically and displays the **Discovery timed out** card with a working **Retry** button.
  - Confirm no fake/simulated devices ever appear.

---

## 6. Mutual Authentication, Explicit Approval & Security Checks
- [ ] Initiate a connection from **Device B** to **Device A** (via Local Wi-Fi, Wi-Fi Direct, or QR code).
- [ ] On **Device A**, verify the **Incoming Connection Request** dialog appears (even if you navigate to Home or Files tab!) showing:
  - [ ] Device B's **Device Name**
  - [ ] Device B's **Device Identifier**
  - [ ] Device B's **Cryptographic Fingerprint**
  - [ ] **6-digit Verification Code (SAS)**
- [ ] **Explicit Rejection Test**: Tap **Reject** on Device A.
  - Confirm Device B immediately transitions out of connecting, shows **Declined** / rejection error, and does **not** claim a successful connection.
- [ ] **Explicit Approval Test**: Re-initiate connection from Device B → tap **Approve Connection** on Device A (optionally check **Remember verified fingerprint as trusted device**).
  - Confirm BOTH devices display **Encrypted Peer Connection** (`AES-256-GCM Verified`) with **identical 6-digit Verification Codes (SAS)** and identical **Session IDs**.
- [ ] **QR Token Expiry & Replay Check**:
  - [ ] Copy a QR pairing URI from Device A, connect once, disconnect, then paste the *same* URI again → confirm rejection (`Pairing token already used` / replay rejected).
  - [ ] Wait >120 seconds until the QR countdown reaches 0 → attempt to connect with the expired URI → confirm rejection (`Pairing QR code has expired`).
- [ ] **Name Spoofing Resistance**:
  - [ ] Change an untrusted third device's name in Settings to match a previously trusted device's name → attempt connection → confirm it is **NOT** marked Trusted and still requires explicit user approval because its public key fingerprint does not match.
- [ ] **Clean Disconnect**: Tap **Disconnect** on either device → confirm socket closes cleanly and status returns to **Idle** / **Listening**.

---

## Part 2 — Real Two-Device File Transfer Engine Verification

After completing **Section 6** (both devices show **Encrypted Peer Connection** with matching 6-digit SAS code), execute the following transfer verification tests between **Device B (Sender)** and **Device A (Receiver)**:

### 7. Small File & Bit-Exact Integrity Verification
- [ ] On **Device B**, open **Send** → pick a small photo or PDF (< 1 MB) → tap **Continue** → tap **Start Encrypted Transfer**.
- [ ] On BOTH devices, open **Active Transfers** and verify live UI fields:
  - [ ] File name and exact byte size
  - [ ] Live progress bar & percentage
  - [ ] Measured transfer speed (`B/s`, `KB/s`, or `MB/s`) and remaining time (ETA)
  - [ ] Connected peer device name and state transition (`Pending` → `Transferring` → `Verifying SHA-256` → `Completed`)
- [ ] Verify the **SHA-256 Verified** badge appears ONLY after transfer reaches 100%.
- [ ] Open **Transfer History** on both devices and confirm a new `Completed` record is persisted (kill and relaunch the app to confirm Room persistence).
- [ ] Compare original file bytes/checksum on Device B against the received file in `Android/data/<package>/files/Download/ShareFlowReceived/` on Device A → confirm **identical SHA-256** and **zero compression**.

### 8. Large File Streaming (Memory & Backpressure Test)
- [ ] Select a large video or archive (> 100 MB) on **Device B** and send to **Device A**.
- [ ] Monitor Android Studio Profiler / Device Memory during transfer → confirm RAM usage stays flat (chunked streaming I/O; entire file is never loaded into heap).
- [ ] Confirm smooth speed & ETA updates and exact SHA-256 match on completion.

### 9. Multiple Files & Relative Folder Structure Preservation
- [ ] On **Device B**, open **Send** → tap **Add Folder (Preserve Structure)** and choose a folder containing nested subfolders (e.g., `Project/Docs/spec.pdf`, `Project/Media/clip.mp4`).
- [ ] Send to **Device A** → confirm all files appear in the batch card and the exact relative subfolder hierarchy is recreated under `ShareFlowReceived/Project/Docs/spec.pdf` and `ShareFlowReceived/Project/Media/clip.mp4`.

### 10. Pause & Resume Mid-Transfer
- [ ] Start sending a large file (> 50 MB) from **Device B** to **Device A**.
- [ ] Mid-transfer (~30–60%), tap **Pause** on **Device B**.
  - [ ] Confirm state transitions to **Paused**, speed drops to `0 B/s`, and progress freezes without losing transferred bytes.
- [ ] Tap **Resume** → confirm transfer continues smoothly from the verified chunk offset and finishes with **SHA-256 Verified**.

### 11. Network Interruption & Verified Reconnection Recovery
- [ ] Start sending a large file (> 50 MB) from **Device B** to **Device A**.
- [ ] Mid-transfer, toggle Wi-Fi OFF on **Device A** (or walk out of range) until the connection drops.
  - [ ] Confirm the transfer is **NOT** marked `Completed` and transitions to `Paused` / `Failed` (recoverable) while preserving the `.part` staging file.
- [ ] Re-enable Wi-Fi, reconnect Device B to Device A via **Nearby Devices & Pairing**, open **Active Transfers**, and tap **Resume / Retry**.
  - [ ] Confirm `TransferRecoveryManager` verifies peer fingerprint and on-disk `.part` integrity, skips already-verified chunks, and completes with full-file **SHA-256 Verified**.

### 12. Duplicate Filename Protection (Zero Silent Overwrite)
- [ ] Send the **same file** (`report.pdf`) a second time from **Device B** to **Device A**.
- [ ] Verify the original `report.pdf` on Device A is completely untouched and the second transfer is saved as `report (1).pdf`.

### 13. Cancellation & Staging Cleanup
- [ ] Start a large file transfer and tap **Cancel** → confirm the destructive confirmation dialog appears.
- [ ] Confirm cancellation → verify state transitions to **Cancelled** (never `Completed`) and no orphaned `.part` file remains in `ShareFlowReceived/.shareflow_staging/`.

### 14. Low Storage Protection
- [ ] When available storage on **Device A** is below the incoming batch size + safety headroom (4 MB), confirm **Device A** rejects the transfer offer cleanly before writing partial data and reports an explicit storage error.

