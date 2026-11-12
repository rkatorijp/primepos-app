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
1. Open **Actions** on this repository → the latest green **Android CI** run → **Artifacts** → download **PrimePOS-debug-apk** (unzip it to get the `.apk`).
2. Allow "Install unknown apps" for the browser when Android asks, then install.
3. Open Prime POS and sign in.

## Connect printing (one tablet per store)
1. In Prime POS: **管理 Admin → プリンター Printers**, add each printer (name, IP, port 9100, paper width).
2. On the tablet that should print, press **このタブレットで印刷する / Use this tablet**.
3. Press **テスト印刷 Test print**. Each printer prints a test ticket.

The tablet must be on the same Wi‑Fi/LAN as the printers. Give the printers fixed IP addresses.
Turn off battery optimisation for Prime POS (Settings → Apps → Prime POS → Battery → Unrestricted) so printing never pauses.

## Build
GitHub Actions (`.github/workflows/android.yml`) builds a debug APK on every push to `main` and attaches it to the run as the artifact **PrimePOS-debug-apk**.

Local build: Android Studio (JDK 17, SDK 34) → open the folder → Run.

## Google Play Console checklist (before publishing)

- **Foreground service declaration** (App content → Foreground service permissions): the print service uses
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` to keep printing kitchen tickets to LAN / Bluetooth printers while the tablet screen is off.
  Play asks for a short description and a video of this (start printing in Admin → Printers, lock the screen, a ticket still prints).
- **Boot receiver**: `RECEIVE_BOOT_COMPLETED` restarts printing after the tablet restarts — mention it in the same declaration.
- **Payments**: buying Pro is hidden inside the app (the web pages check `PrimePOSApp` / the `PrimePOSApp/` user agent). Keep every
  new "buy" or "upgrade" button hidden in the app too, or Play will ask for Google Play Billing.
- **Camera**: only asked when the scan button on the POS is used, only for pages on pos.primehub.co.jp.

## Bridge safety (1.9.4)

`window.PrimePOSApp` is given to every frame of a page, and Android cannot tell which frame calls it. So:
- other websites cannot load in a frame inside the app (`shouldInterceptRequest` in `MainActivity.kt`);
- `printText`, `openDrawer` and `testPrint` only reach a paired Bluetooth printer or a private-network address
  (10.x, 172.16–31.x, 192.168.x) on a raw printing port 9100–9199. Printing from the server (print key) is not limited.
