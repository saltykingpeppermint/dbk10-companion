# DBK.10 BLE Tester

An independent Android test app for exploring the DBK.10's local Bluetooth Low Energy (BLE) interface. It does not connect to Vodafone, use a SIM/account, or call any cloud service.

## Test build behavior

1. Scan for nearby BLE devices and select the bike light.
2. Connect and inspect whether the candidate service and light characteristic are present; the app lists discovered characteristic UUIDs and their advertised properties.
3. If the light characteristic reports read support, read and display its raw bytes and ASCII interpretation.
4. If it reports write support, send ASCII `ON` or `OFF` only when you tap the corresponding button.

The UUIDs and `ON`/`OFF` payloads below are candidates observed by static analysis of the original app, not hardware-verified facts. The app does not write beeper, settings, powerbank, or firmware characteristics. It does not register a device or imitate SIM/account activation.

| Purpose | Candidate UUID |
| --- | --- |
| DBK.10 service | `75a2cfc3-30c6-4ab6-9c30-a55b0b5ba7df` |
| Light characteristic | `4980e5bc-099f-4bec-8b89-5f79237f8dfd` |
| Beeper (not written) | `90660a89-d447-4983-96f9-8f0a4577b3e6` |
| Settings sync (not written) | `de1dc0c3-32a5-4094-8d24-cdba9d51d56c` |
| Battery (not interpreted) | `d78a61b9-de37-4a43-9735-77cb73c0eed0` |
| Powerbank (not interpreted) | `4b154fee-9712-44c6-89a9-be10b63d45a5` |

## Build and install

Open the project in Android Studio with JDK 17 and Android SDK 35, then build the `app` debug variant. GitHub Actions also builds the debug APK on pushes, pull requests, or manual dispatch and uploads it as an artifact named `dbk10-ble-tester-debug-apk`. The APK is not committed to the repository.

To install a built APK, download and extract the workflow artifact, then install `app-debug.apk` from Android Studio or with `adb install -r app-debug.apk`. The first scan requests the Bluetooth permissions needed by the phone's Android version.

## What to report from a hardware test

- Whether the DBK.10 appears in the scan list and what name it shows.
- Whether connection succeeds and whether the candidate service/characteristic are found.
- The displayed characteristic properties and raw read value, if readable.
- Whether the physical light changes after tapping ON and OFF, including any displayed GATT error.

A successful BLE write callback only confirms that Android's GATT operation completed; it does not prove that the lamp changed state. The original APK is not included. No firmware update, undocumented configuration write, or Vodafone service emulation is performed.
