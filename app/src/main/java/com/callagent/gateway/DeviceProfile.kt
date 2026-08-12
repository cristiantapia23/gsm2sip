package com.callagent.gateway

import android.util.Log

/**
 * Device-specific audio profile adapted for Non-Root standard Android devices.
 * Completely skips tinymix/su checks and uses Android standard HAL APIs.
 */
data class DeviceProfile(
    val name: String,
    val mixerSetupCmd: String = "",
    val mixerRestoreCmd: String = "",
    val mixerIncallMusicCmd: String = "",
    val mixerDiagGrep: String = "",
    val musicVolPercent: Int = 20,
    val captureGain: Int = 1,
    val playbackGain: Int = 2,
    val noiseGateThreshold: Int = 300,
    val echoGateThreshold: Int = 300,
    val doubleTalkRatio: Float = 1.5f,
    val requireSpeakerMode: Boolean = true,
    val incallMusicParam: String = "incall_music_enabled",
    val voiceDownlinkWorks: Boolean = true,
    val voiceCallVolPercent: Int = 0,
    val playbackUsage: Int = -1,
    val routeChangeDelayMs: Long = 500,
    val appopsPropagationMs: Long = 300,
) {
    companion object {
        private const val TAG = "DeviceProfile"

        // Non-root build: tinymix disabled completely
        val tinymixBin: String = ""

        fun discoverMixerControls(): String {
            return "Non-root mode: tinymix bypassed."
        }

        fun resolveCmd(cmd: String): String {
            return ""
        }

        /** Auto-detect always falls back to generic standard Android APIs */
        fun detect(): DeviceProfile {
            Log.i(TAG, "Non-Root profile active. Bypassing root shell checks.")
            return generic()
        }

        fun generic() = DeviceProfile(
            name = "Generic Non-Root Android",
            mixerSetupCmd = "",
            mixerRestoreCmd = "",
            mixerIncallMusicCmd = "",
            mixerDiagGrep = "",
            musicVolPercent = 20,
            captureGain = 1,
            playbackGain = 2,
            noiseGateThreshold = 300,
            echoGateThreshold = 300,
            doubleTalkRatio = 1.5f,
            requireSpeakerMode = false,
            incallMusicParam = "incall_music_enabled",
            voiceDownlinkWorks = true,
            routeChangeDelayMs = 200,
            appopsPropagationMs = 100
        )
    }
}
