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
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {

    private val port = 8765
    private val discoveryPort = 8766

    // Total number of people in one call, including the host. Change this to allow more.
    private val maxPeers = 4

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var grid: LinearLayout
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

    private class Conn(val socket: Socket) {
        private val writer = PrintWriter(socket.getOutputStream(), true)
        fun send(o: JSONObject) {
            synchronized(this) { writer.println(o.toString()) }
        }
        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private class Peer(val id: Int) {
        @Volatile var pc: PeerConnection? = null
        @Volatile var remoteSet = false
        val pending = mutableListOf<IceCandidate>()
        @Volatile var view: SurfaceViewRenderer? = null
        @Volatile var track: VideoTrack? = null
        @Volatile var connected = false
    }

    private val peers = ConcurrentHashMap<Int, Peer>()
    private val clients = ConcurrentHashMap<Int, Conn>()
    @Volatile private var hostConn: Conn? = null
    @Volatile private var joinSocket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var callGen = 0
    private val lock = Any()
    private var myId = -1
    private var isHost = false
    private var inCall = false
    private var myIps = ""
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
        try { eglBase.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        root.addView(
            grid,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        localView = SurfaceViewRenderer(this)
        localView.init(eglBase.eglBaseContext, null)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        localView.setEnableHardwareScaler(true)
        localView.setMirror(true)
        localView.setZOrderMediaOverlay(true)
        val lp = FrameLayout.LayoutParams(dp(100), dp(135))
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
        statusText.text = "ثبّت التطبيق على الهواتف ووصلها كلها بنفس الراوتر"
        panel.addView(statusText)

        ipInput = EditText(this)
        ipInput.hint = "اتركه فارغاً للبحث التلقائي، أو اكتب عنوان المضيف"
        ipInput.setHintTextColor(Color.LTGRAY)
        ipInput.setTextColor(Color.WHITE)
        ipInput.inputType = InputType.TYPE_CLASS_TEXT
        panel.addView(ipInput)

        hostBtn = Button(this)
        hostBtn.text = "ابدأ مكالمة (أنا المضيف)"
        hostBtn.setOnClickListener {
            if (!mediaReady) {
                setStatus("لم تُمنح أذونات الكاميرا والميكروفون")
            } else {
                startHost()
            }
        }
        panel.addView(hostBtn)

        joinBtn = Button(this)
        joinBtn.text = "انضمام (بحث تلقائي عن المضيف)"
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

    private fun refreshStatus() {
        if (!inCall) return
        val total = peers.size
        val ok = peers.values.count { it.connected }
        val head = if (isHost) {
            "أنت المضيف. عنوانك: $myIps\nالهواتف الأخرى: اضغط انضمام (تجدك تلقائياً)\n"
        } else ""
        val body = if (total == 0) "بانتظار المشاركين..." else "متصل مع $ok من $total"
        setStatus(head + body)
    }

    // Must be called on the UI thread. Arranges the remote videos in a grid.
    private fun relayout() {
        grid.removeAllViews()
        val tiles = peers.values.sortedBy { it.id }.mapNotNull { it.view }
        val n = tiles.size
        if (n == 0) return
        val perRow = if (n <= 2) 1 else 2
        var i = 0
        while (i < n) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (j in 0 until perRow) {
                if (i < n) {
                    val v = tiles[i]
                    (v.parent as? ViewGroup)?.removeView(v)
                    row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    i++
                }
            }
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

    private fun isMobileIface(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") ||
            n.startsWith("tun") || n.startsWith("v4-")
    }

    private fun localIps(): String {
        val list = mutableListOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback || isMobileIface(ni.name)) continue
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

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback || isMobileIface(ni.name)) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null) out.add(b)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    // Host: announce "I am here" on the network once a second while the call is open.
    private fun startBeacon(ss: ServerSocket) {
        Thread {
            var ds: DatagramSocket? = null
            try {
                val d = DatagramSocket()
                ds = d
                d.broadcast = true
                val data = "LANCALL1".toByteArray()
                while (server === ss) {
                    for (addr in broadcastAddresses()) {
                        try {
                            d.send(DatagramPacket(data, data.size, addr, discoveryPort))
                        } catch (_: Exception) {
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (_: Exception) {
            } finally {
                try { ds?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // Joiner: listen for the host's announcement. Returns the host address or null.
    private fun discoverHost(gen: Int): String? {
        var ds: DatagramSocket? = null
        try {
            val d = DatagramSocket(null as SocketAddress?)
            ds = d
            d.reuseAddress = true
            d.bind(InetSocketAddress(discoveryPort))
            d.soTimeout = 1000
            val deadline = System.currentTimeMillis() + 12000
            val buf = ByteArray(64)
            while (gen == callGen && System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    d.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(pkt.data, 0, pkt.length)
                if (text.startsWith("LANCALL1")) return pkt.address.hostAddress
            }
        } catch (_: Exception) {
        } finally {
            try { ds?.close() } catch (_: Exception) {}
        }
        return null
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
            cap.startCapture(640, 480, 20)
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

    // ---------------------------------------------------------------- one WebRTC connection per other person

    private open inner class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { setStatus("خطأ: $e") }
        override fun onSetFailure(e: String?) { setStatus("خطأ: $e") }
    }

    private fun createPeer(id: Int): Peer {
        val peer = Peer(id)
        val config = PeerConnection.RTCConfiguration(ArrayList<PeerConnection.IceServer>())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val p = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                peer.connected = s == PeerConnection.IceConnectionState.CONNECTED ||
                    s == PeerConnection.IceConnectionState.COMPLETED
                refreshStatus()
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c != null) {
                    sendSig(
                        id,
                        JSONObject().put("k", "cand").put("m", c.sdpMid ?: "")
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
                    runOnUiThread {
                        peer.track = track
                        peer.view?.let { track.addSink(it) }
                    }
                }
            }
        })
        peer.pc = p
        p?.addTrack(videoTrack!!, listOf("stream"))
        p?.addTrack(audioTrack!!, listOf("stream"))
        peers[id] = peer
        runOnUiThread {
            if (peers[id] === peer) {
                val v = SurfaceViewRenderer(this)
                v.init(eglBase.eglBaseContext, null)
                v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                v.setEnableHardwareScaler(true)
                peer.view = v
                peer.track?.addSink(v)
                relayout()
            }
        }
        refreshStatus()
        return peer
    }

    private fun closePeer(id: Int, quiet: Boolean = false) {
        val peer = peers.remove(id) ?: return
        try { peer.pc?.dispose() } catch (_: Exception) {}
        runOnUiThread {
            relayout()
            try { peer.view?.let { peer.track?.removeSink(it) } } catch (_: Exception) {}
            try { peer.view?.release() } catch (_: Exception) {}
        }
        if (!quiet) refreshStatus()
    }

    private fun createOffer(peer: Peer) {
        val p = peer.pc ?: return
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                sendSig(peer.id, JSONObject().put("k", "offer").put("sdp", d.description))
                p.setLocalDescription(Sdp(), d)
            }
        }, MediaConstraints())
    }

    private fun createAnswer(peer: Peer) {
        val p = peer.pc ?: return
        p.createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                sendSig(peer.id, JSONObject().put("k", "answer").put("sdp", d.description))
                p.setLocalDescription(Sdp(), d)
            }
        }, MediaConstraints())
    }

    private fun markRemoteSet(peer: Peer) {
        synchronized(peer) {
            peer.remoteSet = true
            for (c in peer.pending) peer.pc?.addIceCandidate(c)
            peer.pending.clear()
        }
    }

    // A signaling message addressed to me (from another person).
    private fun handleSig(m: JSONObject) {
        val from = m.getInt("from")
        when (m.getString("k")) {
            "offer" -> {
                val peer = peers[from] ?: createPeer(from)
                peer.pc?.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        markRemoteSet(peer)
                        createAnswer(peer)
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, m.getString("sdp")))
            }

            "answer" -> {
                val peer = peers[from] ?: return
                peer.pc?.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        markRemoteSet(peer)
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")))
            }

            "cand" -> {
                val peer = peers[from] ?: return
                val c = IceCandidate(m.optString("m", ""), m.getInt("i"), m.getString("c"))
                synchronized(peer) {
                    if (peer.remoteSet) peer.pc?.addIceCandidate(c) else peer.pending.add(c)
                }
            }
        }
    }

    // All signaling goes through the host, which forwards messages between people.
    private fun sendSig(to: Int, body: JSONObject) {
        body.put("t", "sig").put("from", myId).put("to", to)
        if (isHost) clients[to]?.send(body) else hostConn?.send(body)
    }

    // ---------------------------------------------------------------- host

    private fun startHost() {
        bindWifi()
        isHost = true
        myId = 0
        inCall = true
        myIps = localIps()
        startAudioRouting()
        showInCall("")
        refreshStatus()
        Thread {
            val ss = ServerSocket()
            server = ss
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                startBeacon(ss)
                while (true) {
                    val s = ss.accept()
                    Thread { handleClient(s) }.start()
                }
            } catch (e: Exception) {
                if (server === ss) {
                    setStatus("خطأ: ${e.message}")
                    runOnUiThread { endCall() }
                }
            }
        }.start()
    }

    private fun handleClient(s: Socket) {
        val conn = try { Conn(s) } catch (_: Exception) { return }
        val id = synchronized(lock) {
            val free = (1 until maxPeers).firstOrNull { !clients.containsKey(it) } ?: -1
            if (free != -1) clients[free] = conn
            free
        }
        if (id == -1) {
            conn.send(JSONObject().put("t", "full"))
            conn.close()
            return
        }
        val existing = JSONArray()
        existing.put(0)
        for (k in clients.keys) if (k != id) existing.put(k)
        conn.send(JSONObject().put("t", "welcome").put("id", id).put("peers", existing))
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                val m = JSONObject(line)
                if (m.optString("t") == "sig") {
                    m.put("from", id)
                    val to = m.getInt("to")
                    if (to == 0) handleSig(m) else clients[to]?.send(m)
                }
            }
        } catch (_: Exception) {
        }
        // This person left: tell the others first, then free the slot.
        if (clients[id] === conn) {
            for ((k, c) in clients) {
                if (k != id) c.send(JSONObject().put("t", "left").put("id", id))
            }
            closePeer(id)
            clients.remove(id, conn)
        }
        conn.close()
    }

    // ---------------------------------------------------------------- joining

    private fun startJoin(typedIp: String) {
        bindWifi()
        isHost = false
        inCall = true
        startAudioRouting()
        callGen++
        val gen = callGen
        showInCall(
            if (typedIp.isEmpty()) "جارٍ البحث عن مضيف على الشبكة..."
            else "جارٍ الاتصال بـ $typedIp ..."
        )
        Thread {
            var ip = typedIp
            if (ip.isEmpty()) {
                ip = discoverHost(gen) ?: ""
                if (gen != callGen) return@Thread
                if (ip.isEmpty()) {
                    runOnUiThread {
                        if (gen == callGen) {
                            endCall()
                            setStatus("لم أجد أي مضيف. تأكد أن أحد الهواتف ضغط ابدأ مكالمة وأن الجميع على نفس الراوتر، أو اكتب العنوان يدوياً")
                        }
                    }
                    return@Thread
                }
                setStatus("وجدت المضيف $ip . جارٍ الاتصال...")
            }
            connectAndRun(ip, gen)
        }.start()
    }

    private fun connectAndRun(ip: String, gen: Int) {
        if (gen != callGen) return
        val s = Socket()
        joinSocket = s
        var connected = false
        var msg = "انتهت المكالمة"
        try {
            s.connect(InetSocketAddress(ip, port), 8000)
            connected = true
            hostConn = Conn(s)
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                val m = JSONObject(line)
                when (m.optString("t")) {
                    "welcome" -> {
                        myId = m.getInt("id")
                        val arr = m.getJSONArray("peers")
                        for (i in 0 until arr.length()) {
                            val peer = createPeer(arr.getInt(i))
                            createOffer(peer)
                        }
                    }
                    "sig" -> handleSig(m)
                    "left" -> closePeer(m.getInt("id"))
                    "full" -> {
                        msg = "المكالمة ممتلئة (الحد الأقصى $maxPeers أشخاص)"
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (!connected) msg = "تعذر الاتصال: ${e.message}"
        }
        runOnUiThread {
            if (joinSocket === s) {
                endCall()
                setStatus(msg)
            }
        }
    }

    // ---------------------------------------------------------------- end of call

    private fun endCall() {
        inCall = false
        callGen++
        val hc = hostConn
        val js = joinSocket
        val sv = server
        val cl = clients.values.toList()
        hostConn = null
        joinSocket = null
        server = null
        clients.clear()
        for (id in peers.keys.toList()) closePeer(id, true)
        myId = -1
        Thread {
            hc?.close()
            try { js?.close() } catch (_: Exception) {}
            try { sv?.close() } catch (_: Exception) {}
            for (c in cl) c.close()
        }.start()
        stopAudioRouting()
        showMenu()
    }
}
