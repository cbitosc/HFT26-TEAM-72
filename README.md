# 🚀 CBIT Hacktoberfest '26 — Project Starter Repository

> **Official Public Starter Repository for CBIT Hacktoberfest '26**  
> *Chaitanya Bharathi Institute of Technology (CBIT), Hyderabad*

---

## 📋 Team Information

| Attribute | Details |
| :--- | :--- |
| **Team Name** | **Silent Void** |
| **Team Number** | `TEAM-72` (`HFT26-TEAM-72`) |
| **Registration ID** | `HFEST26-YLQET7WF` |
| **Problem Track** | **Disaster Management / Offline Mesh Communication** |
| **Domain** | `General Track` |
| **Team Leader** | Rohit Pranav Naidu (naidurohitpranav@gmail.com) |
| **Institution** | St. Martins Engineering College |

### 👥 Team Members

| # | Name | Email | Institution |
| :--- | :--- | :--- | :--- |
| 1 | Khyathi Anand | officialforex1@gmail.com | St. Martins Engineering College |
| 2 | Venkata Sai Kiran | vvsk1308@gmail.com | St. Martins Engineering College |
| 3 | Surya Sai | suryasai771@gmail.com | St. Martins Engineering College |
| 4 | Amrutha | amruthatejasridevulapalli@gmail.com | St. Martins Engineering College |

---

# 📡 OFFGRID — Decentralized Offline Radio Mesh Network

OFFGRID is an emergency local mesh communication system engineered for disaster relief and off-grid zones where cellular infrastructure and internet connectivity are destroyed or unavailable. 

The application deliberately operates with **zero internet permission (`INTERNET` permission is completely omitted)**. All payloads move strictly peer-to-peer via Bluetooth Classic (RFCOMM), Bluetooth Low Energy (BLE), and Android Wi‑Fi Direct TCP sockets.

---

## 🌟 Key Features

- **Multi-Device Bluetooth Mesh**: Dynamic discovery and concurrent multi-peer connections across Android devices.
- **Store-and-Forward Mesh Relay**: Devices automatically function as intermediate relay nodes, forwarding packets with hop counting and configurable TTL (Time-to-Live) to dramatically extend network reach.
- **Strict Peer Filtering**: Only scans and connects with devices running the OFFGRID app (filtering via custom service UUIDs and identifiers, eliminating interference with non-OFFGRID Bluetooth devices).
- **Two-Way Communication & Delivery Receipts**: Full bi-directional messaging with one-tap peer replies and cryptographic delivery acknowledgments.
- **Emergency Broadcast SOS**: One-touch broadcast of SOS alerts with optional localized GPS coordinates without internet access.
- **End-to-End Cryptography**: Payloads encrypted with AES-GCM and authenticated using HMAC-SHA256 for secure transmission over open radio frequencies.
- **Text-to-Speech & Voice Features**: Hands-free audio reading for incoming high-priority emergency alerts.

---

## 🏗️ Architecture & Tech Stack

- **Platform**: Native Android (Kotlin, Android SDK 35, Min SDK 26)
- **Local Transports**:
  - Bluetooth Classic (RFCOMM channel fallback & insecure/secure sockets)
  - Bluetooth Low Energy (BLE beacon advertising & scan filters)
  - Wi-Fi Direct (P2P group sockets)
- **Zero Internet Requirement**: Completely air-gapped radio communication.
- **Security**: AES-256-GCM encryption with HMAC-SHA256 packet signatures.

---

## 🚀 Build and Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/cbitosc/HFT26-TEAM-72.git
   cd HFT26-TEAM-72
   ```
2. Build the debug APK via Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. Install the APK on two or more Android devices:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. Grant the required **Nearby Devices** and location permissions when prompted.
5. Tap **SCAN PEERS** or let background BLE beacons auto-discover nearby OFFGRID nodes and start chatting!
