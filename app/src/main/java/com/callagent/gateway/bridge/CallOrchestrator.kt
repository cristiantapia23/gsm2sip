package com.callagent.gateway.bridge

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.util.Log
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.rtp.RtpPacket
import com.callagent.gateway.rtp.RtpSession
import com.callagent.gateway.sip.SipCall
import com.callagent.gateway.sip.SipClient
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Orchestrates the bidirectional GSM ↔ SIP bridge.
 * Adapted for non-root standard Android devices.
 */
class CallOrchestrator(
    private val context: Context,
    private val sipClient: SipClient
) : SipClient.Listener, GsmCallManager.Listener, SipCall.Listener {

    private var activeRtpSession: RtpSession? = null
    private var activeSipCall: SipCall? = null
    private var activeGsmCall: Call? = null
    @Volatile private var diallerInitiated = false
    @Volatile private var lastStateChangeTime = 0L

    // Pending RTP info
    private var pendingRtpAddr: String? = null
    private var pendingRtpPort: Int = 0
    private var pendingPayloadType: Int = 0
    private var pendingLocalRtpPort: Int = 0

    // SIP call retry
    private var sipCallRetries = 0
    private val MAX_SIP_RETRIES = 2

    /** Current bridge state */
    @Volatile var bridgeState: BridgeState = BridgeState.IDLE
        private set

    @Volatile var listener: OrchestratorListener? = null

    interface OrchestratorListener {
        fun onStateChanged(state: BridgeState, info: String)
        fun onError(error: String)
        fun onRtpStats(stats: String) {}
    }

    enum class BridgeState {
        IDLE,
        GSM_RINGING,        // Incoming GSM, waiting to answer
        GSM_ANSWERED,       // GSM answered, placing SIP call
        SIP_CALLING,        // SIP INVITE sent, waiting for answer
        SIP_RINGING,        // SIP ringing at Asterisk
        BRIDGED,            // Both sides active, audio flowing
        GSM_DIALING,        // Outbound: dialing GSM number
        TEARING_DOWN        // Hanging up
    }

    fun start() {
        sipClient.listener = this
        GsmCallManager.listener = this
        Log.i(TAG, "CallOrchestrator started")
    }

    fun stop() {
        tearDown("Orchestrator stopped")
        sipClient.listener = null
        GsmCallManager.listener = null
    }

    /** Initiate an outgoing GSM call from the dialler, then bridge to SIP */
    fun initiateDiallerCall(number: String) {
        if (bridgeState != BridgeState.IDLE) {
            val staleMs = System.currentTimeMillis() - lastStateChangeTime
            if (staleMs > STALE_STATE_TIMEOUT_MS) {
                Log.w(TAG, "Bridge stuck in $bridgeState for ${staleMs/1000}s — force resetting")
                forceReset("Stale state: $bridgeState for ${staleMs/1000}s")
            } else {
                Log.w(TAG, "Busy ($bridgeState) — cannot dial from dialler")
                listener?.onError("Busy — cannot dial")
                return
            }
        }
        Log.i(TAG, "Dialler-initiated call to $number")
        diallerInitiated = true
        lastStateChangeTime = System.currentTimeMillis()
        bridgeState = BridgeState.GSM_DIALING
        listener?.onStateChanged(bridgeState, "Dialing $number")
        GsmCallManager.makeCall(context, number)

        Thread({
            Thread.sleep(GSM_DIAL_TIMEOUT_MS)
            if (bridgeState == BridgeState.GSM_DIALING) {
                Log.w(TAG, "GSM dial timeout — no call events in ${GSM_DIAL_TIMEOUT_MS / 1000}s")
                tearDown("GSM dial timeout")
            }
        }, "GSM-Dial-Timeout").start()
    }

    // ── SipClient.Listener ──────────────────────────────

    override fun onRegistered() {
        Log.i(TAG, "SIP registered — ready for calls")
        listener?.onStateChanged(BridgeState.IDLE, "SIP registered")
    }

    override fun onRegistrationFailed() {
        Log.e(TAG, "SIP registration failed")
        listener?.onError("SIP registration failed")
    }

    /** Incoming SIP INVITE from Asterisk */
    override fun onIncomingCall(call: SipCall) {
        Log.i(TAG, "Incoming SIP call: ${call.callId}, gsm_forward=${call.gsmForwardNumber}")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting SIP call")
            call.hangup()
            return
        }

        val gsmDest = call.gsmForwardNumber
        if (gsmDest != null) {
            handleOutboundFlow(call, gsmDest)
        } else {
            Log.w(TAG, "SIP INVITE without X-GSM-Forward header, answering directly")
            val rtpPort = allocateRtpPort()
            call.listener = this
            call.accept(rtpPort)
            activeSipCall = call
        }
    }

    override fun onCallTerminated(call: SipCall) {
        Log.i(TAG, "SIP call terminated: ${call.callId} (bridge=$bridgeState, retries=$sipCallRetries)")
        if (call != activeSipCall) return

        if ((bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING)
            && sipCallRetries < MAX_SIP_RETRIES && activeGsmCall != null) {
            sipCallRetries++
            Log.w(TAG, "SIP call failed while GSM ringing — retrying ($sipCallRetries/$MAX_SIP_RETRIES)")
            listener?.onStateChanged(bridgeState, "SIP retry $sipCallRetries/$MAX_SIP_RETRIES")
            activeSipCall = null
            sipClient.removeCall(call.callId)
            Thread({
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                if (bridgeState != BridgeState.SIP_CALLING && bridgeState != BridgeState.SIP_RINGING) return@Thread
                activeGsmCall?.let { handleInboundFlow(it) }
                    ?: Log.e(TAG, "SIP retry: GSM call gone, aborting")
            }, "SIP-Retry-$sipCallRetries").start()
            return
        }

        tearDown("SIP call ended")
    }

    // ── GsmCallManager.Listener ─────────────────────────

    override fun onIncomingGsmCall(call: Call, number: String) {
        Log.i(TAG, "Incoming GSM call from $number")

        if (bridgeState != BridgeState.IDLE) {
            Log.w(TAG, "Busy — rejecting GSM call")
            GsmCallManager.rejectCall(call)
            return
        }

        sipCallRetries = 0
        bridgeState = BridgeState.GSM_RINGING
        activeGsmCall = call
        listener?.onStateChanged(bridgeState, "GSM call from $number")

        Log.i(TAG, "GSM ringing from $number — placing SIP call first")
        Thread({ handleInboundFlow(call) }, "SIP-OutCall").start()
    }

    override fun onGsmCallActive(call: Call) {
        Log.i(TAG, "GSM call active")
        activeGsmCall = call

        when (bridgeState) {
            BridgeState.SIP_CALLING, BridgeState.SIP_RINGING -> {
                val addr = pendingRtpAddr
                val port = pendingRtpPort
                val pt = pendingPayloadType
                val localPort = pendingLocalRtpPort
                pendingRtpAddr = null

                if (addr != null && port > 0) {
                    Thread({
                        startRtp(localPort, addr, port, pt)
                        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                            Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                            return@Thread
                        }
                        bridgeState = BridgeState.BRIDGED
                        listener?.onStateChanged(bridgeState, "Bridged (inbound)")
                        Log.i(TAG, "Inbound bridge established")
                    }, "RTP-Start").start()
                } else {
                    Log.w(TAG, "GSM active but no pending RTP info — waiting for SIP")
                    bridgeState = BridgeState.GSM_ANSWERED
                }
            }
            BridgeState.GSM_DIALING -> {
                if (diallerInitiated) {
                    diallerInitiated = false
                    bridgeState = BridgeState.GSM_ANSWERED
                    listener?.onStateChanged(bridgeState, "GSM answered, calling Asterisk")
                    Thread({ handleInboundFlow(call) }, "SIP-OutCall").start()
                } else {
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (outbound)")

                    Thread({
                        activeSipCall?.let { sipCall ->
                            val rtpPort = allocateRtpPort()
                            sipCall.listener = this
                            sipCall.accept(rtpPort)

                            val addr = sipCall.remoteRtpAddress ?: sipClient.serverDomain
                            val port = sipCall.remoteRtpPort
                            val pt = sipCall.negotiatedPayloadType
                            if (port > 0) {
                                startRtp(rtpPort, addr, port, pt)
                            }
                        }
                        Log.i(TAG, "Outbound bridge established")
                    }, "SIP-Bridge").start()
                }
            }
            else -> {}
        }
    }

    override fun onGsmCallStateChanged(call: Call, state: Int) {
        val stateStr = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "OTHER($state)"
        }
        Log.d(TAG, "GSM state: $stateStr")

        if (activeGsmCall == null && bridgeState != BridgeState.IDLE) {
            activeGsmCall = call
        }

        if (state == Call.STATE_DISCONNECTED && bridgeState != BridgeState.IDLE) {
            tearDown("GSM call disconnected")
        }
    }

    override fun onGsmCallEnded(call: Call) {
        Log.i(TAG, "GSM call ended")
        if (call == activeGsmCall || (activeGsmCall == null && bridgeState != BridgeState.IDLE)) {
            tearDown("GSM call ended")
        }
    }

    // ── SipCall.Listener ────────────────────────────────

    override fun onCallAnswered(call: SipCall) {
        Log.i(TAG, "SIP call answered: ${call.callId}")
    }

    override fun onRtpReady(call: SipCall, remoteRtpAddr: String, remoteRtpPort: Int, payloadType: Int) {
        val codecName = when (payloadType) {
            RtpPacket.PT_G722 -> "G.722"
            RtpPacket.PT_PCMA -> "PCMA"
            RtpPacket.PT_PCMU -> "PCMU"
            else -> "PT$payloadType"
        }
        Log.i(TAG, "RTP ready: $remoteRtpAddr:$remoteRtpPort codec=$codecName bridgeState=$bridgeState")

        if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
            val gsmAlreadyActive = GsmCallManager.isCallActive

            if (gsmAlreadyActive) {
                Log.i(TAG, "SIP answered (codec=$codecName) — GSM already active, starting RTP now")
                val localRtpPort = call.localRtpPort
                Thread({
                    startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
                    if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) {
                        Log.w(TAG, "Bridge torn down during RTP setup — not transitioning to BRIDGED")
                        return@Thread
                    }
                    bridgeState = BridgeState.BRIDGED
                    listener?.onStateChanged(bridgeState, "Bridged (dialler)")
                    Log.i(TAG, "Dialler bridge established (codec=$codecName)")
                }, "RTP-Start").start()
            } else {
                pendingRtpAddr = remoteRtpAddr
                pendingRtpPort = remoteRtpPort
                pendingPayloadType = payloadType
                pendingLocalRtpPort = call.localRtpPort

                Log.i(TAG, "SIP answered (codec=$codecName) — answering GSM call now")
                activeGsmCall?.let { GsmCallManager.answerCall(it) }
                    ?: Log.e(TAG, "SIP answered but no active GSM call to answer!")
            }
        } else if (bridgeState == BridgeState.GSM_ANSWERED) {
            val localRtpPort = call.localRtpPort
            startRtp(localRtpPort, remoteRtpAddr, remoteRtpPort, payloadType)
            bridgeState = BridgeState.BRIDGED
            listener?.onStateChanged(bridgeState, "Bridged (inbound)")
            Log.i(TAG, "Bridge established (codec=$codecName)")
        } else {
            Log.w(TAG, "onRtpReady ignored — bridgeState=$bridgeState")
        }
    }

    // ── Inbound flow (GSM → SIP) ───────────────────────

    private fun handleInboundFlow(gsmCall: Call) {
        val callerNumber = gsmCall.details?.handle?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "Inbound flow: placing SIP call for GSM caller $callerNumber")

        bridgeState = BridgeState.SIP_CALLING
        listener?.onStateChanged(bridgeState, "Calling Asterisk for $callerNumber")

        val rtpPort = allocateRtpPort()
        val sipCall = sipClient.makeCall(
            targetExtension = sipClient.username,
            localRtpPort = rtpPort,
            callerIdNumber = callerNumber,
            callerIdName = callerNumber
        )
        sipCall.listener = this
        activeSipCall = sipCall

        Log.i(TAG, "SIP INVITE sent to Asterisk (caller=$callerNumber, rtp=$rtpPort)")

        Thread({
            Thread.sleep(SIP_CALL_TIMEOUT_MS)
            if (bridgeState == BridgeState.SIP_CALLING || bridgeState == BridgeState.SIP_RINGING) {
                Log.w(TAG, "SIP call timeout — Asterisk didn't answer in ${SIP_CALL_TIMEOUT_MS / 1000}s")
                tearDown("Asterisk not answering")
            }
        }, "SIP-Timeout").start()
    }

    // ── Outbound flow (SIP → GSM) ──────────────────────

    private fun handleOutboundFlow(sipCall: SipCall, gsmDestination: String) {
        Log.i(TAG, "Outbound flow: dialing GSM $gsmDestination")

        bridgeState = BridgeState.GSM_DIALING
        activeSipCall = sipCall
        listener?.onStateChanged(bridgeState, "Dialing $gsmDestination")

        sipCall.originalInvite?.let { invite ->
            val ringing = com.callagent.gateway.sip.SipBuilder.ringing180(invite, sipCall.localTag)
            sipClient.sendTo(ringing, sipCall.remoteContactAddress ?: sipClient.serverAddress)
        }

        GsmCallManager.makeCall(context, gsmDestination)
    }

    // ── RTP ─────────────────────────────────────────────

    private fun startRtp(localPort: Int, remoteAddr: String, remotePort: Int,
                         payloadType: Int = RtpPacket.PT_PCMA) {
        forceAllowRecordAudio()

        activeRtpSession?.stop()
        val session = RtpSession(context, localPort, remoteAddr, remotePort, payloadType)
        session.listener = object : RtpSession.Listener {
            override fun onRtpStarted() {
                Log.i(TAG, "RTP session started")
            }
            override fun onRtpStopped() {
                Log.i(TAG, "RTP session stopped")
            }
            override fun onRtpError(error: String) {
                Log.e(TAG, "RTP error: $error")
                listener?.onError("RTP: $error")
            }
            override fun onRtpTimeout() {
                Log.w(TAG, "RTP timeout — no audio from Asterisk, tearing down")
                tearDown("RTP timeout")
            }
            override fun onRtpStats(stats: String) {
                listener?.onRtpStats(stats)
            }
        }
        session.start()
        activeRtpSession = session
    }

    // ── Teardown ────────────────────────────────────────

    @Synchronized
    private fun tearDown(reason: String) {
        if (bridgeState == BridgeState.IDLE || bridgeState == BridgeState.TEARING_DOWN) return
        bridgeState = BridgeState.TEARING_DOWN
        diallerInitiated = false
        Log.i(TAG, "Tearing down bridge: $reason")

        try {
            activeRtpSession?.stop()
            activeRtpSession = null

            activeSipCall?.let {
                try {
                    if (it.state != SipCall.State.TERMINATED) it.hangup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error hanging up SIP: ${e.message}")
                }
                sipClient.removeCall(it.callId)
            }
            activeSipCall = null

            activeGsmCall?.let { call ->
                try {
                    call.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting GSM: ${e.message}")
                }
            }
            activeGsmCall = null
            pendingRtpAddr = null
        } finally {
            bridgeState = BridgeState.IDLE
            lastStateChangeTime = System.currentTimeMillis()
            listener?.onStateChanged(BridgeState.IDLE, reason)
            Log.i(TAG, "Bridge torn down: $reason")
        }
    }

    // ── Utility ─────────────────────────────────────────

    private fun allocateRtpPort(): Int {
        for (port in 30000..40000 step 2) {
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                continue
            }
        }
        throw RuntimeException("No free RTP port available")
    }

    /** Non-root permission bypass guard */
    private fun forceAllowRecordAudio() {
        try {
            Log.d(TAG, "Non-root device: skipping shell appops invocation")
        } catch (e: Exception) {
            Log.w(TAG, "Permission check bypassed: ${e.message}")
        }
    }

    @Synchronized
    private fun forceReset(reason: String) {
        Log.w(TAG, "Force-resetting bridge: $reason")
        try { activeRtpSession?.stop() } catch (_: Exception) {}
        activeRtpSession = null
        try {
            activeSipCall?.let {
                if (it.state != SipCall.State.TERMINATED) it.hangup()
                sipClient.removeCall(it.callId)
            }
        } catch (_: Exception) {}
        activeSipCall = null
        try { activeGsmCall?.disconnect() } catch (_: Exception) {}
        activeGsmCall = null
        pendingRtpAddr = null
        diallerInitiated = false
        bridgeState = BridgeState.IDLE
        lastStateChangeTime = System.currentTimeMillis()
        listener?.onStateChanged(BridgeState.IDLE, reason)
        Log.i(TAG, "Bridge force-reset complete: $reason")
    }

    companion object {
        private const val TAG = "CallOrchestrator"
        private const val SIP_CALL_TIMEOUT_MS = 30_000L
        private const val GSM_DIAL_TIMEOUT_MS = 45_000L
        private const val STALE_STATE_TIMEOUT_MS = 60_000L
    }
}
