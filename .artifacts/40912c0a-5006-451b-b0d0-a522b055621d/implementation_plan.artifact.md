# SDK 37 MAC Address Connection Fix

This plan implements a strict MAC-address-only connection pattern to bypass SDK 37 object caching issues. We will sever all ties to cached UI `BluetoothDevice` objects and use fresh instances created directly from the MAC string.

## User Review Required

> [!IMPORTANT]
> - **MAC Address Only**: The `connectToDevice` method will now strictly require a MAC address string.
> - **Strategy Limit**: Only two standard SPP strategies (Insecure then Secure) will be attempted. Reflection and brute-force channel mapping are removed as requested.

## Proposed Changes

### Bluetooth Manager Refactor
#### [MODIFY] [BluetoothManager.kt](file:///C:/Users/Mhiko/AndroidStudioProjects/Comucane/app/src/main/java/com/example/comucane/BluetoothManager.kt)
- **Re-introduce `MY_UUID`**: Add back the standard SPP UUID constant.
- **Refactor `connectToDevice`**:
    - Change signature to `connectToDevice(macAddress: String)`.
    - Instantiate `freshDevice` using `getRemoteDevice(macAddress)`.
    - Implement **Strategy 1**: Standard Insecure SPP (`createInsecureRfcommSocketToServiceRecord`).
    - Implement **Strategy 2**: Standard Secure SPP (`createRfcommSocketToServiceRecord`).
    - Remove all reflection logic and SDP UUID fetching.

### Activity Integration
#### [MODIFY] [DeviceActivity.kt](file:///C:/Users/Mhiko/AndroidStudioProjects/Comucane/app/src/main/java/com/example/comucane/DeviceActivity.kt)
- Update the connection call to pass `targetDevice.address` instead of the device object.

## Verification Plan

### Automated Tests
- Run `analyze_file` to ensure `MY_UUID` is correctly referenced and method signatures match.

### Manual Verification
1.  **Run the app** and attempt connection from the Device Screen.
2.  **Monitor Logcat** for:
    - `Attempting Standard Insecure SPP...`
    - `Attempting Standard Secure SPP...`
3.  **Confirm Success**: Hardware module LED should stabilize and app should report "Connected".
