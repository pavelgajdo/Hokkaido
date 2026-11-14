# Hokkaido 0.6.4

Tested on a Samsung Galaxy S26 (Exynos 2600, Xclipse 960, 12 GB) and a Galaxy S24 (Exynos 2400, Xclipse 940, 8 GB), both on Android 17.

## Highlights

- **Game compatibility** (upstream)
  - A texture slot that is still empty when a shader is built now compiles as a 2D texture. Upstream this fixed Pokémon Legends Z-A's blank screen.
  - Shader translation no longer runs out of stack on Kirby Air Riders' loops.
  - More shaders translate: shifts with a uniform shift amount (SHF), and more texture gather and call forms.
  - Ocarina of Time 1.0 (without the update) works with the "No Sharpening" anti-aliasing setting.
- **Snapdragon:** Adreno's early depth test (LRZ) is turned off where it drew wrong, on Qualcomm's own driver (upstream).
- **Memory** (upstream)
  - ASTC textures with uneven mip levels stay compressed on phones and other GPUs with shared or small memory.
  - A new buffer no longer joins a large buffer it only shares a page with. Upstream this caused slowdowns and a crash on Yoshi's book pages.
  - Memory hints skip placeholder addresses.
- **Stability** (upstream)
  - Closing a game while its shaders load no longer hangs, and the next launch works.
  - Short reads from game files and failed install copies are reported instead of passing silently.
- **For contributors:** `test/<name>` branches now publish their APK as a pre-release, so a single fix can be tried before a release.

## Known issues

- Ocarina of Time can stay on a black screen after its intro movie on Snapdragon phones (issue #12, under investigation).
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
