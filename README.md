# cross-clip

Lightweight, zero-cost, end-to-end encrypted (E2EE) bidirectional clipboard synchronization between Android devices and Windows PCs over a cloud WebSocket relay.

```
+------------------+         End-to-End Encrypted         +------------------+
|    Windows PC    | <----------------------------------> |  Android Device  |
| (C# .NET 8 Tray) |          Cloudflare Worker           | (Kotlin Service) |
+------------------+         + SQLite-backed DO           +------------------+
```

---

## Overview

`cross-clip` enables seamless clipboard synchronization between Windows computers and Android mobile devices across arbitrary network configurations (including cellular networks behind CGNAT and separate Wi-Fi networks).

- **Zero Cost**: Built entirely on free-tier infrastructure (Cloudflare Workers with SQLite Durable Objects) requiring no credit or debit card.
- **End-to-End Encryption**: Clips are encrypted on the client device using AES-256-GCM. The encryption key never leaves the devices. The relay server processes only opaque ciphertexts and anonymous room identifiers.
- **Minimal Footprint**:
  - Windows: Single-instance system tray application with an idle memory footprint under 20 MB.
  - Android: Background foreground service with a minimal-importance silent notification channel. No input method (IME) or accessibility service required.
- **Bidirectional Overwrite**: The latest copied text automatically overwrites the destination clipboard.
- **Loop Prevention**: Monotonically increasing timestamps and SHA-256 hash tracking prevent reflection loops.
- **Financial App Safety**: Minimal permission footprint designed to avoid triggering security scanners in banking and UPI applications.

---

## Repository Structure

```
cross-clip/
├── android/                 # Android client application (Kotlin, minSdk 26)
│   └── app/src/main/        # Foreground service, capture activity, quick settings tile
├── windows/                 # Windows client application (C# .NET 8 WinForms)
│   ├── CrossClip/           # System tray app, Win32 clipboard listener, AES-GCM
│   └── CrossClip.Tests/     # Unit tests for HKDF, AES-GCM, and echo guard
├── server/                  # Cloud relay worker (TypeScript, Wrangler)
│   ├── src/index.ts         # WebSocket hibernation and Durable Object relay
│   └── wrangler.toml        # SQLite Durable Object configuration
├── docs/
│   └── PROTOCOL.md          # Complete wire protocol and cryptographic specification
├── .github/workflows/       # GitHub Actions CI for automated builds and testing
├── setup-android.bat        # Automated ADB installation and permission configuration
├── banking-mode.bat         # Toggle utility for banking-restricted environments
└── LICENSE                  # MIT License
```

---

## Cryptography and Protocol

The system implements a zero-knowledge trust model:

1. **Master Secret**: A 256-bit cryptographic secret generated using a CSPRNG on the Windows client, formatted in Base32 for human pairing.
2. **Key Derivation (RFC 5869 HKDF-SHA256)**:
   - `Room ID = Hex(HKDF-Expand(PRK, "cross-clip-room-id", 16))` (sent to relay).
   - `AES Key = HKDF-Expand(PRK, "cross-clip-aes-key", 32)` (retained locally).
3. **Authenticated Encryption (AES-256-GCM)**:
   - 12-byte random initialization vector (IV) per payload.
   - 16-byte authentication tag appended to ciphertext.
   - 8-byte big-endian millisecond timestamp included as Additional Authenticated Data (AAD) to prevent replay attacks.

For full technical specifications, packet formats, and state transitions, see [docs/PROTOCOL.md](docs/PROTOCOL.md).

---

## Getting Started

### Prerequisites

