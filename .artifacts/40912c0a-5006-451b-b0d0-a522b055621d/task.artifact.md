# Tasks

- [x] **SDK 37 MAC Address Connection Fix**
    - [x] Restore `MY_UUID` constant and `UUID` import in `BluetoothManager`
    - [x] Refactor `connectToDevice` to accept `macAddress: String`
    - [x] Implement Standard Insecure/Secure SPP only (No reflection/SDP)
    - [x] Update `DeviceActivity` to pass MAC address string
- [x] **Restore Voice System**
    - [x] Re-enable Vosk `prepare()` and `resume()` in `DeviceActivity`
    - [x] Restore Voice UI logic and button triggers
