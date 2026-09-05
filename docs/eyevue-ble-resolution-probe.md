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
