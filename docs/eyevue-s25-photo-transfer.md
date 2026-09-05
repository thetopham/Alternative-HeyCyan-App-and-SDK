# EyeVue S25 photo-transfer correction (2026-09-05)

The 2.2.1 prototype received photos but rejected them, then reported a timeout.
USB logcat from a Samsung SM-S936U established these AA15 packet facts:

| Transfer | Announced bytes | First wire offset | Received bytes | Missing bytes |
| --- | ---: | ---: | ---: | ---: |
| AI button 1 | 10344 | 0 | 9848 | 496 |
| AI button 2 | 10012 | 10344 | 9516 | 496 |
| Ordinary photo | 9936 | 20356 | 9936 | 0 |
| AI button 3 | 10284 | 30292 | 9788 | 496 |

Wire offsets continue across successive pictures. Each first data packet begins
with JPEG SOI. The ordinary-photo final packet contains JPEG EOI followed by three
zero padding bytes. Its transfer completes in 899 ms; the AI-button transfers take
about four seconds from START to END and each has one missing 496-byte packet.
All END packets arrive before the old 12-second timeout.

The corrected assembler normalizes unsigned wire offsets against the JPEG start,
checks complete coverage and consistent overlaps, and strips only bounded zero
padding after JPEG EOI. Missing packets still fail; no missing image data is
invented. Rejections are reported immediately with metadata, instead of silently
waiting for a timeout. Photo command write failures also reach the caller.

CyanBridge previously opened its optional Bluetooth microphone during the photo
transfer. EyeVue now receives the complete image before offering that question.
This tests the suspected audio interaction without changing global BLE connection
parameters. The supplied question and opt-out flag remain respected.

The raw user capture logs stay outside the repository. Protocol regression tests
use synthetic image bytes. A successful build is not evidence of camera quality;
physical capture and decoded-image checks must be recorded separately.
