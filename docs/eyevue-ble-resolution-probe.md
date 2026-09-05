# EyeVue BLE resolution probe

Purpose: determine whether the vendor image-pull command can return a more useful
resolution than the measured 320x180 shutter preview, without using Wi-Fi.
This branch adds measurement hooks; it does not claim a high-resolution BLE mode.

The shipped vendor FunctionCore/SendCommandViaBle defines `appPullImage(type)` as
command 0x36 with one opaque byte. Neither a quality selector nor an image index is
proven by its unused call sites. The separate `appPullHighQualityImageStatus(type)`
command 0x97 is not sent: it may be a status acknowledgement.

Debug APK only, connected EyeVue required. `MainActivity` accepts the existing
`tasker_command` extra with these one-shot values:

- `eyevue_ble_probe_capture`: baseline 0x22/0x31 shutter.
- `eyevue_ble_probe_pull_0`: 0x36 with byte 0.
- `eyevue_ble_probe_pull_1`: 0x36 with byte 1.

Use an explicit Activity start with SINGLE_TOP/CLEAR_TOP flags so the connected
Activity receives the command. Wait for SUCCESS/FAILED/CANCELLED in `EyevueBleProbe`
before the next request. Each request waits at most 120 seconds. Concurrent photo
captures are rejected; the command extra is consumed even if the request is rejected.

All three probes suppress 0x97 assistant routing, including at GATT notification
receipt. They save only a private cache JPEG and log dimensions, bytes, duration,
and filename. They do not invoke ChatGPT, Tasker image handoff, speech recognition,
SCO setup, Wi-Fi, or camera-settings commands. Incoming voice status is now read-only
and does not automatically enable the firmware wake word.

AA14 decoding accepts the observed AC55 response header and legacy AB55, retaining
length and CRC validation. Outgoing commands remain AB55. AA15 image validation
is unchanged, so a size mismatch or packet gap is evidence to investigate, not
permission to pass corrupt data through as a successful image.

For the first test, end other voice/audio sessions and compare all three commands.
If a usable image mode is found, repeat during an existing Live Voice call to check
steady Bluetooth-audio coexistence. Keep raw frames and captured images private;
publish only protocol metadata and verified dimensions in a result note.

## S25 physical results (2026-09-05)

Build 95ae4f4 (version 23) returned an intact 320x180 JPEG, 13,340 bytes in
3,533 ms, for the baseline shutter. Both 0x36 values replied on AA14 but
returned no AA15 image within 120 seconds each. The user then took an ordinary
photo using the glasses button: reported stored-photo count increased from
20 to 21. Both pull values were repeated and again timed out in 120 seconds
with no complete image. No higher-resolution BLE mode has been demonstrated.

The same build fixed reception of the observed AC55 control replies. The
project now decodes to TK8 and selects the vendor T-series AP media profile.
A subsequent Wi-Fi sync received the glasses SSID, then failed in
EyevueWifiTransport's own permission gate before joining the AP: the transport
required Fine Location as well as Nearby Wi-Fi, while the Android 13+ UI only
requested Nearby Wi-Fi. Nearby was granted and Fine Location was not.

Version 24 reuses the UI's existing permission helper to remove that mismatch.
It also explicitly unbinds process routing when the active AP network is lost.
Wi-Fi connection modes, addresses, endpoints, and timing remain unchanged.
Full Wi-Fi download and original-image dimensions still require physical
verification; correcting the permission gate is not proof of a working transfer.

Raw phone logs, app data backups, and captured photos remain private and are
not included in this repository.
