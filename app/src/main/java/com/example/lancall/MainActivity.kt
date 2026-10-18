package com.example.lancall

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class MainActivity : Activity() {

    private val port = 8765

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var hostBtn: Button
    private lateinit var joinBtn: Button
    private lateinit var endBtn: Button

    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null

    private var pc: PeerConnection? = null
    private var socket: Socket? = null
    private var server: ServerSocket? = null
    private var writer: PrintWriter? = null

    private val lock = Any()
    private var remoteSet = false
    private val pending = mutableListOf<IceCandidate>()
    private var isHost = false
    private var mediaReady = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        eglBase = EglBase.create()
        buildUi()
        bindWifi()
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            initMedia()
        } else {
            requestPermissions(needed, 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initMedia()
        } else {
            setStatus("يجب السماح بالكاميرا والميكروفون من إعدادات التطبيق ثم إعادة فتحه")
        }
    }

    override fun onDestroy() {
        endCall()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { localView.release() } catch (_: Exception) {}
        try { remoteView.release() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        remoteView = SurfaceViewRenderer(this)
        remoteView.init(eglBase.eglBaseContext, null)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteView.setEnableHardwareScaler(true)
        root.addView(
            remoteView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        localView = SurfaceViewRenderer(this)
        localView.init(eglBase.eglBaseContext, null)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.setEnableHardwareScaler(true)
        localView.setMirror(true)
        localView.setZOrderMediaOverlay(true)
        val lp = FrameLayout.LayoutParams(dp(110), dp(150))
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(40)
        lp.marginEnd = dp(12)
        root.addView(localView, lp)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.parseColor("#99000000"))
        panel.setPadding(dp(16), dp(12), dp(16), dp(24))

        statusText = TextView(this)
        statusText.setTextColor(Color.WHITE)
        statusText.textSize = 16f
        statusText.text = "ثبّت التطبيق على الهاتفين وصلهما بنفس الراوتر"
        panel.addView(statusText)

        ipInput = EditText(this)
        ipInput.hint = "عنوان الهاتف الآخر مثل 192.168.1.5"
        ipInput.setHintTextColor(Color.LTGRAY)
        ipInput.setTextColor(Color.WHITE)
        ipInput.inputType = InputType.TYPE_CLASS_TEXT
        panel.addView(ipInput)

        hostBtn = Button(this)
        hostBtn.text = "ابدأ مكالمة (أنا أستقبل)"
        hostBtn.setOnClickListener {
            if (!mediaReady) {
                setStatus("لم تُمنح أذونات الكاميرا والميكروفون")
            } else {
                startHost()
            }
        }
        panel.addView(hostBtn)

        joinBtn = Button(this)
        joinBtn.text = "انضمام (أنا أتصل)"
        joinBtn.setOnClickListener {
            if (!mediaReady) {
                setStatus("لم تُمنح أذونات الكاميرا والميكروفون")
            } else {
                startJoin(ipInput.text.toString().trim())
            }
        }
        panel.addView(joinBtn)

        endBtn = Button(this)
        endBtn.text = "إنهاء المكالمة"
        endBtn.visibility = View.GONE
        endBtn.setOnClickListener {
            endCall()
            setStatus("انتهت المكالمة")
        }
        panel.addView(endBtn)

        val plp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        plp.gravity = Gravity.BOTTOM
        root.addView(panel, plp)

        setContentView(root)
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusText.text = s }
    }

    private fun showMenu() {
        runOnUiThread {
            ipInput.visibility = View.VISIBLE
            hostBtn.visibility = View.VISIBLE
            joinBtn.visibility = View.VISIBLE
            endBtn.visibility = View.GONE
        }
    }

    private fun showInCall(msg: String) {
        runOnUiThread {
            ipInput.visibility = View.GONE
            hostBtn.visibility = View.GONE
            joinBtn.visibility = View.GONE
            endBtn.visibility = View.VISIBLE
            statusText.text = msg
        }
    }

    // ---------------------------------------------------------------- network helpers

    // Keep traffic on the Wi-Fi network even if it has no internet.
    private fun bindWifi() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n)
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun localIps(): String {
        val list = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") ||
                    name.startsWith("tun") || name.startsWith("v4-")
                ) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        a.hostAddress?.let { list.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return if (list.isEmpty()) "غير معروف" else list.joinToString("   /   ")
    }

    // ---------------------------------------------------------------- media

    private fun initMedia() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()

            val enumerator = Camera2Enumerator(this)
            val camName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (camName == null) {
                setStatus("لم يتم العثور على كاميرا")
                return
            }
            val cap = enumerator.createCapturer(camName, null)
            capturer = cap
            helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            val vs = factory.createVideoSource(false)
            videoSource = vs
            cap.initialize(helper, applicationContext, vs.capturerObserver)
            cap.startCapture(640, 480, 30)
            val vt = factory.createVideoTrack("v0", vs)
            vt.addSink(localView)
            videoTrack = vt

            val aSrc = factory.createAudioSource(MediaConstraints())
            audioSource = aSrc
            audioTrack = factory.createAudioTrack("a0", aSrc)

            mediaReady = true
            setStatus("جاهز. اختر: ابدأ مكالمة، أو انضمام")
        } catch (e: Exception) {
            setStatus("خطأ في تشغيل الكاميرا: ${e.message}")
        }
    }

    private fun createPc() {
        val config = PeerConnection.RTCConfiguration(ArrayList<PeerConnection.IceServer>())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val p = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> setStatus("متصل")
                    PeerConnection.IceConnectionState.DISCONNECTED -> setStatus("الاتصال ضعيف أو انقطع...")
                    PeerConnection.IceConnectionState.FAILED -> setStatus("فشل الاتصال. تأكد أن الهاتفين على نفس الراوتر")
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c != null) {
                    send(
                        JSONObject().put("t", "cand").put("m", c.sdpMid ?: "")
                            .put("i", c.sdpMLineIndex).put("c", c.sdp)
                    )
                }
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver?) {
                val track = t?.receiver?.track()
                if (track is VideoTrack) {
                    runOnUiThread { track.addSink(remoteView) }
                }
            }
        })
        pc = p
        p?.addTrack(videoTrack!!, listOf("stream"))
        p?.addTrack(audioTrack!!, listOf("stream"))
    }

    // ---------------------------------------------------------------- signaling (plain TCP on the LAN)

    private fun send(obj: JSONObject) {
        synchronized(lock) {
            try {
                writer?.println(obj.toString())
            } catch (_: Exception) {
            }
        }
    }

    private open inner class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { setStatus("خطأ: $e") }
        override fun onSetFailure(e: String?) { setStatus("خطأ: $e") }
    }

    private fun startAudioRouting() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } catch (_: Exception) {
        }
    }

    private fun stopAudioRouting() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private fun startHost() {
        bindWifi()
        isHost = true
        startAudioRouting()
        showInCall("بانتظار الهاتف الآخر...\nعنوان هذا الهاتف: ${localIps()}\nاكتب العنوان (الذي يبدأ بـ 192.168) في الهاتف الثاني ثم اضغط انضمام")
        Thread {
            val ss = ServerSocket()
            server = ss
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                val s = ss.accept()
                onSocket(s)
            } catch (e: Exception) {
                if (server === ss) {
                    setStatus("خطأ: ${e.message}")
                    runOnUiThread { endCall() }
                }
            }
        }.start()
    }

    private fun startJoin(ip: String) {
        if (ip.isEmpty()) {
            setStatus("اكتب عنوان الهاتف الآخر أولاً")
            return
        }
        bindWifi()
        isHost = false
        startAudioRouting()
        showInCall("جارٍ الاتصال بـ $ip ...")
        Thread {
            val s = Socket()
            try {
                socket = s
                s.connect(InetSocketAddress(ip, port), 8000)
                onSocket(s)
            } catch (e: Exception) {
                if (socket === s) {
                    setStatus("تعذر الاتصال: ${e.message}")
                    runOnUiThread { endCall() }
                }
            }
        }.start()
    }

    private fun onSocket(s: Socket) {
        socket = s
        synchronized(lock) {
            writer = PrintWriter(s.getOutputStream(), true)
            remoteSet = false
            pending.clear()
        }
        createPc()
        if (isHost) createOffer()
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                handle(JSONObject(line))
            }
        } catch (_: Exception) {
        }
        runOnUiThread {
            if (socket === s) {
                endCall()
                setStatus("انتهت المكالمة")
            }
        }
    }

    private fun createOffer() {
        val p = pc ?: return
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                send(JSONObject().put("t", "offer").put("sdp", d.description))
                p.setLocalDescription(Sdp(), d)
            }
        }, MediaConstraints())
    }

    private fun createAnswer() {
        val p = pc ?: return
        p.createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                send(JSONObject().put("t", "answer").put("sdp", d.description))
                p.setLocalDescription(Sdp(), d)
            }
        }, MediaConstraints())
    }

    private fun markRemoteSet() {
        synchronized(lock) {
            remoteSet = true
            for (c in pending) pc?.addIceCandidate(c)
            pending.clear()
        }
    }

    private fun handle(m: JSONObject) {
        val p = pc ?: return
        when (m.getString("t")) {
            "offer" -> p.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    markRemoteSet()
                    createAnswer()
                }
            }, SessionDescription(SessionDescription.Type.OFFER, m.getString("sdp")))

            "answer" -> p.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    markRemoteSet()
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))

            "cand" -> {
                val c = IceCandidate(m.optString("m", ""), m.getInt("i"), m.getString("c"))
                synchronized(lock) {
                    if (remoteSet) p.addIceCandidate(c) else pending.add(c)
                }
            }
        }
    }

    private fun endCall() {
        val s = socket
        val sv = server
        val p = pc
        socket = null
        server = null
        pc = null
        synchronized(lock) {
            writer = null
            remoteSet = false
            pending.clear()
        }
        Thread {
            try { s?.close() } catch (_: Exception) {}
            try { sv?.close() } catch (_: Exception) {}
        }.start()
        try { p?.dispose() } catch (_: Exception) {}
        try { remoteView.clearImage() } catch (_: Exception) {}
        stopAudioRouting()
        showMenu()
    }
}
