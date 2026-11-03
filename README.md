# cross-clip 📋⚡

Free, lightweight, zero-cost, end-to-end encrypted (E2EE) bidirectional clipboard synchronization between Android phones (optimized for Oppo / ColorOS) and Windows PCs over a cloud WebSocket relay.

```
+------------------+         End-to-End Encrypted         +------------------+
|   Windows PC     | <----------------------------------> |  Android Phone   |
| (C# .NET 8 Tray) |          Cloudflare Worker           | (Kotlin Service) |
+------------------+          + SQLite Durable            +------------------+
                                   Object
```

---

## Key Features
- **Rs 0 Total Cost**: Cloudflare Workers + SQLite-backed Durable Object on the 100% free plan (no credit/debit card required).
- **True End-to-End Encryption**: 256-bit AES-GCM with HKDF-SHA256 key derivation. The cloud relay only sees opaque encrypted envelopes and room IDs—never your plaintext clips.
- **Zero-Friction Workflow**:
  - Copy on phone ➔ immediately paste with `Ctrl + V` on Windows.
  - Copy on Windows (`Ctrl + C`) ➔ immediately paste with long-press on phone.
- **ColorOS / Oppo K13 5G Optimized**: Bypasses Android 10+ background clipboard blocks using ADB-granted overlay rights + transparent zero-frame launch (<150ms).
- **Zero Battery/Memory Drain**: Hidden background service on Android with `IMPORTANCE_MIN` silent notification. Windows runs purely as a tiny system tray icon with single-instance mutex.
- **Echo-Loop Guard**: Cryptographic SHA-256 hash deduplication prevents infinite copy reflection loops.
- **Bank-Safe & UPI Friendly**: One-tap notification pause toggle, Quick Settings tile, and an instant ADB `banking-mode.bat` switch.

---

## Architecture & Monorepo Layout
```
cross-clip/
├── server/                 # Cloudflare Worker + SQLite Durable Object (TypeScript)
│   ├── src/index.ts        # WebSocket Hibernation & DO relay
│   └── wrangler.toml       # SQLite DO configuration (free tier)
├── windows/                # Windows .NET 8 WinForms system tray application
│   ├── CrossClip/          # Tray icon, Win32 AddClipboardFormatListener, AES-GCM
│   └── CrossClip.Tests/    # Unit tests for HKDF, AES-GCM & EchoLoopGuard
├── android/                # Native Android Kotlin application (minSdk 26)
│   └── app/src/main/       # SyncService, ClipCaptureActivity, Quick Settings tile
├── docs/
│   └── PROTOCOL.md         # Wire protocol and cryptographic specification
├── setup-android.bat       # One-click ADB install and permission granter
├── banking-mode.bat        # Instant bank-safe toggle
└── .github/workflows/      # Automated CI: builds Windows exe & Android APK
```

---

## Setup for Beginners (Step-by-Step)

Follow these exact steps in order:

### 1. Deploy the Cloud Relay (Free Cloudflare Worker)
1. Open a command prompt in the `server` directory:
   ```cmd
   cd D:\cross-clip\server
   ```
2. Log in to your Cloudflare account (no payment card needed):
   ```cmd
   npx wrangler login
   ```
   A browser tab will automatically open. Click **Allow**.
3. Deploy the worker:
   ```cmd
   npx wrangler deploy
   ```
4. Note your deployed worker URL (e.g. `https://cross-clip-relay.<your-subdomain>.workers.dev`).

---

### 2. Run the Windows Tray Application
1. The Windows application is already compiled as a standalone executable in `windows\publish\CrossClip.exe` (or downloaded from the GitHub Actions releases).
2. Double-click `CrossClip.exe` to run.
3. Look near your Windows clock in the system tray: you will see the green **cross-clip** icon.
4. Double-click the tray icon to open **cross-clip Setup**:
   - Paste your Cloudflare Server URL into the **Relay Server URL** field.
   - You will see your unique **Pairing Code** and a generated **QR Code**. Keep this window open.

---

### 3. Install & Configure on Oppo K13 5G (Android)
Your phone is already connected via USB with USB debugging enabled.

1. Double-click `setup-android.bat` in `D:\cross-clip`.
2. The script will:
   - Check device connection (`adb devices`).
   - Install `cross-clip.apk`.
   - Automatically grant required ADB permissions:
     * `SYSTEM_ALERT_WINDOW` (allows background clipboard capture)
     * `android.permission.READ_LOGS`
     * Battery optimization whitelist
