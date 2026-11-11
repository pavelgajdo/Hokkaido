# Hokkaido 0.6.3

Tested on a Samsung Galaxy S26 (Exynos 2600, Xclipse 960, 12 GB) and a Galaxy S24 (Exynos 2400, Xclipse 940, 8 GB), both on Android 17.

## Highlights

- **Less work per frame for the GPU threads** (upstream):
  - A run of uploads goes to the GPU as one command with one pair of barriers, and later transfers are ordered after earlier copies to the same memory.
  - Each compute dispatch records its barrier in its own command, instead of a separate one.
  - Empty submissions to the GPU are skipped.
- **The Legend of Zelda: Ocarina of Time**
  - Its mesh-shader geometry is drawn through compute on every device, not only on Xclipse.
  - The guard house freeze is patched in (upstream).
  - The ocarina's crackle is fixed (upstream).
- **Orbitals** boots into gameplay.
- **Memory and stability** (upstream)
  - GPU memory the game destroys is released, and reused memory is refreshed.
  - A buffer that doesn't fit the memory budget is allocated anyway instead of closing the game.
  - Threads waiting for the GPU are released when a game stops.
- **Rendering fixes** (upstream): texture gathers with a register handle, clears of colour targets with more layers than the framebuffer, and 2D-engine copies between 3D surfaces.
- **Game files** (upstream): damaged pieces of NUP dumps get a stand-in, game paths are read as UTF-8, and file parsers are hardened against malformed dumps. Shader compiling does less work.
- **Version number:** the app now reports the same version as the release (`0.6.3`), so updaters such as EmuHub no longer see an update that isn't there.

## Known issues

- Fast Fusion launches but does not get further.
- Ocarina of Time sometimes starts with graphical glitches; restarting the game helps. On a 12 GB phone it can still be closed for low memory, most often on the first start while shaders compile. Use **0.5x**.
- Pokopia's sky is black.
- Microphone input is not supported on Android.
- The phone heats up during long sessions and slows down until it cools.

## For testers

- Install the APK, add your games folder, and set up keys and firmware as usual. None are included.
- Recommended: Handheld mode, NCE CPU backend, 0.5x resolution, and a custom RADV driver on Xclipse GPUs.
- Diagnostic switches can be set in `Android/data/dev.lichii.android/files/env.txt` (see the README).
- Report problems at https://github.com/pavelgajdo/Hokkaido/issues with your phone, driver and `eden_log.txt`.
