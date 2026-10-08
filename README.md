# ComuCane

**ComuCane** is a comprehensive, Android-powered assistive navigation and obstacle detection system designed for the visually impaired. It pairs via Bluetooth with a hardware "Smart Cane" to provide real-time spatial awareness, obstacle warnings, and hands-free voice control.

---

## Key Features

* **Offline Voice AI:** Embedded on-device voice recognition powered by Vosk. Completely hands-free control using the wake word **"Hey Cane"**.
* **Bilingual Support:** Fully supports both **English** and **Tagalog (Filipino)** for voice commands and text-to-speech (TTS) feedback.
* **Bluetooth LE Hardware Integration:** Connects seamlessly to AT-09, BT05, or custom BLE modules on the physical cane to receive real-time distance sensor data.
* **Live GPS & Navigation Assist:** Uses high-accuracy GPS and OpenStreetMap (Google Maps tiles) to track locations and visualize obstacle encounters.
* **Haptic & Audio Feedback:** Smart hysteresis logic provides dynamic vibration pulses and voice warnings based on obstacle proximity (Safe, Caution, and Stop zones).
* **Emergency SOS:** Instant access to an emergency SOS feature that can alert a pre-configured contact with the user's current GPS location.
* **Obstacle Logging:** Keeps a persistent record of obstacles encountered during navigation for future reference and route planning.

---

## Hardware Used

The ComuCane system consists of this Android application and a custom-built physical "Smart Cane". The physical cane utilizes the following hardware components:

* **Microcontroller:** Arduino (e.g., Uno, Nano) or ESP32 to handle sensor data processing.
* **Distance Sensor:** HC-SR04 Ultrasonic Sensor to measure the distance to obstacles in centimeters.
* **Bluetooth Module:** HM-10, AT-09, or MLT-BT05 (BLE module) for transmitting data wirelessly to the Android app.
* **Power Source:** Portable battery pack or rechargeable lithium-ion battery.

**Data Communication:**
The microcontroller reads the distance from the ultrasonic sensor and transmits it over Bluetooth Serial to the app. The app expects the data in the format `DIST:100\n` or as raw numbers like `100\n` (representing the distance in cm).

---

## How to Use (Voice Commands)

The app is designed to be used without looking at the screen. To trigger a command, always start by saying **"Hey Cane"**.

### Connection
1. Turn on your hardware cane.
2. Say: **"Hey Cane, scan"** — The app will search for the cane.
3. Say: **"Hey Cane, connect"** — The app will link to the discovered cane.

### Navigation & Status
* **"Hey Cane, start navigation"**: Opens the live navigation and obstacle detection map.
* **"Hey Cane, status"**: Reads out your current environment status (e.g., "Path ahead is safe" or "Caution, obstacle 60cm ahead").
* **"Hey Cane, GPS"**: Reads out your exact latitude and longitude.
* **"Hey Cane, stop"**: Exits the current mode or stops navigation.

### Settings & Safety
* **"Hey Cane, switch to Tagalog"**: Changes the AI and feedback language to Filipino.
* **"Hey Cane, switch to English"**: Reverts to English.
* **"Hey Cane, emergency"**: Instantly opens the SOS screen to alert your emergency contact.
* **"Hey Cane, voice off / voice on"**: Toggles voice feedback on or off.

---

## Permissions Required

Upon first launch, ComuCane will request the following permissions:
* **Location (Precise):** Required for mapping, GPS tracking, and Bluetooth LE scanning (on older Android versions).
* **Bluetooth (Scan & Connect):** Required to find and pair with the smart cane.
* **Microphone (Record Audio):** Required for the offline wake-word and voice command AI.

---

## License


Copyright (c) 2026 Comucane (Mhiko).

This repository and its contents are the intellectual property of eMoodtune (Mhiko). This code is provided publicly for portfolio, demonstration, and educational evaluation purposes only.

You may NOT:

Copy, reproduce, or distribute this code.
Publish, display, or perform this code.
Modify or create derivative works from this code.
Sell, offer for sale, or monetize any part of this software.
Deploy or operate this software for public or commercial use.
