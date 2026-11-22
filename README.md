# Pocketpad

Use your phone as a game controller for your Windows laptop, and send files between them over your own
Wi-Fi. No internet needed.

- **Controller:** phone becomes an Xbox controller (sticks, triggers, bumpers, D-pad, A/B/X/Y, L3/R3, Start/Back) with haptics.
- **Share:** send files phone → laptop and laptop → phone.

## Install

1. Open the **Releases** page of this repo and download the newest `Pocketpad.apk` and `Pocketpad.exe`.
2. **Laptop (one time):** install the ViGEmBus driver (it lets Windows see a virtual Xbox controller):
   https://github.com/nefarius/ViGEmBus/releases (download the `ViGEmBus_*_x64_x86_arm64.exe` installer). Restart the laptop if it asks.
3. Run `Pocketpad.exe`. If Windows Firewall asks, allow it on **Private and Public** networks.
4. **Phone:** install `Pocketpad.apk` (allow "install unknown apps" for your browser/files app).

## Connect (no internet needed)

Pick any one:
- Both devices on the same Wi-Fi.
- **Phone hotspot:** turn on your phone's hotspot and connect the laptop to it.
- **USB:** connect with a USB cable and turn on *USB tethering* on the phone.

Open Pocketpad on both. The phone finds the laptop by itself; if not, type the address shown in the laptop app.

## Play

Phone: **Open controller**. Games that support Xbox controllers (RDR2, Elden Ring, …) will just see it.

## How it works

- Controller: phone sends a 14-byte UDP packet to the laptop (port 39500) the moment anything changes, plus a heartbeat every 50 ms.
  The laptop app feeds it into a virtual Xbox 360 pad via ViGEmBus.
- Files: plain TCP (laptop receives on 39501, phone on 39502). Received files go to `Downloads\Pocketpad` on both.

## Repo layout

- `android/` Kotlin app (no third-party libraries)
- `windows/` C# .NET 8 WinForms app
- `.github/workflows/build.yml` builds both on every push and publishes a Release
