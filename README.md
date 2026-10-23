# PulsarNav 🏍️🗺️

**Open-Source Google Maps Turn-by-Turn (TBT) Navigation Bridge for Bajaj Pulsar & Dominar Motorcycles**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-blue.svg)](https://developer.android.com)
[![Clean-Room Interoperability](https://img.shields.io/badge/Protocol-Clean--Room%20Interoperability-green.svg)](#-legal--trademark-disclaimer)

PulsarNav is a lightweight, zero-telemetry Android companion app designed to replace proprietary navigation integrations in motorcycle companion apps. It intercepts real-time navigation directions from **Google Maps** and transmits them over Bluetooth Low Energy (BLE) to the digital instrument console of your motorcycle.

> [!IMPORTANT]
> ### ⚖️ Legal & Trademark Disclaimer
> **PulsarNav is an independent, community-driven open-source software project.**
> * It is **NOT** affiliated with, authorized, sponsored, or endorsed by **Bajaj Auto Limited**, **OTC Engineering**, **Google LLC**, or any of their affiliates.
> * *"Bajaj"*, *"Pulsar"*, *"Dominar"*, *"Rouser"*, and related brand names, logos, and vehicle models are registered trademarks of Bajaj Auto Limited.
> * *"Google"* and *"Google Maps"* are registered trademarks of Google LLC.
> * All trademark references in this project are strictly nominative fair use to describe software compatibility and interoperability under Section 52(1)(ab) of the Indian Copyright Act, Section 1201(f) of the U.S. DMCA, and EU Directive 2009/24/EC.
> * This repository contains **NO** proprietary source code, decompiled binaries, or copyright-infringing assets from OEM applications. All protocols were implemented independently from clean-room Bluetooth telematics packet analysis.

---

## 🎯 Supported Motorcycles

Compatible with all Bajaj motorcycles equipped with the **OTC Engineering Bluetooth Connectivity Cluster**:
* **Bajaj Pulsar NS200** (2024+ Digital Bluetooth Console)
* **Bajaj Pulsar N250 / F250**
* **Bajaj Pulsar NS160 / NS125**
* **Bajaj Dominar 400 / 250**
* **Bajaj Rouser NS200 / N250** (Latin America)

---

## ⚙️ How It Works

```mermaid
flowchart LR
    GM[Google Maps App] -->|Android Notifications| NL[PulsarNav: NotificationListenerService]
    NL -->|Parse Maneuver & Distance| PB[OtcPacketBuilder: 48-Byte Frame]
    PB -->|GATT Write| BM[BajajBleManager]
    BM -->|BLE OTC Protocol| CL[Bajaj Digital Cluster]
    
    style GM fill:#4285F4,color:#fff
    style NL fill:#34A853,color:#fff
    style PB fill:#FBBC05,color:#000
    style BM fill:#EA4335,color:#fff
    style CL fill:#673AB7,color:#fff
```

1. **Google Maps** posts ongoing navigation notifications (`com.google.android.apps.maps`) to the Android status bar.
2. `GoogleMapsNotificationListener` intercepts the instruction in real-time, parsing distance (`m`, `km`, `ft`, `mi`), maneuver arrows, and street names.
3. `OtcPacketBuilder` formats the instruction into the exact **48-byte OTC Engineering telematics frame**.
4. `BajajBleManager` transmits the packet to the bike's dashboard characteristic, updating the turn arrow and countdown distance on your bike's console.

---

## 📡 Reverse-Engineered Protocol Specification

### Bluetooth GATT UUIDs
* **Service UUID**: `0010676e-6972-6565-6e69-676e4543544f` (`otcEngineering`)
* **TBT Navigation Characteristic**: `0110676e-6972-6565-6e69-676e4543544f` (Write without response / Write with response)
* **Handlebar Button Characteristic**: `0a10676e-6972-6565-6e69-676e4543544f` (Notify)

### 48-Byte TBT Packet Structure

| Byte Offset | Field | Description |
| :--- | :--- | :--- |
| `0x00` | Header | Fixed packet sequence identifier (`0x01`) |
| `0x01` – `0x06` | Reserved | Padding (`0x00`) |
| `0x07` | **Maneuver Opcode** | Turn arrow icon opcode (see table below) |
| `0x08` – `0x0B` | **Distance** | 32-bit Big-Endian unsigned integer in meters |
| `0x0C` – `0x0D` | ETA | 16-bit Big-Endian unsigned integer in minutes |
| `0x0E` | Street Length | Length $N$ of sanitized street name ($\le 31$) |
| `0x0F` – `0x0F + N` | Street Name | ASCII string (accents and special chars stripped) |
| `0x0F + N` – `0x2E` | Padding | Zero padding up to byte 46 |
| `0x2F` (`Byte 47`) | **Checksum** | $\sum_{i=0}^{46} \text{byte}[i] \pmod{256}$ |

### Maneuver Opcodes

| Opcode (Dec) | Opcode (Hex) | ASCII | Maneuver Name |
| :--- | :--- | :--- | :--- |
| `73` | `0x49` | `'I'` | Turn Left |
| `74` | `0x4A` | `'J'` | Turn Right |
| `67` | `0x43` | `'C'` | Turn Slight Left |
| `68` | `0x44` | `'D'` | Turn Slight Right |
| `69` | `0x45` | `'E'` | Turn Sharp Left |
| `70` | `0x46` | `'F'` | Turn Sharp Right |
| `71` | `0x47` | `'G'` | Continue Straight |
| `72` | `0x48` | `'H'` | Destination Reached |
| `75` | `0x4B` | `'K'` | Ramp Left |
| `76` | `0x4C` | `'L'` | Ramp Right |
| `78` | `0x4E` | `'N'` | Roundabout Right |
| `79` | `0x4F` | `'O'` | U-Turn Left |
| `80` | `0x50` | `'P'` | U-Turn Right |
| `81` | `0x51` | `'Q'` | Fork Left |
| `82` | `0x52` | `'R'` | Fork Right |
| `85` | `0x55` | `'U'` | Roundabout Left |
| `86` | `0x56` | `'V'` | Merge |
| `88` | `0x58` | `'X'` | Keep Right |
| `90` | `0x5A` | `'Z'` | Keep Left |
| `66` | `0x42` | `'B'` | Wrong Way / Off-route |

---

## 🌟 Key Features

* **🏍️ Ultra-Clean Cockpit**: Minimalist, distraction-free rider interface featuring an integrated Connection & Google Maps launch station, 1-tap Home/Work quick navigation, and auto-saved parking location.
* **🗺️ Two-Wheeler Google Maps Routing (`mode=l`)**: Routes via motorcycle-friendly paths, narrow shortcuts, and accurate two-wheeler ETAs instead of standard 4-wheeler congestion.
* **🏍️ Cluster Turn-by-Turn Mirroring**: Real-time 48-byte OTC telematics packet transmission of distance countdowns, maneuver arrows, and street names to the bike console.
* **⚡ Samsung Modes & Routines Compatibility**: App shortcuts and ignition receivers (`ACTION_ACL_CONNECTED`) trigger automatic cockpit setup (screen timeout, outdoor brightness boost, DND driving mode, helmet intercom volume) upon key-on.
* **✏️ Editable Ride Logs & Telemetry**: Full in-app editing of recorded or simulated rides (route title, distance, duration, top/avg speed) with dynamic lifetime stat recalculation.
* **🎨 6 Motorcycle Accent Themes**:
  * 🍷 **Wine Red**: Pulsar Obsidian Red default
  * 🖤 **AMOLED Pure Black**: True `#000000` black pixel shutoff for handlebar OLED battery saving
  * ⬛ **Stealth Mono**: Carbon slate grey
  * ⚡ **Neon Amber**: Cocktail Neon orange
  * 🟢 **Hyper Mint**: Ather Electric green
  * 🔵 **Liquid Blue**: DTS-i high-contrast blue
* **🧤 Glove-Friendly Ergonomics**: Large $\ge 54\text{ dp}$ touch targets and $35\text{ ms}$ crisp tactile vibration pulses engineered for motorcycle riding gloves.
* **🅿️ Auto-Save Parking GPS Fix**: Captures exact GPS coordinates and reverse-geocoded address when ignition cuts off, with 1-tap walking directions back to the bike.
* **⛽ Beginner-Friendly Refuel Log**: 1-tap ₹ presets (₹200, ₹500, ₹1000, Full Tank) with automatic litre computation and accurate full-tank mileage calculation ($\text{km/l}$).
* **⛓️ Maintenance & Chain Lube Tracker**: Real-time 500 km O-ring chain cleaning countdown and 5,000 km oil service alerts.
* **🧪 Settings Diagnostics & Simulation Bench**: Cluster HUD mirror, turn simulations, Caller ID alerts, and handlebar button controls moved into dedicated test benches in Settings.
* **📜 Full MIT License & Compliance**: Clean-room interoperability documentation with zero OEM proprietary code or assets.

---

## 🛠️ How to Build the APK

### Prerequisites
* [Android Studio Hedgehog or newer](https://developer.android.com/studio) (or JDK 17+ with Android SDK tools).

### Building via Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `pulsarnav` directory.
3. Wait for Gradle sync to finish.
4. Click **Build** ➔ **Build Bundle(s) / APK(s)** ➔ **Build APK(s)**.
5. The generated APK will be in: `pulsarnav/app/build/outputs/apk/debug/app-debug.apk`.
6. Transfer the APK to your Android phone and install it.

### Building via Terminal
```bash
cd pulsarnav
./gradlew assembleDebug
```

---

## 🚀 How to Use When Your Bike Arrives

1. **Install and Launch PulsarNav**:
   * Open the app.
   * Tap **Enable Notification Access** and switch the toggle **ON** for **PulsarNav** in Android Settings.
2. **Pair with your Pulsar NS200**:
   * Turn on your bike's ignition key.
   * In PulsarNav, tap **Scan**.
   * Your bike will appear in the list (e.g., `Pulsar NS200` or `Rouser NS200`).
   * Tap **Connect**. The status dot turns green.
3. **Garage Test (Optional)**:
   * While parked, tap the **Left**, **Straight**, or **Right** simulation buttons.
   * Look at your bike's LCD/TFT display: you will see the turn arrow and distance appear immediately!
4. **Ride with Google Maps**:
   * Tap **Open Google Maps & Start Ride**.
   * Enter your destination and tap **Start**.
   * Put your phone in your pocket. As Google Maps gives navigation turns, your bike console will show turn arrows and count down the distance in real-time!
