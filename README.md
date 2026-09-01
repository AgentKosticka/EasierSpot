[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/agentkosticka)
# Easier Spot

Easier Spot is an Android application that simplifies Wi-Fi hotspot sharing between devices using Bluetooth Low Energy (BLE). Instead of manually sharing hotspot credentials, Easier Spot allows devices to automatically discover and connect to nearby hotspots.

## Overview

Allows you to share your hotspot with your other devices and/or other people. Think apple continuity or Samsung's "Connect to device" but for anyone using two android devices.

## Getting the app
- You can download prebuilt `.apk` files in the [releases section](https://github.com/AgentKosticka/EasierSpot/releases)
- Alternatively you can [build from source](#build-instructions)

### Requirements
- Install requirements:
  - Android 12 or above (Android SDK 31 or above)
  - Ability to sideload applications (install from .apk)
- Runtime requirements:
  - Bluetooth → For communicating between the server and client
  - Location → **WE DO NOT USE YOUR GPS**, the android system classifies scanning for BLE devices as being able to track the users location in 3D space. We, however, use it exclusively for finding a suitable server to connect to
  - Notifications → Used for approvals, foreground-service status, and Connect actions when a previously paired server is nearby
  - Network state access → To automatically connect you to the Wi-Fi 
  - Shizuku → **only required if you plan to run the server** because without it, we cannot turn your hotspot on without prompting you to do it yourself.

### How It Works

Easier Spot uses one dashboard with sharing and nearby-device controls:

**Server Mode** (Hotspot Owner) (Requires Shizuku to work)
- Reads your device's active Wi-Fi hotspot credentials using privileged system APIs
- Publishes a slow, low-power BLE availability beacon while listening for authenticated wake requests from paired clients
- Starts the hotspot immediately after an authenticated tap-to-connect wake request
- Uses secure BLE GATT only for first pairing, changed credentials, and fallback
- Confirms connected clients over an authenticated, network-bound UDP control channel
- Manages device approval with configurable policies (auto-approve, always-ask, or auto-deny) and ability to nickname devices to quickly distinguish them from each other

**Client Mode** (Connecting Device)
- Uses Android's OS-owned filtered PendingIntent BLE scan while idle; no idle client service or polling loop runs
- Connects to discovered servers and requests hotspot credentials
- Remembers an authenticated server only after the first approved pairing
- Offers one expiring Connect notification when a paired sharing phone becomes nearby
- Sends the authenticated wake only after Connect is tapped, starting hotspot activation before GATT
- Keeps every provisioned network as an Android-owned suggestion and adaptively races it with Shizuku acceleration
- Declares success only after the expected Wi-Fi has an address and the paired phone returns an authenticated ACK
- Sends one small authenticated UDP heartbeat per minute while connected

### Key Features

- **Low-power discovery**: OS-owned filtered scanning on the client and ultra-low-power advertising plus route-filtered wake scanning on the server
- **Automatic Cleanup**: After three missed heartbeat windows, the server stops only hotspots that Easier Spot itself started. User-started hotspots are never auto-stopped
- **Out-of-range Signal**: A disconnected client emits a short authenticated high-power BLE burst so the server can react even after Wi-Fi is gone
- **Ease of use**: App features a simplistic UI that gets the job done
- **Manual Control**: Server owners approve or deny connection requests from client devices

## Building from Source
### Prerequisites

- **Android Studio**
- **JDK 11 or higher**
- **Android SDK** with API level 36 installed
- **Git** for cloning the repository

### Build Instructions

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd EasierSpot
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the cloned Easier Spot directory
   - Click Gradle on the right panel and select "Download sources"

3. **Sync Gradle**
   - Android Studio should automatically trigger a Gradle sync
   - If not, click "File → Sync Project with Gradle Files"

4. **Build the APK**

   Using Android Studio:
   - Enable USB/Wireless debugging in your phones developer options → Connect to PC → up top select the green triangle to build and install on devices

5. **Install on Device**
   ```bash
   # Install debug APK via ADB
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Build Variants

- **Debug**: Includes debugging symbols, not optimized
- **Release**: Optimized build (requires signing configuration for distribution)

## Usage

### Initial Setup

1. **Install and set up Shizuku** (only for server devices)
3. **Grant Shizuku permission** to Easier Spot when prompted
4. **Grant runtime permissions** (Bluetooth, location, notifications)

### Server Setup (Hotspot Owner)

1. Open Easier Spot and enable "Share from this phone"
2. The app will start low-power advertising via BLE
3. When a client connects, approve or deny the connection request
4. Manage remembered devices and approval policies in settings

### Client Setup (Connecting Device)

1. Open Easier Spot and open the nearby-device list
2. The app starts a bounded foreground scan automatically
3. Tap on a discovered server to connect
4. Once approved, Easier Spot installs an app-owned Wi-Fi suggestion; Auto mode also uses Shizuku when it can make the switch faster
5. Future sightings appear as a background Connect notification. Tap once to wake the phone, start its hotspot, and join

Both phones must run protocol-v3 builds. Older protocol versions are intentionally not discovered or accepted.

### Project Structure

```
app/src/main/java/com/agentkosticka/EasierSpot/
├── ble/           # BLE client/server implementation
├── data/          # Room database and data models
├── hotspot/       # Hotspot credential retrieval via Shizuku
├── service/       # Foreground service for BLE operations
├── ui/            # Activities and UI components
└── util/          # Logging and utility functions
```

## Known Limitations

- **Shizuku Dependency**: Requires Shizuku running with elevated privileges to run the server - this is so that the app doesn't need to prompt the user to enable the hotspot settings in the background and so it can read hotspot config
- **Android 12+**: Only compatible with Android 12 and newer due to API requirements - we are working on that but getting hidden api calls right across different devices even on the same Android version is surprisingly hard
- **BLE Range**: Limited to Bluetooth range (typically 10-30 meters). Really intended for your own devices and/or leeching friends with no service

## License

We use the **GNU General Public License v3.0**. Read more [here](LICENSE)

## Contributing

Any contribution is welcome so long as it provides helpful and new insights and/or fixes existing bugs within the codebase.
