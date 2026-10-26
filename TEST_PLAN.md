# LightLink test plan

## Protocol checks
- Round-trip tests for ASCII and Arabic UTF-8.
- Reject empty messages and payloads over 48 UTF-8 bytes.
- Reject invalid preamble, invalid length, and CRC mismatch.

## Device tests required before claiming reliability
1. Install on two Android phones; sender must have a controllable torch.
2. Test at 20 cm, 50 cm, then 1 m, starting in a dim room.
3. Repeat with Arabic and Latin messages.
4. Repeat at several ambient-light levels.
5. Verify exact payload and CRC before reporting success.
6. Repeat at least 20 times per distance and record success rate.
7. Test app backgrounding, permission denial, and torch cleanup.

## Known limitations
- Receiver uses approximate timing from a detected light edge; it is not robust clock recovery.
- Camera auto-exposure, FPS, alignment, and ambient light can break reception.
- No acknowledgement/retry protocol or file transfer yet.
