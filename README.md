# Pocketpad

Use your phone as a game controller for your Windows laptop, and send files between them over your own
Wi-Fi. No internet needed.

## Install

Go to **Releases** (right side of this page) and download the newest:

- `Pocketpad-Setup.exe` for the laptop. Double-click it and keep pressing Next. It installs everything,
  including the controller driver. Windows may say "unknown publisher": click **More info**, then **Run anyway**.
- `Pocketpad.apk` for the phone. Open it from your Downloads. If Google Play Protect warns you, tap
  **More details**, then **Install anyway**.

## Connect (no internet needed)

Pick one:

- Both devices on the same Wi-Fi.
- Turn on your phone's **hotspot** and connect the laptop to it.
- USB cable plus **USB tethering** on the phone.

Open Pocketpad on both. The phone finds the laptop by itself. If not, type the address shown in the laptop app.

## Play

Phone: **Start playing**. Games that support Xbox controllers (RDR2, Elden Ring, ...) see it as one.
The ping stays on screen at the top. **TUNE** changes stick and touchpad speed while the game runs, **EDIT** moves,
resizes and remaps buttons, and **X** leaves.

**Touchpad:** in EDIT, tap the dashed TOUCHPAD box, drag it where you want and switch PAD ON (or use TUNE).
Swipe to move the laptop mouse, tap to click, tap with a second finger to right-click.

## Share files

Drop files on the laptop window (or click it) to send to the phone, or tap **Send to laptop** on the phone.
Received files are saved wherever you say: pick **Ask every time** or **Choose folder** under "Save received
files" in either app. Tap a line in the activity list to open the saved file (phone) or show it in Explorer (laptop).

## Under the hood

- Controller: 14-byte UDP packets (port 39500; 6-byte mouse packets use the same port) into a virtual Xbox 360 pad via ViGEmBus.
- Files: TCP (phone uploads to 39501; for laptop-to-phone the phone pulls from 39503).
- `android/` Kotlin, no libraries. `windows/` C# .NET 8. `installer/` Inno Setup script. CI builds all on every push.
- The APK is signed with the key in `android/app/pocketpad.jks` (committed on purpose so updates install over old builds).
