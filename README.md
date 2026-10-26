# LightLink

Android prototype for short text transmission using visible-light on/off keying (OOK).

## Status
**Experimental prototype; not validated as a reliable end-to-end communications link.** The app emits a framed bitstream using the phone torch and attempts to receive it through CameraX brightness sampling. Camera exposure, frame rate, timing, alignment, and ambient light can cause failures. Do not use it for sensitive information.

## Protocol draft
- Preamble: 0xD391
- Payload length: 1 byte
- Payload: UTF-8, maximum 48 bytes
- CRC: CRC-8 polynomial 0x07
- Bit order: MSB-first
- OOK bit slot: nominal 250 ms

CRC detects some transmission errors; it does not encrypt messages or authenticate the sender. Robust clock recovery, retransmission, and measured reliability tests remain future work. File transfer is not implemented yet.

## Build APK from GitHub on a phone
1. Open **Actions** and enable workflows if GitHub asks.
2. Run **Build LightLink APK**, or push a commit to `main`.
3. Open the successful workflow run and download the `LightLink-debug-apk` artifact.
4. Extract the APK and install it on an Android test phone. Android may ask you to allow installation from the browser/file manager.

## Permissions
Camera permission is used for receiving light. Sending requires a controllable torch. The app does not request internet, contacts, location, or microphone permissions.

## Optional Vercel page
The informational site is in `web/`. Import this repository into Vercel and set **Root Directory** to `web`; use the **Other** framework preset and no build command. Vercel hosts only the project page, not the Android app or its camera/flash logic.
