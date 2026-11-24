# Fusion preview.2 integration notes

This build keeps the Hokkaido and Banana Native engines separate, with one launcher,
per-game engine selection, and shared save folders. It does not merge their renderers
or establish a device performance result.

## Settings and engine behavior

- The Banana in-game drawer opens the original Hokkaido settings, per-game settings,
  and controls. Hokkaido-specific graphics settings apply to Hokkaido.
- Home settings and game properties also open Banana's own settings. The embedded
  settings activity returns to the caller and cannot start a second game through the
  standalone launcher's Play button. Pack preparation and save sharing use the host bridge.
- Banana uses its own GPU driver, window/scene resolution, recommendations, and advanced
  settings. Starting a game no longer copies Hokkaido's default driver or 1080p resolution
  into Banana, or installs Galaxy-specific tuning on unrelated phones.
- An upgrade removes an old 1080p window override only when the earlier Fusion marker,
  current value, and absence of an explicit scene/preset choice identify it. Other choices
  are preserved. **Use Banana Native recommendations** is an explicit per-game action
  to restore recommendations and retire matching legacy Fusion compatibility overrides;
  saves and controller mappings are retained.
- Auto prefers Banana for Ocarina of Time when its profile is available. Its upstream
  `bring-up` status is still shown as experimental. Selecting Hokkaido explicitly wins.
- Preparation checks accept the same on-demand and compatible older shader libraries
  as the embedded Banana version. Preparing game code does not require building every
  optional shader before the game can be reported ready.

## Language behavior

On Android 13 and later, both engines use Android's application locale. An empty locale
selection follows the device's language and subsequent changes. An existing explicit
Hokkaido language selection is migrated once when Android has no app selection. The
language picker and reset action read/write this shared setting. Cached settings labels
and Banana display arrays are resolved again in the current language.

Simplified and Traditional Chinese resources cover the host, embedded launcher, fusion
controls, preparation messages, common advanced options, and bundled post-processing
metadata. Display translations never change game IDs, environment keys/values, shader
uniform IDs, or saved preset names. Only files matching the bundled post-processing
fingerprints receive bundled metadata translations; imported or edited files retain
their own metadata.

`tools/android/check_fusion_locales.py` gates Chinese resource coverage, format arguments,
container shapes, and duplicate definitions after source synchronization. Its JSON report
also lists the incomplete upstream coverage in other languages. Resource coverage does
not prove translation quality or complete localization of every runtime-generated string.
Developer reference descriptions from the upstream environment registry, diagnostic
logs, and user/imported metadata can retain their source language. This release therefore
does not claim complete translation of every language Android can select.

## Native packaging and verification

Banana's `libhook_impl.so` and `libmain_hook.so` must remain byte-identical to its pinned
release. Hokkaido's libadrenotools now emits `libhokkaido_hook_impl.so` and
`libhokkaido_main_hook.so` and loads those names. This prevents Android packaging from
replacing Banana's driver compatibility code with the host engine's different hooks.

The APK gate checks the package, minimum SDK, ARM64 ELF files, signature, all 33 Banana
native files and 11 assets, and the isolated hook SONAME/DT_NEEDED/load names. It must fail
if either original hook is overwritten. A signed candidate retained after a failed gate
is not a verified release.

Local checks cover clean patch application, locale changes without changing parameter
values, conservative preference migration, shader readiness rules, and verification
failures. The Android workflow compiles Java/Kotlin/native code and checks the final APK.
Device installation, graphics correctness, stability, thermal behavior, and frame rate
still require testing on the target phone and game version.
