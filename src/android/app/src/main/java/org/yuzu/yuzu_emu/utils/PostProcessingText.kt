// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import org.yuzu.yuzu_emu.R
import java.io.File
import java.security.MessageDigest

/** Display text for the bundled effects only. File names, uniform keys and preset names stay native IDs. */
object PostProcessingText {
    data class PresetText(val name: String, val description: String)

    private data class PresetSource(val file: String, val hash: String, val description: String)
    private data class Identity(val modified: Long, val size: Long, val bundled: Boolean)
    private val identities = mutableMapOf<String, Identity>()

    fun effect(context: Context, effect: NativePostProcessing.Effect): NativePostProcessing.Effect {
        val expected = shaders[effect.file] ?: return effect
        if (!isBundled(NativePostProcessing.getShaderDirectory(), effect.file, expected)) return effect
        return effect.copy(
            label = text(context, effect.label),
            description = text(context, effect.description),
            uniforms = effect.uniforms.map { uniform ->
                uniform.copy(
                    label = text(context, uniform.label),
                    tooltip = text(context, uniform.tooltip),
                    category = text(context, uniform.category),
                    items = uniform.items.map { text(context, it) }
                )
            }
        )
    }

    fun preset(
        context: Context,
        preset: NativePostProcessing.Preset,
        catalog: List<NativePostProcessing.Preset>
    ): PresetText {
        val original = PresetText(preset.name, preset.description)
        val source = presets[preset.name] ?: return original
        if (!preset.bundled || preset.description != source.description || catalog.count { it.name == preset.name } != 1) {
            return original
        }
        if (!isBundled(NativePostProcessing.getPresetDirectory(), source.file, source.hash)) return original
        return PresetText(text(context, preset.name), text(context, preset.description))
    }

    private fun text(context: Context, original: String): String =
        strings[original]?.let { context.getString(it) } ?: original

    /** Cache identity, never translated text. Edited or external files keep their own metadata. */
    private fun isBundled(directory: String, name: String, expected: String): Boolean {
        if (directory.isEmpty()) return false
        val file = File(directory, name)
        if (!file.isFile || file.length() > 1_048_576) return false
        val path = file.absolutePath
        val modified = file.lastModified()
        val size = file.length()
        synchronized(identities) {
            identities[path]?.let {
                if (it.modified == modified && it.size == size) return it.bundled
            }
        }
        val actual = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            // CMake bundles the files line by line; blank lines and line endings carry no metadata.
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isNotBlank()) {
                    digest.update(line.toByteArray(Charsets.UTF_8))
                    digest.update(10.toByte())
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return false
        val bundled = actual == expected
        synchronized(identities) { identities[path] = Identity(modified, size, bundled) }
        return bundled
    }