- A Windows PC running Windows 10 (1809+) or Windows 11.
- An Android device running Android 8.0 (API 26) or newer.
- A free [Cloudflare account](https://dash.cloudflare.com/) (no payment card required).

---

### Step 1: Deploy the Cloud Relay

The relay runs as a serverless Cloudflare Worker utilizing Durable Objects with WebSocket Hibernation.

1. Install Node.js (v18+) if not already present.
2. Navigate to the `server` directory and install dependencies:
   ```bash
   cd server
   npm install
   ```
3. Authenticate with Cloudflare:
   ```bash
   npx wrangler login
   ```
   A browser window will open. Authorize the login request.
4. Deploy the worker:
   ```bash
   npx wrangler deploy
   ```
5. Record your deployed Worker URL from the console output (for example: `https://cross-clip-relay.<subdomain>.workers.dev`).

---

### Step 2: Install and Configure the Windows Application

1. Download `CrossClip.exe` from the latest GitHub Actions build artifact or build locally from source.
2. Run `CrossClip.exe`. The application starts minimized in the system tray.
3. Double-click the tray icon to open settings:
   - Enter your Cloudflare Relay URL in the **Relay Server URL** field.
   - Note your generated **Pairing Code** or keep the QR code visible.
   - Ensure the connection status indicates **Connected**.

---

### Step 3: Install and Configure the Android Application

1. Enable **Developer options** and **USB debugging** on your Android device:
   - Go to **Settings** > **About phone** > tap **Build number** 7 times.
   - In **Settings** > **System** > **Developer options**, turn ON **USB debugging**.
   - *(Oppo / ColorOS / Realme devices)*: Also turn ON **Disable permission monitoring**.
2. Connect your phone to your PC via USB. When prompted on the phone screen, select **Always allow from this computer** and tap **Allow**.
3. Run the automated setup script on your PC:
   ```cmd
   setup-android.bat
   ```
   The script performs the following actions:
   - Installs `cross-clip.apk`.
   - Grants `SYSTEM_ALERT_WINDOW` via ADB (allows background clipboard capture without drawing overlays).
   - Grants `READ_LOGS` via ADB.
   - Adds the application to the battery optimization exemption whitelist.
4. Complete OEM background settings on the device (ColorOS / MIUI / HyperOS / OneUI):
   - **Auto-launch**: Set **cross-clip** to allowed for background launch.
   - **Battery Usage**: Set to **Unrestricted** or **Allow background activity**.
   - **Recents Lock**: Open the app, view Recent Apps, tap the options menu on the card, and select **Lock**.
5. Open the **cross-clip** app on your phone, enter your Relay URL and Pairing Code, and tap **Save & Connect**.
6. Once connected, disconnect the USB cable. The physical connection is no longer needed.

---

## Android Background Clipboard Architecture

Android 10+ restricts background processes from reading the system clipboard. `cross-clip` handles this restriction transparently without requiring accessibility services or custom keyboards:

1. A background foreground service listens for system clipboard change notifications and system clipboard log events via `READ_LOGS`.
2. When a clipboard update event occurs outside the application, the service launches `ClipCaptureActivity`—a fully transparent, zero-frame, zero-animation activity possessing `SYSTEM_ALERT_WINDOW` permissions.
3. The activity gains focus for under 50 milliseconds, reads the primary clipboard, passes the text to the service for encryption and transmission, and immediately invokes `finish()`.
4. Writing incoming remote clips to the clipboard from the background is natively permitted by Android and executes directly within `SyncService`.

---

## Banking and UPI App Compatibility

Certain banking and payment applications (e.g., PhonePe, Google Pay, iMobile, YONO) inspect device security states and may flag active USB debugging connections or overlay permissions:

- **Active USB Connection**: Unplugging the USB cable resolves connection alerts. USB debugging may also be toggled off after initial ADB configuration; permissions remain preserved in user tables on ColorOS and standard Android distributions.
- **One-Tap Pause**: The ongoing persistent notification allows immediate one-tap pausing and resumption without launching the application interface.
- **Emergency Banking Switch**: The included [`banking-mode.bat`](banking-mode.bat) script provides a one-click mechanism to temporarily revoke sensitive permissions via ADB and restore them later.

---

## Building from Source

### Windows Application (.NET 8)

```bash
cd windows
dotnet restore
dotnet test CrossClip.Tests/CrossClip.Tests.csproj
dotnet publish CrossClip/CrossClip.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o publish/
```

### Android Application (Gradle)

```bash
cd android
./gradlew testReleaseUnitTest
./gradlew assembleRelease
```

### Server (Cloudflare Worker)

```bash
cd server
npm install
npm run test
npx wrangler deploy
```

---

## Known Platform Behaviors

- **Android System Toast**: Android 12 and newer may display an intermittent system notification (`cross-clip pasted from your clipboard`) when the application reads clipboard content. This behavior is enforced by the operating system and cannot be modified by applications.
- **Sensitive Clipboard Content**: Items marked with `FLAG_SENSITIVE` (such as passwords copied from specialized credential managers) may be concealed or cleared by the Android system.
- **Reboot Permission Retention**: On select Android vendor builds, `READ_LOGS` permissions may be reset following a device restart. Running `setup-android.bat` restores the required permissions.

---

## License

This project is licensed under the [MIT License](LICENSE).
