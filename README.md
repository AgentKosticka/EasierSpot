# Easier Spot

Easier Spot is an Android application that simplifies Wi-Fi hotspot sharing between devices using Bluetooth Low Energy (BLE). Instead of manually sharing hotspot credentials, Easier Spot allows devices to automatically discover and connect to nearby hotspots.

## Overview

Allows you to share your hotspot with your other devices and/or other people. Think apple continuity or Samsungs "Connect to device" but for anyone using two android devices.

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
  - Notifications → We use them to inform you when a client needs to be approved and when you are running the server
  - Network state access → To automatically connect you to the Wi-Fi 
  - Shizuku → **only required if you plan to run the server** because without it, we cannot turn your hotspot on without prompting you to do it yourself.

### How It Works

Easier Spot operates in two modes:

**Server Mode** (Hotspot Owner) (Requires Shizuku to work)
- Reads your device's active Wi-Fi hotspot credentials using privileged system APIs
- Advertises availability via BLE broadcasting
- Shares credentials with approved client devices over a secure BLE GATT connection
- Manages device approval with configurable policies (auto-approve, always-ask, or auto-deny) and ability to nickname devices to quickly distinguish them from each other

**Client Mode** (Connecting Device)
- Scans for nearby Easier Spot servers via BLE
- Connects to discovered servers and requests hotspot credentials
- Receives and displays the hotspot SSID and password and saves them with your system
- Automatically attempts to connect to said Wi-Fi. Sometimes fails and needs to be connected manually clicked in your network settings

### Key Features

- **BLE-Based Discovery**: Low-power Bluetooth scanning and advertising to work for prolonged periods of time without draining the battery
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

1. Open Easier Spot and tap "Server Mode"
2. The app will start advertising via BLE
3. When a client connects, approve or deny the connection request
4. Manage remembered devices and approval policies in settings

### Client Setup (Connecting Device)

1. Open Easier Spot and tap "Client Mode"
2. The app will scan for nearby Easier Spot servers
3. Tap on a discovered server to connect
4. Once approved, the hotspot credentials will save in your system
5. The app should automatically connect to said Wi-Fi, if it doesn't, you will still have the Wi-Fi saved in your android settings

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