    // Fingerprints of dist/post_shaders and dist/post_presets, with the same normalization as above.
    private val shaders = mapOf(
        "AMDBoost.fx" to "b1ae24986eebebb27a321479e6eb407b10be8ccc3124d66f66452e1dcd4cf983",
        "Bloom.fx" to "d5ee72b24c9c94490c23a7f3695221e4aeae62d36fe6b7f93676a0fb22c14973",
        "Blur.fx" to "c635b238305ff029c2953c9ae6604034863cda812d7fb2c01375e2d01b4b6297",
        "CRT.fx" to "8809a0a1cfb8ad763130e257836bac00620cc300cd5c1c5135384401fc330507",
        "Cartoon.fx" to "57781b9f45068d33c88a41ce77058eb4339dcef4361d0e5913ba14656e8baf95",
        "CartoonSoft.fx" to "fed4a5f7596a52dd2a678775cff6707bc55f71ef6ca0a876ce869d88739d0aef",
        "CelShading.fx" to "da5b7044d691c0165436b1b8edf1dff09083134bcaf1d8fb07e18070a6d3c052",
        "ChromaticAberration.fx" to "5c212253eca9cf72c20a08a48a08bc7cd3ba6ecbb07aa7bcb76e6c24b57ada86",
        "ColorGrade.fx" to "768eeb7a4c914b9c2bb1c9e3daf9818c943bdaef1ec7f9c91d614d60a190df07",
        "Deband.fx" to "2ea5af2ea386aa0eeed66544d5aea544e6c8501d9a9e3603996425cd76c3597b",
        "Denoise.fx" to "45dd74369ab8813d4878dd5cde6688a8c2fb17d503644fb355c3a408122323e8",
        "FilmGrain.fx" to "cc85f5e41f86a07c50da4073be4d147e341c1c4fa5c8646f6dd7e6924b1a9bf8",
        "FilmicCurve.fx" to "bd38455b96e3136298da95dbb78c8d0e8dc1796e8b4bf9f177f0fe68c9ca86ae",
        "LensDistortion.fx" to "767dfcd74fb4063b53a53abab2799e91b7dd3423a86f49249568053d7461d09c",
        "Levels.fx" to "8c5feb392fe6423cc3960d4accc8f5f58aa3b85741b457235aea5380b416b5fc",
        "MotionBlur.fx" to "1887ef9fbabded1e0dda033bfe7b124ec75eb178092fdfb43563d5dc7985df3b",
        "NaturalColors.fx" to "74e03a4e5cc814f765fd56a087c8c11295195ecf44ec118ab93071c9fc9d67a6",
        "Reflections.fx" to "bc469dad3c0cdd9a0331991c62c9e42c8e6c9c735a2569c3e89e757c3043de25",
        "Scanlines.fx" to "a4ea1d0d3658f1d2b5fc0e74dd6a26f282d1626fa305e5223a3936e6c5f49d97",
        "Sharpen.fx" to "4b820077a3064e27287f06e4cc2d95ba89ccde27fb3cbcd18263105d32902f49",
        "SplitToning.fx" to "b5e6553788ec0b3e5a5f6bd4127ef0b1e76662a2f08d2c3c6775389b37199a06",
        "Vignette.fx" to "11823b179dbc8111607acbc851cd96e1da108ea7fc7f786ea0c751bcd847f24c",
        "WhiteBalance.fx" to "f2bb0ef42a9d2247f84a78347446245221e8f37e8f05f16d2e47025c8df78913"
    )

    private val presets = mapOf(
        "Anime" to PresetSource("Anime.fxp", "a91a9db15174a3f66b87adcc1578153b95d5bd17cfd23216d8e848930797e942", "Cel shaded look: the picture is cleaned first, then the lighting is collapsed into flat bands with inked edges, finished with a small glow."),
        "Cinematic" to PresetSource("Cinematic.fxp", "9f69d2b12e4054f7af985ddbf381ac7c7fe0bb8091883286f5be30fc2374af2e", "Film look: soft glow on the highlights, a filmic contrast curve, cool shadows against warm highlights, fine grain and a gentle vignette."),
        "Clean" to PresetSource("Clean.fxp", "836b8301394db72f95670375201bec93e75a09c50ccc2353fc1001dfd58a0f14", "Cleans up a low internal resolution: removes dithering and compression noise, smooths banded skies and brings edge detail back. The safe starting point if you are not sure what you want."),
        "Dreamy" to PresetSource("Dreamy.fxp", "8227a43db114790ec149f7c637620d9b78ec618968d5ae964f832057b5567fce", "Soft focus: the centre of the screen stays sharp while everything around it blurs, with a wide glow over the highlights. Good for slower games and cutscenes."),
        "Retro" to PresetSource("Retro.fxp", "57a43d36a7a45375b29cc1ac7dfa0b7d843da61467aa1d5ff9a8e9c55e2fe1b7", "Old television look: colour fringing at the edges of the screen, curved scanlines with a phosphor mask, and darkened corners. The scanline roll is switched off so it does not crawl."),
        "Vivid" to PresetSource("Vivid.fxp", "f3711f67fe7382cb6c92c947c2c88ec9d1279e1e4c1a3cae6ae836ec6d911303", "Punchier colour without stylising the image: warmer and more saturated, blacks and whites pushed to the ends of the range, and a light sharpen.")
    )