3. Complete these 3 ColorOS steps directly on your phone:
   - **A. Auto-launch**: Open phone **Settings** ➔ **Apps** ➔ **App management** ➔ **cross-clip** ➔ Tap **Auto-launch** and switch it **ON**.
   - **B. Background Activity**: In the same menu, tap **Battery usage** ➔ Turn **ON** "Allow background activity".
   - **C. Lock in Recents**: Open **cross-clip** on your phone, swipe up to Recent Apps, tap the 3 dots (or lock icon) above cross-clip, and tap **Lock**.

---

### 4. Pair Phone & PC
1. On your phone, open the **cross-clip** app.
2. Enter or paste your **Relay Server URL** and your **Pairing Code** (from Step 2).
3. Tap **Save & Connect**.
4. Both devices will display **Connected**!

---

### 5. Test the Workflow
1. **Phone ➔ PC**:
   - On your phone, open any app (e.g. Chrome, Notes, WhatsApp) and copy any text.
   - Switch to your PC and press `Ctrl + V` in Notepad. Your text appears instantly!
2. **PC ➔ Phone**:
   - On your PC, select any text and press `Ctrl + C`.
   - On your phone, tap and hold any text box and tap **Paste**. The text pastes immediately!

---

## If My Banking / UPI App Says "Risky App Detected"

Some Indian banking and UPI apps (such as PhonePe, Google Pay, YONO SBI, BHIM, iMobile) run root/environment scanners. They may flag devices for:
1. **`SYSTEM_ALERT_WINDOW` (Overlay) Permission**: Used by screen recorders or malicious overlays. Although `cross-clip` never draws any overlay, simply holding the permission can be flagged by strict scanners.
2. **Active Developer Options / USB Debugging**: Some apps refuse to run if USB debugging is enabled in Android settings.
3. **Sideloaded APKs**: Apps not installed directly from Google Play.

### How to Handle It (Honest Practical Advice):
- **Option 1: One-Tap Pause (Quickest)**:
  Swipe down your phone's notification shade. Tap the persistent **cross-clip** notification (or its **⏸ Pause** button). The service stops immediately, disconnecting network activity. Tap again to resume when done.
- **Option 2: Use `banking-mode.bat`**:
  Connect your phone to your PC and double-click `banking-mode.bat`:
  - Choose **[1] Turn ON Banking Mode**: This immediately stops `cross-clip` and completely revokes `SYSTEM_ALERT_WINDOW` and `READ_LOGS` via ADB.
  - Turn **OFF** "Developer options" in phone Settings.
  - Open and use your banking app freely.
  - When you want clipboard sync back: re-enable USB debugging, run `banking-mode.bat`, and choose **[2] Turn OFF Banking Mode** to restore permissions and restart sync.
- **ADB Permission Persistence Test Result**:
  *On the Oppo K13 5G (ColorOS), permissions granted via ADB (`SYSTEM_ALERT_WINDOW` and `READ_LOGS`) remain stored in Android's user permission tables even when USB debugging is toggled OFF.* They only reset if the app is uninstalled or if Android performs an explicit permission reset.
- **Option 3: Last Resort**:
  If a banking app refuses to run despite Developer options being OFF, uninstall `cross-clip` via phone Settings.

---

## Known Platform Limitations
- **Android 12+ System Toast**: On some Android versions, Android may display a small system toast `"cross-clip pasted from your clipboard"` when reading the clip. This is a system security notice that cannot be disabled by third-party apps.
- **Password Manager Protection**: Sensitive clips copied from password managers (Bitwarden, 1Password) flagged with `FLAG_SENSITIVE` may be concealed by Android.
- **Foreground Service Notification**: Android requires background services to maintain an ongoing notification. We have configured it to `IMPORTANCE_MIN` (silent, zero vibration, zero sound, zero badge) and turned it into an interactive pause/resume toggle.

---

## Troubleshooting

### 1. Phone to PC Not Syncing
- Ensure `cross-clip` was granted `SYSTEM_ALERT_WINDOW`. In `MainActivity`, verify the checklist displays `✓ ADB Permissions: GRANTED`. If pending, run `setup-android.bat`.
- Ensure ColorOS has **Auto-launch** and **Allow background activity** turned ON.
- Verify the phone is not in **Paused** state.

### 2. PC to Phone Not Syncing
- Check the Windows tray icon status. Double-click it to verify the status displays **Connected**.
- Make sure both devices are pointing to the exact same Cloudflare Worker URL and have identical Pairing Codes.

### 3. Reconnection After Phone Reboot
- `cross-clip` includes a `BootReceiver` that automatically restarts the sync service when your phone turns on.
- If ColorOS aggressively stops the boot receiver, open `cross-clip` once after restart or lock it in Recents.
