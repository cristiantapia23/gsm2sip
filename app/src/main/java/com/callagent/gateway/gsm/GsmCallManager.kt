package com.callagent.gateway.gsm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.callagent.gateway.DeviceProfile

/**
 * GSM call manager: answers/makes/hangs up GSM calls, tracks state.
 * Adapted for non-root standard Android devices.
 */
object GsmCallManager {

    private const val TAG = "GsmCallManager"

    /** Active device profile — initialized on first use. */
    val profile: DeviceProfile by lazy { DeviceProfile.detect() }

    // Current active GSM call
    @Volatile var activeCall: Call? = null; private set
    @Volatile var activeCallState: Int = Call.STATE_NEW; private set
    @Volatile var inCallService: InCallService? = null; private set

    @Volatile var listener: Listener? = null

    /** Optional callback for routing audio diagnostics to the log viewer */
    @Volatile var logCallback: ((String) -> Unit)? = null

    private fun appLog(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    interface Listener {
        fun onIncomingGsmCall(call: Call, number: String)
        fun onGsmCallActive(call: Call)
        fun onGsmCallStateChanged(call: Call, state: Int)
        fun onGsmCallEnded(call: Call)
    }

    // ── InCallService callbacks ─────────────────────────

    fun onCallAdded(call: Call, service: InCallService) {
        inCallService = service
        activeCall = call
        activeCallState = call.state

        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        when (call.state) {
            Call.STATE_RINGING -> {
                Log.i(TAG, "Incoming GSM call from $number")
                try {
                    val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Ringer silence failed: ${e.message}")
                }
                listener?.onIncomingGsmCall(call, number)
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                Log.i(TAG, "Outgoing GSM call to $number")
            }
            Call.STATE_ACTIVE -> {
                Log.i(TAG, "GSM call active: $number")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
        }
    }

    fun onCallRemoved(call: Call) {
        Log.i(TAG, "GSM call removed")
        if (activeCall == call) {
            activeCall = null
            activeCallState = Call.STATE_DISCONNECTED
        }
        restoreAudio()
        listener?.onGsmCallEnded(call)
    }

    fun onCallStateChanged(call: Call, state: Int) {
        activeCallState = state

        when (state) {
            Call.STATE_RINGING -> {
                val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
                Log.i(TAG, "GSM call ringing: $number (via state change)")
                listener?.onIncomingGsmCall(call, number)
            }
            Call.STATE_ACTIVE -> {
                Log.i(TAG, "GSM call active")
                configureAudioBridge()
                listener?.onGsmCallActive(call)
            }
            Call.STATE_DISCONNECTED -> {
                Log.i(TAG, "GSM call disconnected")
                listener?.onGsmCallEnded(call)
                if (activeCall == call) {
                    activeCall = null
                }
            }
        }
        listener?.onGsmCallStateChanged(call, state)
    }

    // ── Call control ────────────────────────────────────

    /** Answer a ringing GSM call explicitly */
    fun answerCall(call: Call? = activeCall) {
        call?.let {
            try {
                Log.i(TAG, "Answering GSM call")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.answer(VideoProfile.STATE_AUDIO_ONLY)
                } else {
                    @Suppress("DEPRECATION")
                    it.answer(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call: ${e.message}")
            }
        }
    }

    /** Reject a ringing GSM call */
    fun rejectCall(call: Call? = activeCall) {
        call?.let {
            try {
                Log.i(TAG, "Rejecting GSM call")
                it.reject(false, "")
            } catch (e: Exception) {
                Log.e(TAG, "Error rejecting call: ${e.message}")
            }
        }
    }

    /** Hang up active GSM call */
    fun hangupCall(call: Call? = activeCall) {
        call?.let {
            try {
                Log.i(TAG, "Hanging up GSM call")
                it.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error hanging up call: ${e.message}")
            }
        }
    }

    /** Place outgoing GSM call via the SIM */
    fun makeCall(context: Context, destination: String) {
        try {
            Log.i(TAG, "Making GSM call to $destination")
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$destination"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error making call: ${e.message}")
        }
    }

    /** Music volume percent — from device profile. */
    val MUSIC_VOL_PERCENT: Int get() = profile.musicVolPercent

    private fun configureAudioBridge() {
        try {
            inCallService?.let { service ->
                val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

                if (profile.requireSpeakerMode) {
                    service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                }

                audioManager?.let { am ->
                    am.isMicrophoneMute = false
                    enforceVolumes(am)

                    Thread({
                        try {
                            Thread.sleep(profile.routeChangeDelayMs)
                            enforceVolumes(am)
                            batchMixerSetup()
                        } catch (_: Exception) {}
                    }, "VolEnforce").start()

                    appLog("Audio bridge configured: mode=${am.mode}, profile=${profile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio: ${e.message}")
        }
    }

    fun enforceVolumes(am: AudioManager) {
        try {
            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: SecurityException) {}

        try {
            val vcVol = if (profile.voiceCallVolPercent > 0) {
                val maxVc = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                (maxVc * profile.voiceCallVolPercent / 100).coerceAtLeast(1)
            } else {
                1
            }
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vcVol, 0)
        } catch (_: SecurityException) {}

        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val musicVol = (maxMusic * MUSIC_VOL_PERCENT / 100).coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, musicVol, 0)

        val actualVoice = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val actualMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        appLog("Vol: voice=$actualVoice, music=$actualMusic/$maxMusic")
    }

    private fun restoreAudio() {
        try {
            batchMixerRestore()

            inCallService?.let { service ->
                service.setAudioRoute(CallAudioState.ROUTE_EARPIECE)

                val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    am.isMicrophoneMute = false
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: SecurityException) {}
                    try {
                        val maxVc = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVc * 2 / 3).coerceAtLeast(1), 0)
                    } catch (_: SecurityException) {}
                    Log.i(TAG, "Audio restored")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio: ${e.message}")
        }
    }

    fun batchMixerSetup() {
        if (profile.mixerSetupCmd.isEmpty()) return
        appLog("Non-root mode: skipping mixer setup")
    }

    fun batchMixerRestore() {
        if (profile.mixerRestoreCmd.isEmpty()) return
        Log.i(TAG, "Non-root mode: skipping mixer restore")
    }

    val isCallActive: Boolean
        get() = activeCall != null && activeCallState == Call.STATE_ACTIVE

    val currentNumber: String?
        get() = activeCall?.details?.handle?.schemeSpecificPart
}
