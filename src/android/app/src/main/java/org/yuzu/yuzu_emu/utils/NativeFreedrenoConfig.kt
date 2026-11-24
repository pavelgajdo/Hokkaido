// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.annotation.StringRes
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication

/**
 * Provides access to Freedreno/Turnip driver configuration through JNI bindings.
 *
 * This class allows Java/Kotlin code to configure Freedreno environment variables
 * for the GPU driver (Turnip/Freedreno) that runs in the emulator on Android.
 *
 * Variables must be set BEFORE starting emulation for them to take effect.
 *
 * See https://docs.mesa3d.org/drivers/freedreno.html for documentation.
 */
object NativeFreedrenoConfig {

    @Synchronized
    external fun setFreedrenoBasePath(basePath: String)

    @Synchronized
    external fun initializeFreedrenoConfig()

    @Synchronized
    external fun saveFreedrenoConfig()

    @Synchronized
    external fun reloadFreedrenoConfig()

    @Synchronized
    external fun setFreedrenoEnv(varName: String, value: String): Boolean

    @Synchronized
    external fun getFreedrenoEnv(varName: String): String

    @Synchronized
    external fun isFreedrenoEnvSet(varName: String): Boolean

    @Synchronized
    external fun clearFreedrenoEnv(varName: String): Boolean

    @Synchronized
    external fun clearAllFreedrenoEnv()

    @Synchronized
    external fun getFreedrenoEnvSummary(): String

    @Synchronized
    external fun setCurrentProgramId(programId: String)

    @Synchronized
    external fun loadPerGameConfig(programId: String): Boolean

    @Synchronized
    external fun loadPerGameConfigWithGlobalFallback(programId: String): Boolean

    @Synchronized
    external fun savePerGameConfig(programId: String): Boolean

    @Synchronized
    external fun hasPerGameConfig(programId: String): Boolean

    @Synchronized
    external fun deletePerGameConfig(programId: String): Boolean
}

/**
 * Data class representing a Freedreno preset configuration.
 * Presets are commonly used debugging/profiling configurations.
 */
data class FreedrenoPreset(
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: String,           // Icon identifier
    val variables: Map<String, String> // Map of env vars to set
) {
    val name: String get() = YuzuApplication.applyLanguage(YuzuApplication.appContext).getString(nameRes)
    val description: String get() = YuzuApplication.applyLanguage(YuzuApplication.appContext).getString(descriptionRes)
}

/**
 * Predefined Freedreno presets for quick configuration.
 */
object FreedrenoPresets {

    val DEBUG_CPU_MEMORY = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_cpu_memory,
        descriptionRes = R.string.fusion_freedreno_cpu_memory_description,
        icon = "ic_debug_cpu",
        variables = mapOf(
            "TU_DEBUG" to "sysmem"
        )
    )

    val DEBUG_UBWC_DISABLED = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_no_ubwc,
        descriptionRes = R.string.fusion_freedreno_no_ubwc_description,
        icon = "ic_debug_ubwc",
        variables = mapOf(
            "TU_DEBUG" to "noubwc"
        )
    )

    val DEBUG_NO_BINNING = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_no_binning,
        descriptionRes = R.string.fusion_freedreno_no_binning_description,
        icon = "ic_debug_bin",
        variables = mapOf(
            "TU_DEBUG" to "nobin"
        )
    )

    val CAPTURE_RENDERPASS = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_capture_renderpass,
        descriptionRes = R.string.fusion_freedreno_capture_renderpass_description,
        icon = "ic_capture",
        variables = mapOf(
            "FD_RD_DUMP" to "enable"
        )
    )

    val CAPTURE_FRAMES = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_capture_frames,
        descriptionRes = R.string.fusion_freedreno_capture_frames_description,
        icon = "ic_capture",
        variables = mapOf(
            "FD_RD_DUMP" to "enable",
            "FD_RD_DUMP_FRAMES" to "0-100"
        )
    )

    val SHADER_DEBUG = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_shader_debug,
        descriptionRes = R.string.fusion_freedreno_shader_debug_description,
        icon = "ic_shader",
        variables = mapOf(
            "IR3_SHADER_DEBUG" to "nouboopt,spillall"
        )
    )

    val GPU_HANG_TRACE = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_hang_trace,
        descriptionRes = R.string.fusion_freedreno_hang_trace_description,
        icon = "ic_hang_trace",
        variables = mapOf(
            "TU_BREADCRUMBS" to "1"
        )
    )

    val DEV_FEATURES_UBWC_HINT = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_ubwc_hint,
        descriptionRes = R.string.fusion_freedreno_ubwc_hint_description,
        icon = "ic_dev_features",
        variables = mapOf(
            "FD_DEV_FEATURES" to "enable_tp_ubwc_flag_hint=1"
        )
    )

    val PERFORMANCE_DEFAULT = FreedrenoPreset(
        nameRes = R.string.fusion_freedreno_performance,
        descriptionRes = R.string.fusion_freedreno_performance_description,
        icon = "ic_performance",
        variables = emptyMap()  // Clears all when applied
    )

    val ALL_PRESETS = listOf(
        DEBUG_CPU_MEMORY,
        DEBUG_UBWC_DISABLED,
        DEBUG_NO_BINNING,
        CAPTURE_RENDERPASS,
        CAPTURE_FRAMES,
        SHADER_DEBUG,
        GPU_HANG_TRACE,
        DEV_FEATURES_UBWC_HINT,
        PERFORMANCE_DEFAULT
    )
}
