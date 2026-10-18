# Prime POS for Android

Prime POS (https://pos.primehub.co.jp) as an Android app, with **direct printing** to network receipt/kitchen printers and the cash drawer — no PC or print server needed.

Any Prime POS store can use it: install the app, sign in with your own store account or staff PIN.

## Features
- All Prime POS screens (POS, admin, kitchen screen, QR) in a full-screen app; screen stays on
- Prints kitchen tickets, bills and receipts to LAN printers (ESC/POS, raw port 9100)
  - Tested ticket format for Epson TM-T20II / TM-T88V, SII RP-F10, Star (ESC/POS mode)
  - Japanese text (Shift-JIS / Kanji mode), bold, large, centred lines, auto cut
  - Cash drawer opens on cash payment (drawer connected to the printer)
- Printing keeps running in the background (notification "Prime POS 印刷中") and restarts after reboot
- Menu CSV export downloads, photo upload

## Install
1. Open **Releases** on this repository and download the latest `PrimePOS-*.apk` on the tablet.
2. Allow "Install unknown apps" for the browser when Android asks, then install.
3. Open Prime POS and sign in.

## Connect printing (one tablet per store)
1. In Prime POS: **管理 Admin → プリンター Printers**, add each printer (name, IP, port 9100, paper width).
2. On the tablet that should print, press **このタブレットで印刷する / Use this tablet**.
3. Press **テスト印刷 Test print**. Each printer prints a test ticket.

The tablet must be on the same Wi‑Fi/LAN as the printers. Give the printers fixed IP addresses.
Turn off battery optimisation for Prime POS (Settings → Apps → Prime POS → Battery → Unrestricted) so printing never pauses.

## Build
GitHub Actions (`.github/workflows/build.yml`) builds a release APK on every push to `main` and publishes it as a release `v1.0.<run>`.

Optional signing (recommended, so updates install over the old version). Add repository secrets:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

Without them the APK is signed with a debug key.

Local build: Android Studio (JDK 17, SDK 34) → open the folder → Run.