    private val strings = mapOf(
        "Scale Factor" to R.string.fusion_fx_amdboost_scalefactor_label,
        "Upscaling factor (1.0 = native, 1.5 = 1.5x, 2.0 = 2x). Higher values require more performance." to R.string.fusion_fx_amdboost_scalefactor_tooltip,
        "Sharpness" to R.string.fusion_fx_amdboost_sharpness_label,
        "RCAS sharpening strength. Higher values increase edge contrast." to R.string.fusion_fx_amdboost_sharpness_tooltip,
        "Enable Denoising" to R.string.fusion_fx_amdboost_enabledenoise_label,
        "Apply a light denoising pass before upscaling to reduce artifacts." to R.string.fusion_fx_amdboost_enabledenoise_tooltip,
        "Denoise Strength" to R.string.fusion_fx_amdboost_denoisestrength_label,
        "Strength of the denoising filter. Only applies when denoising is enabled." to R.string.fusion_fx_amdboost_denoisestrength_tooltip,
        "Enable Upscaling" to R.string.fusion_fx_amdboost_enableupscaling_label,
        "Enable FSR 1 EASU upscaling. Disable for sharpening only." to R.string.fusion_fx_amdboost_enableupscaling_tooltip,
        "AMD Boost" to R.string.fusion_fx_amdboost_amdboost_label,
        "AMD FidelityFX Boost: Combines FSR 1 upscaling (EASU), RCAS sharpening, and optional denoising for enhanced image quality." to R.string.fusion_fx_amdboost_amdboost_tooltip,
        "Radius" to R.string.fusion_fx_bloom_radius_label,
        "How far the glow spreads from bright areas." to R.string.fusion_fx_bloom_radius_tooltip,
        "Amount" to R.string.fusion_fx_bloom_amount_label,
        "Strength of the glow added on top of the image." to R.string.fusion_fx_bloom_amount_tooltip,
        "Bloom" to R.string.fusion_fx_bloom_bloom_label,
        "Bleeds light out of the brightest areas into a soft halo, the way a camera does when pointed at something too bright." to R.string.fusion_fx_bloom_bloom_tooltip,
        "How far the blur reaches, in pixels." to R.string.fusion_fx_blur_radius_tooltip,
        "Strength" to R.string.fusion_fx_blur_strength_label,
        "Blend between the original image and the blurred one." to R.string.fusion_fx_blur_strength_tooltip,
        "Sharp Centre" to R.string.fusion_fx_blur_focussize_label,
        "Size of the region left in focus at the centre of the screen. At zero the whole image is blurred evenly." to R.string.fusion_fx_blur_focussize_tooltip,
        "Focus Falloff" to R.string.fusion_fx_blur_focussoftness_label,
        "How gradually the sharp centre gives way to the blur." to R.string.fusion_fx_blur_focussoftness_tooltip,
        "Blur" to R.string.fusion_fx_blur_blur_label,
        "Gaussian blur with an optional sharp centre, for softening the picture or faking depth of field." to R.string.fusion_fx_blur_blur_tooltip,
        "Line Density" to R.string.fusion_fx_crt_density_label,
        "Roll Speed" to R.string.fusion_fx_crt_rollspeed_label,
        "Speed of the rolling bar. Zero disables it." to R.string.fusion_fx_crt_rollspeed_tooltip,
        "Colour Bleed" to R.string.fusion_fx_crt_bleed_label,
        "Horizontal separation of the red and green channels." to R.string.fusion_fx_crt_bleed_tooltip,
        "CRT" to R.string.fusion_fx_crt_crt_label,
        "Curved scanlines and the phosphor mask of a CRT television, for games that were drawn with that kind of screen in mind." to R.string.fusion_fx_crt_crt_tooltip,
        "Edge Strength" to R.string.fusion_fx_cartoon_edgestrength_label,
        "Darkness of the ink outline drawn around detected edges." to R.string.fusion_fx_cartoon_edgestrength_tooltip,
        "Colour Levels" to R.string.fusion_fx_cartoon_levels_label,
        "How many bands the colours are quantised into." to R.string.fusion_fx_cartoon_levels_tooltip,
        "Cartoon" to R.string.fusion_fx_cartoon_cartoon_label,
        "Ink outlines and flat colour bands. Faithful port of the PPSSPP shader; the outline gets heavy in dark scenes, so try Cartoon Soft or Cel Shading if the blacks smear." to R.string.fusion_fx_cartoon_cartoon_tooltip,
        "Shadow Guard" to R.string.fusion_fx_cartoonsoft_shadowguard_label,
        "Holds the outline back in dark areas. Raise it if shadows turn into black blobs." to R.string.fusion_fx_cartoonsoft_shadowguard_tooltip,
        "Banding" to R.string.fusion_fx_cartoonsoft_smoothing_label,
        "How far the picture is pushed towards flat bands." to R.string.fusion_fx_cartoonsoft_smoothing_tooltip,
        "Saturation" to R.string.fusion_fx_cartoonsoft_saturation_label,
        "Cartoon Soft" to R.string.fusion_fx_cartoonsoft_cartoonsoft_label,
        "Ink outlines and flat colour bands, with the outline held back in the shadows so dark scenes do not turn into black blobs." to R.string.fusion_fx_cartoonsoft_cartoonsoft_tooltip,
        "Shading Bands" to R.string.fusion_fx_celshading_bands_label,
        "How many flat steps the lighting is collapsed into. Three or four gives the classic look." to R.string.fusion_fx_celshading_bands_tooltip,
        "Band Contrast" to R.string.fusion_fx_celshading_bandcontrast_label,
        "Spreads the bands towards pure black and white. Lower it if the shadows crush." to R.string.fusion_fx_celshading_bandcontrast_tooltip,
        "Band Edge" to R.string.fusion_fx_celshading_bandedge_label,
        "Width of the transition between bands. Low values give hard cel steps." to R.string.fusion_fx_celshading_bandedge_tooltip,
        "Texture Detail" to R.string.fusion_fx_celshading_detail_label,
        "How much surface texture survives the flattening. At zero the bands are completely flat." to R.string.fusion_fx_celshading_detail_tooltip,
        "Outline Strength" to R.string.fusion_fx_celshading_outlinestrength_label,
        "Opacity of the ink line drawn along detected edges." to R.string.fusion_fx_celshading_outlinestrength_tooltip,
        "Outline Threshold" to R.string.fusion_fx_celshading_outlinethreshold_label,
        "How strong an edge has to be before it is inked. Raise it to keep ink off textures and specular highlights." to R.string.fusion_fx_celshading_outlinethreshold_tooltip,
        "Colour intensity of the flat regions." to R.string.fusion_fx_celshading_saturation_tooltip,
        "Cel Shading" to R.string.fusion_fx_celshading_celshading_label,
        "Collapses the lighting into a few hard steps and inks the edges, keeping texture detail and hue intact." to R.string.fusion_fx_celshading_celshading_tooltip,
        "Channel separation in pixels, measured at the edge of the screen." to R.string.fusion_fx_chromaticaberration_strength_tooltip,
        "Falloff" to R.string.fusion_fx_chromaticaberration_falloff_label,
        "How fast the separation grows away from the centre. Higher keeps the middle clean." to R.string.fusion_fx_chromaticaberration_falloff_tooltip,
        "Chromatic Aberration" to R.string.fusion_fx_chromaticaberration_chromaticaberration_label,
        "Splits the colour channels apart towards the edges of the screen, the way a cheap lens fails to focus every colour on the same spot." to R.string.fusion_fx_chromaticaberration_chromaticaberration_tooltip,
        "Brightness" to R.string.fusion_fx_colorgrade_brightness_label,
        "Contrast" to R.string.fusion_fx_colorgrade_contrast_label,
        "Gamma" to R.string.fusion_fx_colorgrade_gamma_label,
        "Colour Grade" to R.string.fusion_fx_colorgrade_colorgrade_label,
        "The four basic colour controls in one pass: saturation, brightness, contrast and gamma. Reach for this before anything more specialised." to R.string.fusion_fx_colorgrade_colorgrade_tooltip,
        "Threshold" to R.string.fusion_fx_deband_threshold_label,
        "How flat a neighbourhood must be before it gets smoothed. Raise it to catch wider bands, lower it to keep more detail." to R.string.fusion_fx_deband_threshold_tooltip,
        "How far the sampling reaches, in pixels. Wider gradients need a larger radius." to R.string.fusion_fx_deband_radius_tooltip,
        "Dither" to R.string.fusion_fx_deband_grain_label,
        "Noise added to break up whatever banding survives the smoothing." to R.string.fusion_fx_deband_grain_tooltip,
        "Deband" to R.string.fusion_fx_deband_deband_label,
        "Smooths the visible steps in gradients such as skies, then dithers whatever survives. Worth adding whenever large flat areas show rings." to R.string.fusion_fx_deband_deband_tooltip,
        "Shadow Bias" to R.string.fusion_fx_denoise_curve_label,
        "Denoise" to R.string.fusion_fx_denoise_denoise_label,
        "Edge preserving blur that clears dithering and compression noise while leaving outlines sharp. Ported from Anime4K." to R.string.fusion_fx_denoise_denoise_tooltip,
        "Intensity" to R.string.fusion_fx_filmgrain_intensity_label,
        "Size" to R.string.fusion_fx_filmgrain_size_label,
        "Grain cell size in pixels." to R.string.fusion_fx_filmgrain_size_tooltip,
        "Colour" to R.string.fusion_fx_filmgrain_colored_label,
        "Zero gives monochrome grain, one gives independent noise per channel." to R.string.fusion_fx_filmgrain_colored_tooltip,
        "Film Grain" to R.string.fusion_fx_filmgrain_filmgrain_label,
        "Adds photographic grain, strongest in the midtones and fading out in blacks and whites." to R.string.fusion_fx_filmgrain_filmgrain_tooltip,
        "Exposure" to R.string.fusion_fx_filmiccurve_exposure_label,
        "Toe" to R.string.fusion_fx_filmiccurve_toe_label,
        "Above 1.0 deepens the shadows, below 1.0 lifts them." to R.string.fusion_fx_filmiccurve_toe_tooltip,
        "Shoulder" to R.string.fusion_fx_filmiccurve_shoulder_label,
        "Above 1.0 opens up the highlights, below 1.0 compresses them." to R.string.fusion_fx_filmiccurve_shoulder_tooltip,
        "Filmic Curve" to R.string.fusion_fx_filmiccurve_filmiccurve_label,
        "Filmic contrast curve. Deepens the shadows and opens the highlights without clipping either end." to R.string.fusion_fx_filmiccurve_filmiccurve_tooltip,
        "Distortion" to R.string.fusion_fx_lensdistortion_distortion_label,
        "Positive bulges the picture outwards, negative pinches it inwards." to R.string.fusion_fx_lensdistortion_distortion_tooltip,
        "Zoom" to R.string.fusion_fx_lensdistortion_zoom_label,
        "Scales the picture to hide the edges the warp pulls in." to R.string.fusion_fx_lensdistortion_zoom_tooltip,
        "Lens Distortion" to R.string.fusion_fx_lensdistortion_lensdistortion_label,
        "Barrel or pincushion warp, like looking through a wide angle lens." to R.string.fusion_fx_lensdistortion_lensdistortion_tooltip,
        "Input Black" to R.string.fusion_fx_levels_inputblack_label,
        "Input level mapped to black. Raise it to deepen washed out shadows." to R.string.fusion_fx_levels_inputblack_tooltip,
        "Input White" to R.string.fusion_fx_levels_inputwhite_label,
        "Output Black" to R.string.fusion_fx_levels_outputblack_label,
        "Lifts crushed shadows so detail stops collapsing into one flat black." to R.string.fusion_fx_levels_outputblack_tooltip,
        "Output White" to R.string.fusion_fx_levels_outputwhite_label,
        "Levels" to R.string.fusion_fx_levels_levels_label,
        "Black point, white point, gamma and output range. Use it to fix crushed or washed out shadows." to R.string.fusion_fx_levels_levels_tooltip,
        "Length" to R.string.fusion_fx_motionblur_length_label,
        "How far the streaks reach, as a percentage of screen height." to R.string.fusion_fx_motionblur_length_tooltip,
        "Streaks running out from the centre of the screen, as if rushing forward. The centre stays sharp." to R.string.fusion_fx_motionblur_zoom_tooltip,
        "Pan" to R.string.fusion_fx_motionblur_pan_label,
        "Streaks running in one fixed direction, as if the camera were sweeping across." to R.string.fusion_fx_motionblur_pan_tooltip,
        "Pan Angle" to R.string.fusion_fx_motionblur_angle_label,
        "Direction of the sweep, in degrees." to R.string.fusion_fx_motionblur_angle_tooltip,
        "Spin" to R.string.fusion_fx_motionblur_spin_label,
        "Streaks curling around the centre, as if the camera were rolling. Negative turns the other way." to R.string.fusion_fx_motionblur_spin_tooltip,
        "Motion Blur" to R.string.fusion_fx_motionblur_motionblur_label,
        "Camera style motion blur: streaks the picture outwards, sideways or around, without touching still detail at the centre." to R.string.fusion_fx_motionblur_motionblur_tooltip,
        "Luma Curve" to R.string.fusion_fx_naturalcolors_luma_label,
        "Gamma applied to the luminance channel in YIQ space." to R.string.fusion_fx_naturalcolors_luma_tooltip,
        "Chroma Gain" to R.string.fusion_fx_naturalcolors_chroma_label,
        "Boost applied to the two colour difference channels." to R.string.fusion_fx_naturalcolors_chroma_tooltip,
        "Natural Colours" to R.string.fusion_fx_naturalcolors_naturalcolors_label,
        "Warms the picture and lifts saturation for a less washed out look. Ported from the PPSSPP shader." to R.string.fusion_fx_naturalcolors_naturalcolors_tooltip,
        "Reflection Line" to R.string.fusion_fx_reflections_horizon_label,
        "Height on screen where the reflective floor begins. Everything above it is left untouched." to R.string.fusion_fx_reflections_horizon_tooltip,
        "How strongly the reflection shows through the floor." to R.string.fusion_fx_reflections_amount_tooltip,
        "How quickly the reflection fades as the floor comes towards the viewer. At zero it stays even." to R.string.fusion_fx_reflections_falloff_tooltip,
        "Perspective" to R.string.fusion_fx_reflections_perspective_label,
        "Stretches or squashes the mirrored image. One is a true mirror, higher values pull it towards the line." to R.string.fusion_fx_reflections_perspective_tooltip,
        "Ripple" to R.string.fusion_fx_reflections_ripple_label,
        "Amplitude of the waves disturbing the reflection. At zero the mirror is perfectly still." to R.string.fusion_fx_reflections_ripple_tooltip,
        "Ripple Speed" to R.string.fusion_fx_reflections_ripplespeed_label,
        "How fast the waves travel." to R.string.fusion_fx_reflections_ripplespeed_tooltip,
        "Reflections" to R.string.fusion_fx_reflections_reflections_label,
        "Mirrors the picture into a reflective floor below a line you choose, with optional water ripples." to R.string.fusion_fx_reflections_reflections_tooltip,
        "Number of scanline pairs across the screen." to R.string.fusion_fx_scanlines_density_tooltip,
        "How dark the gaps between lines become." to R.string.fusion_fx_scanlines_intensity_tooltip,
        "Phosphor Tint" to R.string.fusion_fx_scanlines_tint_label,
        "Strength of the green-warm phosphor cast." to R.string.fusion_fx_scanlines_tint_tooltip,
        "Scanlines" to R.string.fusion_fx_scanlines_scanlines_label,
        "Darkens alternating rows of pixels to imitate the horizontal scanlines of a CRT. Cheaper than the full CRT effect when you only want the lines." to R.string.fusion_fx_scanlines_scanlines_tooltip,
        "Sharpen" to R.string.fusion_fx_sharpen_sharpen_label,
        "Unsharp mask that brings back edge detail lost to scaling. Most useful after upscaling a low internal resolution." to R.string.fusion_fx_sharpen_sharpen_tooltip,
        "Shadow Hue" to R.string.fusion_fx_splittoning_shadowhue_label,
        "Shadow Strength" to R.string.fusion_fx_splittoning_shadowstrength_label,
        "Highlight Hue" to R.string.fusion_fx_splittoning_highlighthue_label,
        "Highlight Strength" to R.string.fusion_fx_splittoning_highlightstrength_label,
        "Balance" to R.string.fusion_fx_splittoning_balance_label,
        "Moves the split between what counts as shadow and what counts as highlight." to R.string.fusion_fx_splittoning_balance_tooltip,
        "Split Toning" to R.string.fusion_fx_splittoning_splittoning_label,
        "Tints the shadows and the highlights towards two different hues." to R.string.fusion_fx_splittoning_splittoning_tooltip,
        "How dark the corners become." to R.string.fusion_fx_vignette_strength_tooltip,
        "Aspect" to R.string.fusion_fx_vignette_aspect_label,
        "Vignette" to R.string.fusion_fx_vignette_vignette_label,
        "Darkens the corners of the screen to pull the eye towards the middle, the way a camera lens falls off at its edges." to R.string.fusion_fx_vignette_vignette_tooltip,
        "Temperature" to R.string.fusion_fx_whitebalance_temperature_label,
        "Negative cools the picture towards blue, positive warms it towards orange." to R.string.fusion_fx_whitebalance_temperature_tooltip,
        "Tint" to R.string.fusion_fx_whitebalance_tint_label,
        "Negative shifts towards green, positive towards magenta." to R.string.fusion_fx_whitebalance_tint_tooltip,
        "White Balance" to R.string.fusion_fx_whitebalance_whitebalance_label,
        "Corrects a picture that looks too cool, too warm or tinted." to R.string.fusion_fx_whitebalance_whitebalance_tooltip,
        "Anime" to R.string.fusion_fx_preset_anime_name,
        "Cel shaded look: the picture is cleaned first, then the lighting is collapsed into flat bands with inked edges, finished with a small glow." to R.string.fusion_fx_preset_anime_description,
        "Cinematic" to R.string.fusion_fx_preset_cinematic_name,
        "Film look: soft glow on the highlights, a filmic contrast curve, cool shadows against warm highlights, fine grain and a gentle vignette." to R.string.fusion_fx_preset_cinematic_description,
        "Clean" to R.string.fusion_fx_preset_clean_name,
        "Cleans up a low internal resolution: removes dithering and compression noise, smooths banded skies and brings edge detail back. The safe starting point if you are not sure what you want." to R.string.fusion_fx_preset_clean_description,
        "Dreamy" to R.string.fusion_fx_preset_dreamy_name,
        "Soft focus: the centre of the screen stays sharp while everything around it blurs, with a wide glow over the highlights. Good for slower games and cutscenes." to R.string.fusion_fx_preset_dreamy_description,
        "Retro" to R.string.fusion_fx_preset_retro_name,
        "Old television look: colour fringing at the edges of the screen, curved scanlines with a phosphor mask, and darkened corners. The scanline roll is switched off so it does not crawl." to R.string.fusion_fx_preset_retro_description,
        "Vivid" to R.string.fusion_fx_preset_vivid_name,
        "Punchier colour without stylising the image: warmer and more saturated, blacks and whites pushed to the ends of the range, and a light sharpen." to R.string.fusion_fx_preset_vivid_description
    )
}
